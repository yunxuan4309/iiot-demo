package com.iiot.collector;

import com.iiot.collector.core.DeviceConfig;
import com.iiot.collector.core.DeviceDriver;
import com.iiot.collector.core.DeviceSampler;
import com.iiot.collector.core.PointDef;
import com.iiot.collector.core.PointTableLoader;
import com.iiot.collector.core.PointValue;
import com.iiot.collector.core.Quality;
import com.iiot.collector.driver.TcpModbusDriver;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单设备持续采集 —— 验证「点表配置化 + 质量码」这条链路。
 *
 * <p>每行是「一次采样」：设备、质量码、四个点。读失败时质量码变 STALE/BAD，
 * 值保留上次的并打上标记 —— <b>不会变成 0</b>。
 *
 * <p>用法（点表默认读 classpath 的 {@code points.properties}）：
 * <pre>
 *   java -jar collector/target/collector-0.1.0-SNAPSHOT.jar ...   # 那是从站
 *   collector\run-collect.cmd --cycles 5
 *   collector\run-collect.cmd --points D:\my-points.properties --device sim-02
 * </pre>
 */
public final class CollectMain {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private CollectMain() {
    }

    public static void main(String[] args) throws Exception {
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printUsage();
            return;
        }

        String pointsFile = stringArg(args, "--points", null);
        String wantedDevice = stringArg(args, "--device", null);
        int cycles = intArg(args, "--cycles", 0);
        int timeoutMillis = intArg(args, "--timeout", 3000);
        long intervalOverride = intArg(args, "--interval", 0);

        List<DeviceConfig> configs = pointsFile == null
                ? PointTableLoader.loadFromClasspath("/points.properties")
                : PointTableLoader.loadFromFile(Path.of(pointsFile));

        DeviceConfig config = select(configs, wantedDevice);
        long periodMillis = intervalOverride > 0 ? intervalOverride : config.periodMillis();

        System.out.printf("设备 %s  点位 %d 个  周期 %d ms%n",
                config.endpoint(), config.points().size(), periodMillis);
        System.out.print("点表里的字序：");
        for (PointDef point : config.points()) {
            System.out.printf("%s=%s  ", point.id(),
                    point.type().isBoolean() ? "-" : point.wordOrder());
        }
        System.out.println("（来自配置，不是代码里写死的）");

        Map<String, PointDef.Type> types = new HashMap<>();
        for (PointDef point : config.points()) {
            types.put(point.id(), point.type());
        }

        int done = 0;
        // 按 endpoint 选驱动（TCP / RTU），单设备场景也用注册表，保证两种传输走同一套代码
        try (com.iiot.collector.driver.DriverRegistry registry =
                     new com.iiot.collector.driver.DriverRegistry(timeoutMillis)) {
            DeviceDriver driver = registry.driverFor(config.endpoint());
            DeviceSampler sampler = new DeviceSampler(config, driver);
            while (true) {
                long startedAt = System.nanoTime();
                print(sampler.poll(), sampler, types);
                done++;
                if (cycles > 0 && done >= cycles) {
                    break;
                }
                long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
                long sleep = periodMillis - elapsedMillis;
                if (sleep > 0) {
                    Thread.sleep(sleep);
                }
            }
        }
        System.out.println("采集结束（达到 --cycles " + cycles + "）");
    }

    private static void print(List<PointValue> values, DeviceSampler sampler, Map<String, PointDef.Type> types) {
        PointValue first = values.get(0);
        StringBuilder line = new StringBuilder(String.format("[%s] %-8s %-5s ",
                LocalTime.now().format(CLOCK), first.deviceId(), first.quality()));
        for (PointValue value : values) {
            line.append(String.format("%s=%-14s ", value.pointId(), format(value, types)));
        }
        System.out.println(line);
        if (sampler.lastError() != null) {
            System.out.printf("           ↳ 读失败：%s%n", sampler.lastError());
            if (first.quality() == Quality.BAD) {
                System.out.println("           ↳ 从未读到过好值 → BAD（值不可用，不是 0）");
            } else {
                System.out.printf("           ↳ 值保留上次、标 STALE（不是 0）；上次好值 %s%n",
                        LocalTime.ofInstant(sampler.lastGoodAt(), ZoneId.systemDefault()).format(CLOCK));
            }
        }
    }

    /** BOOL 打成 true/false（带质量码标记）；其余沿用 PointValue 的数值格式。 */
    private static String format(PointValue value, Map<String, PointDef.Type> types) {
        if (types.get(value.pointId()) == PointDef.Type.BOOL) {
            if (!Double.isFinite(value.value())) {
                return value.format();
            }
            String text = value.value() != 0 ? "true" : "false";
            return value.quality() == Quality.GOOD ? text : text + "(" + value.quality() + ")";
        }
        return value.format();
    }

    private static DeviceConfig select(List<DeviceConfig> configs, String wantedDevice) {
        if (wantedDevice == null) {
            return configs.get(0);
        }
        return configs.stream()
                .filter(c -> c.deviceId().equalsIgnoreCase(wantedDevice))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "点表里没有设备 " + wantedDevice + "，现有：" +
                                configs.stream().map(DeviceConfig::deviceId).toList()));
    }

    private static void printUsage() {
        System.out.println("""
                单设备持续采集（点表驱动，质量码 GOOD/STALE/BAD）

                  --points <file>   点表文件，默认 classpath:/points.properties
                  --device <id>     采集哪台设备，默认点表里第一台
                  --cycles <n>      采多少轮后退出，默认 0 = 一直跑
                  --interval <ms>   覆盖点表里的周期
                  --timeout <ms>    读超时，默认 3000
                  --help            显示本帮助
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
        if (raw == null) {
            return defaultValue;
        }
        return Integer.parseInt(raw.trim());
    }
}
