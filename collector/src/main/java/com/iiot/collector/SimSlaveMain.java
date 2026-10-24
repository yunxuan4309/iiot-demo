package com.iiot.collector;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;
import com.iiot.collector.proto.WordOrder;
import com.iiot.collector.sim.SimDevice;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 仿真 Modbus TCP 从站 —— <b>独立 main，可随时停掉用来演练"设备离线"</b>。
 *
 * <p>停掉它以后，采集端读不到数据，正好用来做计划书 §九 坑 3（断线不是 0，是"过期"）和坑 5
 * （单设备拖垮采集）的演练：应当是 <b>BAD/STALE 质量码 + 超时后设备级隔离</b>，而不是把温度写成 0。
 *
 * <p>用法：
 * <pre>
 *   java -jar collector/target/collector-0.1.0-SNAPSHOT.jar --port 1502
 *   java -jar ... --port 1502 --devices 10 --temp-order CDAB
 *   （Ctrl+C 停止，等价于现场把网线拔了）
 * </pre>
 *
 * <p>端口用 <b>1502</b> 而不是 502：Linux 下 <1024 需要 root，Windows 上也容易被别的服务占；
 * 1502 是我们自己的约定，写在配置里就能对上。
 */
public final class SimSlaveMain {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    /** 采样周期基准（毫秒）。 */
    private static final long BASE_PERIOD_MS = 1000L;
    /** 采样抖动上限（毫秒）：真设备的采样不会精确到 1.000 s。 */
    private static final int JITTER_MS = 20;

    private SimSlaveMain() {
    }

    public static void main(String[] args) throws Exception {
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printUsage();
            return;
        }

        int port = intArg(args, "--port", 1502);
        int firstUnitId = intArg(args, "--unit", 1);
        int deviceCount = intArg(args, "--devices", 1);
        WordOrder tempOrder = WordOrder.parse(stringArg(args, "--temp-order", "CDAB"));
        WordOrder hoursOrder = WordOrder.parse(stringArg(args, "--hours-order", "ABCD"));
        double baseTemperature = doubleArg(args, "--base-temp", 62.0);
        boolean quiet = hasFlag(args, "--quiet");

        if (deviceCount <= 0) {
            System.err.println("--devices 必须 >= 1");
            System.exit(2);
        }

        ModbusSlave slave;
        try {
            slave = ModbusSlaveFactory.createTCPSlave(port, Math.max(5, deviceCount));
        } catch (ModbusException e) {
            System.err.printf("端口 %d 起不来（可能已被占用，或上一个从站还没退干净）：%s%n", port, e.getMessage());
            System.exit(1);
            return;
        }

        List<SimDevice> devices = new ArrayList<>(deviceCount);
        for (int i = 0; i < deviceCount; i++) {
            // 温度偏移用「锯齿」而不是线性 i * 0.7：
            // 同一个厂房里 100 个测点不会"编号越大越热"。线性递增到第 83 台就冲到 120℃，
            // 会被点表的量程校验判成 BAD（校验没错，是仿真模型不真实）。
            double temperatureSpread = (i % 40) * 0.7;
            SimDevice device = new SimDevice(firstUnitId + i, tempOrder, hoursOrder, baseTemperature + temperatureSpread);
            slave.addProcessImage(firstUnitId + i, device.image());
            devices.add(device);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            System.out.println("[从站] 收到停止信号，关闭监听 → 采集端此时应当看到超时，而不是 0 值");
            ModbusSlaveFactory.close(slave);
        }, "slave-shutdown"));

        slave.open();

        System.out.printf("[从站] 已启动 127.0.0.1:%d  devices=%d  unitId=%d..%d  tempOrder=%s  hoursOrder=%s%n",
                port, deviceCount, firstUnitId, firstUnitId + deviceCount - 1, tempOrder, hoursOrder);
        System.out.printf("[从站] 采样周期 %d ms ±%d ms，数据发生器：一阶低通 + 3 分钟漂移 + 0.5%% 尖峰%n",
                BASE_PERIOD_MS, JITTER_MS);
        System.out.println("[从站] Ctrl+C 停止（= 设备离线），采集端应把这一批点打成 BAD/STALE");

        Random jitter = new Random();
        long lastNanos = System.nanoTime();
        while (!Thread.currentThread().isInterrupted()) {
            long sleep = BASE_PERIOD_MS + jitter.nextInt(JITTER_MS * 2 + 1) - JITTER_MS;
            Thread.sleep(Math.max(1, sleep));

            long now = System.nanoTime();
            long elapsedMillis = (now - lastNanos) / 1_000_000L;
            lastNanos = now;

            for (SimDevice device : devices) {
                device.tick(elapsedMillis);
            }
            if (!quiet) {
                System.out.printf("[%s] %s%n", LocalTime.now().format(CLOCK),
                        devices.get(0).statusLine());
            }
        }
    }

    private static void printUsage() {
        System.out.println("""
                仿真 Modbus TCP 从站（独立 main，可随时停止以演练设备离线）

                  --port <n>          监听端口，默认 1502
                  --unit <n>          第一个从站号，默认 1；多设备依次 +1
                  --devices <n>       设备数量，默认 1（同一个端口多从站号 = RS485 网关挂多台表）
                  --temp-order <o>    temperature 的字序，默认 CDAB（国内仪表常见）
                  --hours-order <o>   runtime_hours 的字序，默认 ABCD
                  --base-temp <f>     温度基准值，默认 62.0（想演练告警可设 79）
                  --quiet             不打印每周期状态
                  --help              显示本帮助

                字序可选：ABCD / CDAB / BADC / DCBA
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
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 需要整数，实际是: " + raw);
        }
    }

    private static double doubleArg(String[] args, String name, double defaultValue) {
        String raw = stringArg(args, name, null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 需要小数，实际是: " + raw);
        }
    }
}
