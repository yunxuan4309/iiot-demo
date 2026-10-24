package com.iiot.collector;

import com.iiot.collector.core.CollectStats;
import com.iiot.collector.core.DeviceConfig;
import com.iiot.collector.core.DeviceDriver;
import com.iiot.collector.core.DeviceFanout;
import com.iiot.collector.core.DeviceSampler;
import com.iiot.collector.core.PointTableLoader;
import com.iiot.collector.core.PointValue;
import com.iiot.collector.core.TelemetryRepository;
import com.iiot.collector.driver.TcpModbusDriver;
import com.iiot.collector.repo.CountingTelemetryRepository;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

        List<DeviceConfig> base = pointsFile == null
                ? PointTableLoader.loadFromClasspath("/points.properties")
                : PointTableLoader.loadFromFile(Path.of(pointsFile));

        List<DeviceConfig> configs = fanout > 0
                ? DeviceFanout.expand(base.get(0), fanout)
                : base;

        int pointsPerDevice = configs.get(0).points().size();
        CollectStats stats = new CollectStats(pointsPerDevice, 200_000);
        TelemetryRepository repository = new CountingTelemetryRepository();

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
                    runDevice(config, repository, stats, timeoutMillis, maxCycles, deadline, intervalOverride,
                            stop, echo);
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
                                  int timeoutMillis,
                                  int maxCycles,
                                  long deadline,
                                  long intervalOverride,
                                  AtomicBoolean stop,
                                  boolean echo) {
        long period = intervalOverride > 0 ? intervalOverride : config.periodMillis();
        int consecutiveFailures = 0;
        int done = 0;

        // 每台设备一个独立驱动：连接/锁/超时都隔离
        try (DeviceDriver driver = new TcpModbusDriver(timeoutMillis)) {
            DeviceSampler sampler = new DeviceSampler(config, driver);
            while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                long startedAt = System.nanoTime();
                List<PointValue> batch = sampler.poll();
                // 微秒：本机回环读一轮常常 <1ms，用毫秒会被截断成 0
                long latencyMicros = (System.nanoTime() - startedAt) / 1_000L;

                boolean commOk = sampler.lastError() == null;
                repository.write(batch);
                stats.record(config.deviceId(), batch, latencyMicros, commOk, sampler.lastRangeError());

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
