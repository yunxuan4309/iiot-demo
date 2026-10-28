package com.iiot.collector;

import com.iiot.collector.core.DeviceConfig;
import com.iiot.collector.core.DeviceDriver;
import com.iiot.collector.core.PointDef;
import com.iiot.collector.core.PointTableLoader;
import com.iiot.collector.core.PointValue;
import com.iiot.collector.driver.DriverRegistry;

import java.nio.file.Path;
import java.util.List;

/**
 * 数据桥：把 <b>A 设备的一个点</b>周期性地写到 <b>B 设备的一个点</b> 上。
 *
 * <p><b>为什么需要它</b>：CODESYS 软 PLC 没有 485 口（或者说，我们不想让 PLC 直接开串口），
 * 而真传感器只认 485。于是让采集端当"搬运工"：
 *
 * <pre>
 *   真传感器(COM7, RTU) ──读──→ 本程序 ──写──→ CODESYS PLC 的保持寄存器 %IW0
 *                                                    ↓
 *                                        PLC 程序：rTemp := %IW0 / 10
 *                                                    ↓
 *                                        xAlarm := rTemp &gt; 80.0   ← 判断仍然在 PLC 里
 * </pre>
 *
 * <p><b>职责边界（很重要）</b>：本程序<b>只搬运数据，不做任何判断</b>。
 * "超温要不要报警"这件事在 PLC 里决定 —— Java 负责"把现场的数字送进去、把结果读回来"。
 *
 * <p><b>反向缩放</b>：传感器读出来是工程值（如 24.7℃），PLC 的 WORD 寄存器要的是
 * 原始整数（247）。所以写之前要 <b>÷ scale</b> —— 因为 {@code 驱动.write()} 写的是裸寄存器值，
 * 它不替你缩放（见 {@code AbstractModbusDriver.write}）。
 *
 * <p>用法：
 * <pre>
 *   collector\run-bridge.cmd
 *   java ... BridgeMain --from &lt;传感器点表&gt; --to &lt;PLC点表&gt; --from-point temperature --to-point temp_in
 * </pre>
 */
public final class BridgeMain {

    private BridgeMain() {
    }

    public static void main(String[] args) throws Exception {
        String fromTable = stringArg(args, "--from", "collector/src/main/resources/points-sensor.properties");
        String toTable = stringArg(args, "--to", "collector/src/main/resources/points-codesys-writable.properties");
        String fromPointId = stringArg(args, "--from-point", "temperature");
        String toPointId = stringArg(args, "--to-point", "temp_in");
        long intervalMillis = intArg(args, "--interval", 1000);
        int cycles = intArg(args, "--cycles", 0);          // 0 = 一直跑
        int timeoutMillis = intArg(args, "--timeout", 3000);

        DeviceConfig source = single(PointTableLoader.loadFromFile(Path.of(fromTable)), fromTable);
        DeviceConfig target = single(PointTableLoader.loadFromFile(Path.of(toTable)), toTable);
        PointDef fromPoint = find(source, fromPointId);
        PointDef toPoint = find(target, toPointId);

        System.out.printf("数据桥：%s.%s ──→ %s.%s（每 %d ms）%n",
                source.deviceId(), fromPoint.id(), target.deviceId(), toPoint.id(), intervalMillis);
        System.out.printf("  源：%s%n", source.endpoint());
        System.out.printf("  目标：%s（区域 %s · offset %d · scale %s）%n",
                target.endpoint(), toPoint.area(), toPoint.offset(), toPoint.scale());
        System.out.println("  注意：本程序只搬运，判断在 PLC 里做（xAlarm := rTemp > 80）");
        System.out.println();

        int okCount = 0;
        int failCount = 0;
        try (DriverRegistry drivers = new DriverRegistry(timeoutMillis)) {
            DeviceDriver sourceDriver = drivers.driverFor(source.endpoint());
            DeviceDriver targetDriver = drivers.driverFor(target.endpoint());
            int i = 0;
            while (cycles == 0 || i < cycles) {
                i++;
                try {
                    List<PointValue> read = sourceDriver.read(source.endpoint(), List.of(fromPoint));
                    PointValue value = read.get(0);
                    if (!value.isGood()) {
                        System.out.printf("  [%d] 源数据质量 %s，跳过（不把坏数据喂给 PLC）%n", i, value.quality());
                        failCount++;
                    } else {
                        // 反向缩放：工程值 → 原始寄存器整数（写接口不做缩放）
                        double raw = toPoint.scale() == 1.0 ? value.value() : value.value() / toPoint.scale();
                        targetDriver.write(target.endpoint(), toPoint, raw);
                        okCount++;
                        System.out.printf("  [%d] ✓ %.2f%s → %s.%s = %d（原始值）%n",
                                i, value.value(), value.unit() == null ? "" : value.unit(),
                                target.deviceId(), toPoint.id(), Math.round(raw));
                    }
                } catch (Exception e) {
                    failCount++;
                    System.out.printf("  [%d] ✗ %s%n", i, e.getMessage());
                }
                if (cycles == 0 || i < cycles) {
                    Thread.sleep(intervalMillis);
                }
            }
        }
        System.out.printf("%n结束：成功 %d 次 / 失败 %d 次%n", okCount, failCount);
        if (failCount > 0) {
            System.exit(1);
        }
    }

    private static DeviceConfig single(List<DeviceConfig> configs, String table) {
        if (configs.isEmpty()) {
            throw new IllegalArgumentException("点表里没有设备: " + table);
        }
        return configs.get(0);
    }

    private static PointDef find(DeviceConfig device, String pointId) {
        return device.points().stream()
                .filter(p -> p.id().equals(pointId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "设备 " + device.deviceId() + " 里没有点位 " + pointId
                                + "（可用：" + device.points().stream().map(PointDef::id).toList() + "）"));
    }

    private static String stringArg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static int intArg(String[] args, String name, int fallback) {
        String raw = stringArg(args, name, null);
        return raw == null ? fallback : Integer.parseInt(raw);
    }
}
