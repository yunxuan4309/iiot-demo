package com.iiot.collector;

import com.iiot.collector.core.CollectStats;
import com.iiot.collector.core.DeviceConfig;
import com.iiot.collector.core.DeviceDriver;
import com.iiot.collector.core.DeviceFanout;
import com.iiot.collector.core.DeviceSampler;
import com.iiot.collector.core.PointTableLoader;
import com.iiot.collector.core.PointValue;
import com.iiot.collector.core.TelemetryRepository;
import com.iiot.collector.driver.DriverRegistry;
import com.iiot.collector.core.AlarmEngine;
import com.iiot.collector.repo.CountingTelemetryRepository;
import com.iiot.collector.north.MqttPublisher;
import com.iiot.collector.repo.TdengineTelemetryRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多设备并发采集 —— <b>每台设备一条虚拟线程 + 独立驱动实例</b>。
 *
 * <h2>为什么是「每设备一个驱动实例」</h2>
 * {@link TcpModbusDriver#read} 是 {@code synchronized} 的（它内部复用一个连接池）。
 * 如果多设备共用一个实例，<b>100 台会退化成串行</b> —— 一台慢、全体等。
 * 所以这里每台设备 new 一个驱动：<b>连接隔离、锁隔离、超时隔离</b>。
 * 这也符合现场语义：一台表一条链路，一台坏了不影响其他。
 *
 * <h2>三层隔离（坑 5 的解法）</h2>
 * <ol>
 *   <li><b>线程隔离</b>：每台设备一条虚拟线程，一台阻塞不会占住别人</li>
 *   <li><b>时间隔离</b>：驱动设了读超时（{@code --timeout}），"连上但不回包"也会被掐断</li>
 *   <li><b>异常隔离</b>：设备循环里 {@code catch (Throwable)} 全兜住，一台崩了不影响其他台</li>
 * </ol>
 *
 * <h2>失败退避</h2>
 * 连续失败时按 1×/2×/4×/8× 周期线性放大间隔（上限 30s）——
 * 一台掉线的设备不应该继续以 1Hz 去打它，那是在给网络和从站添乱。
 *
 * <h2>用法</h2>
 * <pre>
 *   collector\run-slave.cmd --port 1502 --devices 100
 *   collector\run-multi.cmd --fanout 100 --duration 60
 * </pre>
 */
public final class MultiDeviceMain {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private MultiDeviceMain() {
    }

    public static void main(String[] args) throws Exception {
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printUsage();
            return;
        }

        String pointsFile = stringArg(args, "--points", null);
        int fanout = intArg(args, "--fanout", 0);
        int maxCycles = intArg(args, "--cycles", 0);
        int durationSeconds = intArg(args, "--duration", 0);
        int timeoutMillis = intArg(args, "--timeout", 2000);
        long intervalOverride = intArg(args, "--interval", 0);
        int reportEverySeconds = intArg(args, "--report-every", 5);
        boolean verbose = hasFlag(args, "--verbose");

        // 存储后端：counting（不落盘，用来建立采集端基线）| tdengine（真库）
        String store = stringArg(args, "--store", "counting");
        String tdUrl = stringArg(args, "--td-url", "http://localhost:6041");
        String tdDb = stringArg(args, "--td-db", "iiot");
        String tdUser = stringArg(args, "--td-user", "root");
        String tdPass = stringArg(args, "--td-pass", "taosdata");

        List<DeviceConfig> base = pointsFile == null
                ? PointTableLoader.loadFromClasspath("/points.properties")
                : PointTableLoader.loadFromFile(Path.of(pointsFile));

        List<DeviceConfig> configs = fanout > 0
                ? DeviceFanout.expand(base.get(0), fanout)
                : base;

        int pointsPerDevice = configs.get(0).points().size();
        CollectStats stats = new CollectStats(pointsPerDevice, 200_000);
        TelemetryRepository repository = createRepository(store, tdUrl, tdDb, tdUser, tdPass);
        // 告警引擎：--alarm 才启用（默认关，避免影响纯性能测试的基线数字）
        AlarmEngine alarms = createAlarmEngine(args);
        // A/B 模式：同一批采样点同时喂给「去抖」和「不去抖」两个引擎 ——
        // 保证对比的是"配置差异"，不是"两次运行的信号差异"。
        final MqttPublisher mqtt = createMqttPublisher(args);
        // 驱动按"物理链路"复用：RTU 一个 COM 口只能打开一次，总线上所有 unitId 共用一条连接
        final DriverRegistry drivers = new DriverRegistry(timeoutMillis);
        final AlarmEngine strictAlarms;
        if (alarms != null && hasFlag(args, "--alarm-ab")) {
            strictAlarms = strictAlarmEngine();
            System.out.println("A/B 模式：同一批数据同时喂给『去抖(5s+回差5℃)』与『不去抖』两个引擎");
        } else {
            strictAlarms = null;
        }
        if (alarms != null) {
            System.out.println("告警引擎已启用 · 规则点位 " + alarms.rules().keySet()
                    + "（去抖 + 回差 + 分级 + 质量码门禁）");
        }

        System.out.printf("多设备采集启动：%d 台 × %d 点 · 周期 %d ms · 读超时 %d ms%n",
                configs.size(), pointsPerDevice,
                intervalOverride > 0 ? intervalOverride : configs.get(0).periodMillis(), timeoutMillis);
        System.out.printf("存储实现：%s%n", repository.describe());
        System.out.printf("目标：%s%n", configs.get(0).endpoint().host() + ":" + configs.get(0).endpoint().port()
                + "  从站号 1.." + configs.size());
        if (configs.size() <= 3 || verbose) {
            System.out.println("（设备数少，逐轮打印每台的值）");
        } else {
            System.out.println("（设备多，只打印汇总进度；加 --verbose 可逐台打印）");
        }

        long deadline = durationSeconds > 0
                ? System.currentTimeMillis() + durationSeconds * 1000L
                : 0;
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch allDone = new CountDownLatch(configs.size());
        boolean echo = configs.size() <= 3 || verbose;

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        for (DeviceConfig config : configs) {
            executor.submit(() -> {
                try {
                    runDevice(config, repository, stats, alarms, strictAlarms, mqtt, drivers, timeoutMillis, maxCycles,
                            deadline, intervalOverride, stop, echo);
                } finally {
                    allDone.countDown();
                }
            });
        }

        // 主线程：打进度 + 看何时收工
        Thread reporter = Thread.ofVirtual().start(() -> {
            while (!stop.get()) {
                try {
                    Thread.sleep(reportEverySeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!stop.get() && configs.size() > 3 && !echo) {
                    System.out.printf("[%s]%s%n", LocalTime.now().format(CLOCK), stats.progressLine());
                }
            }
        });

        waitFor(allDone, maxCycles, deadline, configs.size());
        stop.set(true);
        reporter.interrupt();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        repository.close();

        System.out.print(stats.report());
        if (repository instanceof TdengineTelemetryRepository tdengine) {
            System.out.println(tdengine.summaryLine());
        }
        if (alarms != null) {
            System.out.print(alarms.report());
        }
        if (mqtt != null) {
            System.out.println(mqtt.summaryLine());
        }
        if (strictAlarms != null) {
            System.out.println();
            System.out.println("══════════ A/B 对比（同一批采样点，只改配置）══════════");
            System.out.printf("  不去抖（持续 0 ms、回差 0℃）   → %,d 条告警%n", strictAlarms.raisedCount());
            System.out.printf("  去抖+回差（持续 5 s、回差 5℃） → %,d 条告警%n", alarms.raisedCount());
            long before = strictAlarms.raisedCount();
            long after = alarms.raisedCount();
            if (before > 0) {
                System.out.printf("  ★ 减少 %,d 条（压掉 %.1f%%）%n",
                        before - after, 100.0 * (before - after) / before);
            } else {
                System.out.println("  （本次没有告警，无法比较）");
            }
            System.out.println("══════════════════════════════════════════════════════");
        }
        // ⚠️ Paho 的客户端线程不是守护线程：不显式 close()，JVM 打了报告也不退出。
        // 这不是"卡住"，是"干完活了但线程还挂着" —— 实测踩过一次。
        if (mqtt != null) {
            mqtt.close();
        }
        drivers.close();
    }

    /** 对照组：完全不设防（持续 0ms、回差 0）—— 等价于"每个越限采样点都报警"。 */
    private static AlarmEngine strictAlarmEngine() {
        Properties props = new Properties();
        props.setProperty("alarm.temperature.warningHigh", "80");
        props.setProperty("alarm.temperature.criticalHigh", "85");
        props.setProperty("alarm.temperature.recoverOffset", "0");
        props.setProperty("alarm.temperature.durationMillis", "0");
        return AlarmEngine.fromProperties(props);
    }

    /**
     * 构造告警引擎。
     *
     * <p>{@code --alarm} 用内置规则（classpath 里的 {@code alarms.properties}）；
     * {@code --alarm-rules <file>} 用外部文件 —— 现场改阈值不改代码，也方便做 A/B 对比
     * （同一批数据，一份"不去抖"、一份"去抖+回差"，直接看告警条数差多少）。
     */
    /**
     * 构造北向 MQTT 发布端。
     *
     * <p>只有 {@code --northbound mqtt} 才建立连接 —— 默认不连，
     * 这样"只想看采集性能"的场景不需要 broker，也不会有一次多余的 TCP 连接。
     */
    private static MqttPublisher createMqttPublisher(String[] args) {
        String northbound = stringArg(args, "--northbound", "none");
        if (!northbound.toLowerCase().contains("mqtt")) {
            return null;
        }
        String url = stringArg(args, "--mqtt-url", "tcp://localhost:1883");
        int qos = intArg(args, "--mqtt-qos", 1);
        boolean retain = !hasFlag(args, "--mqtt-no-retain");
        String clientId = "iiot-collector-" + ProcessHandle.current().pid();
        MqttPublisher publisher = new MqttPublisher(url, clientId, qos, retain);
        System.out.println("北向 MQTT 已连接 · " + url + " · topic iiot/{device}/{point}"
                + " · QoS " + qos + " · retain=" + retain);
        return publisher;
    }

    private static AlarmEngine createAlarmEngine(String[] args) {
        String rulesFile = stringArg(args, "--alarm-rules", null);
        if (rulesFile == null && !hasFlag(args, "--alarm")) {
            return null;
        }
        AlarmEngine engine;
        if (rulesFile != null) {
            try {
                engine = AlarmEngine.load(Path.of(rulesFile));
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "读取告警规则失败: " + rulesFile + " —— " + e.getMessage(), e);
            }
        } else {
            engine = AlarmEngine.loadDefault();
        }
        if (engine != null) {
            System.out.println("告警引擎已启用 · 规则点位 " + engine.rules().keySet()
                    + "（去抖 + 回差 + 分级 + 质量码门禁）");
        }
        return engine;
    }

    /** 按 {@code --store} 选存储实现 —— 换库只改这一处，采集逻辑一行不动。 */
    private static TelemetryRepository createRepository(String store, String url, String db,
                                                       String user, String password) {        return switch (store.toLowerCase()) {
            case "tdengine", "td" -> new TdengineTelemetryRepository(url, db, user, password);
            case "counting", "count", "none" -> new CountingTelemetryRepository();
            default -> throw new IllegalArgumentException(
                    "未知存储 --store " + store + "（可选 counting / tdengine）");
        };
    }

    /** 等到：全部设备跑完 cycles / 到 deadline / 被中断。 */
    private static void waitFor(CountDownLatch latch, int maxCycles, long deadline, int deviceCount)
            throws InterruptedException {
        while (true) {
            if (latch.await(500, TimeUnit.MILLISECONDS)) {
                return;
            }
            if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                return;
            }
            if (maxCycles <= 0 && deadline <= 0 && deviceCount > 0) {
                // 无限模式：一直采（Ctrl+C 退出）
                continue;
            }
        }
    }

    private static void runDevice(DeviceConfig config,
                                  TelemetryRepository repository,
                                  CollectStats stats,
                                  AlarmEngine alarms,
                                  AlarmEngine strictAlarms,
                                  MqttPublisher mqtt,
                                  DriverRegistry drivers,
                                  int timeoutMillis,
                                  int maxCycles,
                                  long deadline,
                                  long intervalOverride,
                                  AtomicBoolean stop,
                                  boolean echo) {
        long period = intervalOverride > 0 ? intervalOverride : config.periodMillis();
        int consecutiveFailures = 0;
        int done = 0;

        // 从注册表拿驱动：RTU 返回同一条共享连接（一个 COM 口只能打开一次），TCP 每台独立
        DeviceDriver driver = drivers.driverFor(config.endpoint());
        try {
            DeviceSampler sampler = new DeviceSampler(config, driver);
            while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                long startedAt = System.nanoTime();
                List<PointValue> batch = sampler.poll();
                // 微秒：本机回环读一轮常常 <1ms，用毫秒会被截断成 0
                long latencyMicros = (System.nanoTime() - startedAt) / 1_000L;

                boolean commOk = sampler.lastError() == null;
                repository.write(batch);
                stats.record(config.deviceId(), batch, latencyMicros, commOk, sampler.lastRangeError());

                // 告警判断：注意只对 GOOD 数据判（质量码门禁在引擎内部）
                if (alarms != null) {
                    for (PointValue value : batch) {
                        for (AlarmEngine.Event event : alarms.onSample(value)) {
                            synchronized (System.out) {
                                System.out.println(event.format());
                            }
                        }
                        // A/B：同一批数据也喂给"不去抖"的引擎（不打印，只计数）
                        if (strictAlarms != null) {
                            strictAlarms.onSample(value);
                        }
                    }
                }

                // 北向 MQTT：与南向解耦 —— 发布失败只计数，绝不影响采集
                if (mqtt != null) {
                    for (PointValue value : batch) {
                        mqtt.publish(value, value.unit());
                    }
                }

                if (echo) {
                    printBatch(config, batch, sampler, latencyMicros);
                }

                consecutiveFailures = commOk ? 0 : consecutiveFailures + 1;
                done++;
                if (maxCycles > 0 && done >= maxCycles) {
                    return;
                }
                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    return;
                }
                Thread.sleep(backoff(period, consecutiveFailures));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // 设备级隔离：一台设备的任何异常都不影响其他设备
            stats.deviceCrashed(config.deviceId(), t);
        }
    }

    /**
     * 失败退避：1× / 2× / 4× / 8× 周期，上限 30 秒。
     *
     * <p>一台掉线的设备继续以满速轮询是没有意义的 —— 只会把网络和从站拖得更慢。
     */
    private static long backoff(long periodMillis, int consecutiveFailures) {
        if (consecutiveFailures <= 0) {
            return periodMillis;
        }
        int shift = Math.min(consecutiveFailures - 1, 3);
        return Math.min(periodMillis << shift, 30_000L);
    }

    private static void printBatch(DeviceConfig config, List<PointValue> batch,
                                   DeviceSampler sampler, long latencyMicros) {
        StringBuilder line = new StringBuilder(String.format("[%s] %-9s %-5s %6.2fms ",
                LocalTime.now().format(CLOCK), config.deviceId(), batch.get(0).quality(), latencyMicros / 1000.0));
        for (PointValue value : batch) {
            line.append(String.format("%s=%s ", value.pointId(), value.format()));
        }
        System.out.println(line);
        if (sampler.lastError() != null) {
            System.out.printf("            ↳ 通信失败：%s%n", sampler.lastError());
        }
        if (sampler.lastRangeError() != null) {
            System.out.printf("            ↳ 量程越界：%s%n", sampler.lastRangeError());
        }
    }

    private static void printUsage() {
        System.out.println("""
                多设备并发采集（虚拟线程，每设备一条）

                  --points <file>      点表文件，默认 classpath:/points.properties
                  --fanout <n>         把点表里第一台设备复制成 n 台（同 host/port，从站号 1..n）
                  --duration <sec>     跑多少秒后收工，默认 0 = 一直跑
                  --cycles <n>         每台采多少轮后收工，默认 0 = 不限（优先于 duration 之外）
                  --interval <ms>      覆盖点表里的周期
                  --timeout <ms>       单次读超时，默认 2000
                  --report-every <sec> 进度打印间隔，默认 5
                  --verbose            设备多时也逐台打印

                告警（去抖 + 回差 + 分级 + 质量码门禁）：
                  --alarm              启用告警引擎（规则见 resources/alarms.properties）
                                       ⚠️ 只对 GOOD 数据判告警；STALE/BAD 一律不报
                  --alarm-rules <file> 用外部规则文件（现场改阈值 / 做 A/B 对比）
                  --alarm-ab            A/B：同一批数据同时喂「去抖」与「不去抖」两个引擎

                北向 MQTT（发布订阅，一对多分发）：
                  --northbound mqtt    启用北向 MQTT 发布（默认 none）
                  --mqtt-url <url>     broker 地址，默认 tcp://localhost:1883
                  --mqtt-qos <n>       QoS，默认 1（至少一次）
                  --mqtt-no-retain     关闭 retain（默认开：新订阅者立刻拿到最新值）

                存储（--store）：
                  counting             不落盘，只计数（默认，用来建立采集端基线）
                  tdengine             写入 TDengine（REST 接口）
                    --td-url   <url>    默认 http://localhost:6041
                    --td-db    <name>   默认 iiot
                    --td-user  <name>   默认 root
                    --td-pass  <pass>   默认 taosdata

                  --help               显示本帮助
                """);
    }

    private static boolean hasFlag(String[] args, String name) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String stringArg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equalsIgnoreCase(name)) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private static int intArg(String[] args, String name, int defaultValue) {
        String raw = stringArg(args, name, null);
        return raw == null ? defaultValue : Integer.parseInt(raw.trim());
    }
}
