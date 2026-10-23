package com.iiot.collector;

import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.util.BitVector;
import com.iiot.collector.proto.WordOrder;
import com.iiot.collector.sim.SimDevice;

/**
 * 一次「抓取 + 猜字序」的验证程序 —— D1 的验收工具，对应计划书 §九 坑 1 / 坑 2。
 *
 * <p>它做三件事：
 * <ol>
 *   <li>按 <b>offset 0</b>（不是惯用编号 40001）读 4 个保持寄存器，把原始值打出来</li>
 *   <li>对 temperature 用 <b>四种字序</b> 各解析一遍 —— 正常情况下只有一种落在 10~100℃</li>
 *   <li>读线圈 / 离散输入，可选地写一次线圈（FC05）</li>
 * </ol>
 *
 * <p>用法：
 * <pre>
 *   java -cp collector/target/collector-0.1.0-SNAPSHOT.jar com.iiot.collector.ProbeMain --port 1502
 *   ... --write-motor true      # 写线圈，验证 FC05 与"写操作不幂等"（坑 4）
 * </pre>
 */
public final class ProbeMain {

    private ProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printUsage();
            return;
        }

        String host = stringArg(args, "--host", "127.0.0.1");
        int port = intArg(args, "--port", 1502);
        int unitId = intArg(args, "--unit", 1);
        int timeoutMillis = intArg(args, "--timeout", 3000);
        String writeMotor = stringArg(args, "--write-motor", null);

        ModbusTCPMaster master = new ModbusTCPMaster(host, port, timeoutMillis, false);
        System.out.printf("连接 %s:%d（unit=%d, timeout=%d ms）...%n", host, port, unitId, timeoutMillis);
        try {
            master.connect();
        } catch (Exception e) {
            printOfflineHint(host, port, e);
            System.exit(3);
            return;
        }

        try {
            readAndExplain(master, unitId);

            if (writeMotor != null) {
                boolean value = Boolean.parseBoolean(writeMotor);
                long startedAt = System.nanoTime();
                boolean acknowledged = master.writeCoil(unitId, SimDevice.COIL_MOTOR, value);
                long costMillis = (System.nanoTime() - startedAt) / 1_000_000L;
                System.out.printf("%n== 写线圈（FC05）%n  motor_running <= %s  ACK=%s  耗时 %d ms%n",
                        value, acknowledged, costMillis);
                System.out.println("  ⚠ 写操作**不幂等**：超时后重发可能让电机启停两次（坑 4），"
                        + "真实采集器必须靠读回确认，而不是靠重试");
                BitVector after = master.readCoils(unitId, SimDevice.COIL_MOTOR, 1);
                System.out.printf("  读回确认 motor_running = %s%n", after.getBit(0));
            }
        } finally {
            master.disconnect();
        }
    }

    private static void readAndExplain(ModbusTCPMaster master, int unitId) throws Exception {
        long startedAt = System.nanoTime();
        Register[] registers = master.readMultipleRegisters(unitId, SimDevice.REG_TEMP, 4);
        long costMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        System.out.printf("%n== 原始寄存器（FC03 holding，offset %d..%d ↔ 惯用编号 40001..40004）耗时 %d ms ==%n",
                SimDevice.REG_TEMP, SimDevice.REG_TEMP + registers.length - 1, costMillis);
        for (int i = 0; i < registers.length; i++) {
            int value = registers[i].getValue() & 0xFFFF;
            System.out.printf("  reg[%d] (4%04d) = 0x%04X   %d%n",
                    i, i + 1, value, value);
        }

        int tempReg0 = registers[0].getValue() & 0xFFFF;
        int tempReg1 = registers[1].getValue() & 0xFFFF;

        System.out.println();
        System.out.println("== temperature（Float32 = reg[0] + reg[1]）按四种字序解析 ==");
        WordOrder plausible = null;
        for (WordOrder order : WordOrder.values()) {
            float value = order.floatOf(tempReg0, tempReg1);
            boolean inRange = value > 10 && value < 100;
            if (inRange && plausible == null) {
                plausible = order;
            }
            System.out.printf("  %-4s %22s  %s%n", order, format(value),
                    inRange ? "<== 落在 10~100℃ 区间" : "（不像温度）");
        }

        int hoursReg0 = registers[2].getValue() & 0xFFFF;
        int hoursReg1 = registers[3].getValue() & 0xFFFF;
        System.out.println();
        System.out.println("== runtime_hours（UInt32 = reg[2] + reg[3]）按字序解析 ==");
        for (WordOrder order : WordOrder.values()) {
            long hours = order.uint32Of(hoursReg0, hoursReg1);
            System.out.printf("  %-4s %16d h   %s%n", order, hours,
                    hours > 0 && hours < 100_000 ? "<== 像一个合理的设备运行时长" : "（离谱）");
        }

        BitVector coils = master.readCoils(unitId, SimDevice.COIL_MOTOR, 1);
        BitVector inputs = master.readInputDiscretes(unitId, SimDevice.INPUT_ALARM, 1);
        System.out.println();
        System.out.println("== 开关量 ==");
        System.out.printf("  motor_running (coil %d, FC01)  = %s%n", SimDevice.COIL_MOTOR, coils.getBit(0));
        System.out.printf("  alarm         (input %d, FC02) = %s%n", SimDevice.INPUT_ALARM, inputs.getBit(0));

        System.out.println();
        if (plausible != null) {
            System.out.printf("判定：只有 %s 落在合理区间 → 这台设备的 temperature 是 %s%n", plausible, plausible);
            System.out.println("     （第一次读成天文数字是正常的，那就是字序没对上）");
        } else {
            System.out.println("判定：四种字序**都不**合理 → 要么设备不是 Float32，要么寄存器地址整体错位一位（坑 2）");
            System.out.println("      先确认 40001 是惯用编号、报文里要传 offset 0");
            System.exit(2);
        }
    }

    /** 大数用科学计数法，避免把控制台撑爆；小数保留两位。 */
    private static String format(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return String.valueOf(value);
        }
        if (Math.abs(value) >= 1e6f || (value != 0f && Math.abs(value) < 1e-3f)) {
            return String.format("%.4e", value);
        }
        return String.format("%.2f", value);
    }

    private static void printOfflineHint(String host, int port, Exception cause) {
        System.err.printf("%n连接失败（%s:%d）：%s%n", host, port, cause.getMessage());
        System.err.println("→ 设备离线，或从站根本没起来。这正是计划书 §九 坑 3 的场景：");
        System.err.println("  采集端必须把这一批点打成 BAD/STALE（质量码 = 1），");
        System.err.println("  绝不能把温度补成 0 —— \"0℃\" 会被当成真值，比没有数据更危险。");
        System.err.println("  启动从站：collector\\run-slave.cmd  或  "
                + "java -jar collector\\target\\collector-0.1.0-SNAPSHOT.jar --port 1502");
    }

    private static void printUsage() {
        System.out.println("""
                Modbus 探测：读一遍点位、猜字序、可选写线圈

                  --host <s>          主机，默认 127.0.0.1
                  --port <n>          端口，默认 1502
                  --unit <n>          从站号，默认 1
                  --timeout <ms>      超时，默认 3000
                  --write-motor <b>   写 motor_running（true/false），默认不写
                  --help              显示本帮助
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
