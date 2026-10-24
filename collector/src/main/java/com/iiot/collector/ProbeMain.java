package com.iiot.collector;

import com.ghgande.j2mod.modbus.facade.AbstractModbusMaster;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.facade.ModbusSerialMaster;
import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.util.SerialParameters;
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

        // 串口模式：--com COM3（给了就走 RTU，不给就走 TCP）
        String comPort = stringArg(args, "--com", null);
        int baudRate = intArg(args, "--baud", 9600);
        int dataBits = intArg(args, "--data-bits", 8);
        int stopBits = intArg(args, "--stop-bits", 1);
        String parity = stringArg(args, "--parity", "none");
        boolean rs485Mode = hasFlag(args, "--rs485-mode");

        AbstractModbusMaster master;
        if (comPort != null) {
            SerialParameters params = new SerialParameters();
            params.setPortName(comPort);
            params.setBaudRate(baudRate);
            params.setDatabits(dataBits);
            params.setStopbits(stopBits);
            params.setParity(parity);
            params.setEncoding("rtu");
            params.setEcho(false);
            if (rs485Mode) {
                params.setRs485Mode(true);
                params.setRs485TxEnableActiveHigh(true);
            }
            master = new ModbusSerialMaster(params, timeoutMillis);
            System.out.printf("连接 %s（%d-%d-%s-%d, unit=%d, timeout=%d ms）...%n",
                    comPort, baudRate, dataBits, parity.substring(0, 1).toUpperCase(), stopBits,
                    unitId, timeoutMillis);
        } else {
            master = new ModbusTCPMaster(host, port, timeoutMillis, false);
            System.out.printf("连接 %s:%d（unit=%d, timeout=%d ms）...%n", host, port, unitId, timeoutMillis);
        }
        try {
            master.connect();
        } catch (Exception e) {
            if (comPort != null) {
                printSerialHint(comPort, baudRate, parity, e);
            } else {
                printOfflineHint(host, port, e);
            }
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

    /**
     * 四个区域全探一遍 —— 现场设备不一定把数据放在 holding。
     *
     * <p>典型例子：CODESYS 的 Modbus 从站把 PLC 的 {@code %QW} 暴露成 <b>输入寄存器（FC04）</b>、
     * {@code %QX} 暴露成 <b>离散输入（FC02）</b>，而 {@code %IW} 才是保持寄存器。
     * 只会读 FC03 的探测器在那种设备上"什么都读不到"，很容易误判成"地址错了"。
     *
     * <p>另外每个区域<b>单独容错</b>：从站只实现了部分区域时（比如线圈数量配成 0），
     * 读它会被回 <b>Illegal Data Address</b>，不能让整个探测中断。
     */
    private static void readAndExplain(AbstractModbusMaster master, int unitId) {
        System.out.println();
        System.out.println("═══ 四个区域全探（现场设备可能把数据放在任意一个）═══");

        int[] holding = readRegisters(master, unitId, false, 0, PROBE_WORDS);
        int[] input = readRegisters(master, unitId, true, 0, PROBE_WORDS);
        boolean[] coils = readBits(master, unitId, false, 0, PROBE_WORDS);
        boolean[] discrete = readBits(master, unitId, true, 0, PROBE_WORDS);

        printRegisters("保持寄存器（FC03 · 惯用编号 40001+ · PLC 侧 %IW）", holding);
        printRegisters("输入寄存器（FC04 · 惯用编号 30001+ · PLC 侧 %QW）", input);
        printBits("线圈（FC01 · 00001+ · PLC 侧 %IX）", coils);
        printBits("离散输入（FC02 · 10001+ · PLC 侧 %QX）", discrete);

        System.out.println();
        System.out.println("═══ 按字序解析（只在有数据的寄存器区域做）═══");
        boolean found = false;
        found |= decodeCandidates("保持寄存器", holding);
        found |= decodeCandidates("输入寄存器", input);

        System.out.println();
        System.out.println("═══ 判定 ═══");
        if (found) {
            System.out.println("  ✅ 上面标 <== 的位置就是数据所在：按「区域 + offset + 字序」填进点表即可");
            System.out.println("     （同一台设备里不同点位可以用不同字序，那是正常的）");
        } else {
            System.out.println("  ❌ 四个区域都没读到像温度（10~100℃）的值。按这个顺序排查：");
            System.out.println("     1. 寄存器地址整体错位一位？（坑 2：40001 是惯用编号，报文传 offset 0）");
            System.out.println("     2. 数据类型不是 Float32？（可能是 Int16 × 10 倍缩放，温度 = 值/10）");
            System.out.println("     3. 从站只暴露了部分区域？（看上面哪个区域报 Illegal Data Address）");
            System.out.println("     4. 数据还没开始更新？（PLC 程序是不是 STOP 状态）");
        }
    }

    /** 一次读这么多字/位就够看清楚了；再多对定位没帮助。 */
    private static final int PROBE_WORDS = 8;

    /** 读寄存器区；失败返回 {@code null}（该区域不存在或未开放）。 */
    private static int[] readRegisters(AbstractModbusMaster master, int unitId,
                                       boolean inputArea, int ref, int count) {
        // 数量递减重试：现场设备常常只开放很少几个字，
        // 一次要 8 个会被回 Illegal Data Address（实测 CODESYS 配 1 位离散输入就是这样）。
        for (int n : new int[]{count, 4, 2}) {
            if (n > count) {
                continue;
            }
            try {
                InputRegister[] registers = inputArea
                        ? master.readInputRegisters(unitId, ref, n)
                        : master.readMultipleRegisters(unitId, ref, n);
                int[] values = new int[registers.length];
                for (int i = 0; i < registers.length; i++) {
                    values[i] = registers[i].getValue() & 0xFFFF;
                }
                return values;
            } catch (Exception ignored) {
                // 换小一点的数量再试
            }
        }
        return null;
    }

    /** 读位区域；失败返回 {@code null}。 */
    private static boolean[] readBits(AbstractModbusMaster master, int unitId,
                                      boolean inputArea, int ref, int count) {
        // 同上：数量递减重试。CODESYS 把离散输入配成 1 个时，一次要 8 位会报 Illegal Data Address。
        for (int n : new int[]{count, 4, 2, 1}) {
            if (n > count) {
                continue;
            }
            try {
                BitVector bits = inputArea
                        ? master.readInputDiscretes(unitId, ref, n)
                        : master.readCoils(unitId, ref, n);
                boolean[] values = new boolean[n];
                for (int i = 0; i < n; i++) {
                    values[i] = bits.getBit(i);
                }
                return values;
            } catch (Exception ignored) {
                // 换小一点的数量再试
            }
        }
        return null;
    }

    private static void printRegisters(String label, int[] values) {
        if (values == null) {
            System.out.printf("%n== %s ==%n  ❌ 读不到（该区域不存在 / 未开放 / 数量配成 0）%n", label);
            return;
        }
        boolean allZero = true;
        for (int v : values) {
            if (v != 0) {
                allZero = false;
                break;
            }
        }
        System.out.printf("%n== %s ==%n", label);
        for (int i = 0; i < values.length; i++) {
            System.out.printf("  reg[%d] = 0x%04X   %d%n", i, values[i], values[i]);
        }
        if (allZero) {
            System.out.println("  （全 0 —— 该区域开放了，但没有数据写进来）");
        }
    }

    private static void printBits(String label, boolean[] values) {
        if (values == null) {
            System.out.printf("%n== %s ==%n  ❌ 读不到（该区域不存在 / 未开放 / 数量配成 0）%n", label);
            return;
        }
        System.out.printf("%n== %s ==%n", label);
        StringBuilder sb = new StringBuilder("  ");
        for (int i = 0; i < values.length; i++) {
            sb.append('[').append(i).append(']').append(values[i] ? "1 " : "0 ");
        }
        System.out.println(sb);
    }

    /**
     * 在一个寄存器区里扫描「起始偏移 × 四种字序」的所有组合，打印落在合理温度区间的。
     *
     * @return 是否找到至少一个合理解释
     */
    private static boolean decodeCandidates(String label, int[] values) {
        if (values == null || values.length < 2) {
            return false;
        }
        boolean allZero = true;
        for (int v : values) {
            if (v != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero) {
            return false;
        }

        System.out.printf("%n  【%s】%n", label);
        boolean found = false;
        for (int offset = 0; offset + 1 < values.length; offset++) {
            for (WordOrder order : WordOrder.values()) {
                float value = order.floatOf(values[offset], values[offset + 1]);
                boolean plausible = value > 10 && value < 100;
                if (!plausible) {
                    continue;
                }
                found = true;
                System.out.printf("    offset %d + %-4s → %10s  <== 合理（10~100℃）%n",
                        offset, order, format(value));
            }
        }
        if (!found) {
            System.out.println("    （这个区域的原始值里没有能解成 10~100℃ 的组合）");
        }
        return found;
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

/** RTU 连不上时的排查提示 —— 顺序很重要：前四项都不是代码问题。 */
    private static void printSerialHint(String comPort, int baudRate, String parity, Exception e) {
        System.out.println();
        System.out.println("打不开串口 " + comPort + "（" + baudRate + "-8-" + parity + "-1）：" + e.getMessage());
        System.out.println();
        System.out.println("按现场顺序排除（前四项都不是代码问题）：");
        System.out.println("  1. COM 口对吗？PowerShell: [System.IO.Ports.SerialPort]::GetPortNames()");
        System.out.println("  2. 驱动装了吗？设备管理器 → 端口 → USB Serial Port (COMx)");
        System.out.println("  3. 串口被别的软件占用？（Modbus Poll / 组态软件 / 之前没退干净的进程）");
        System.out.println("  4. A/B 接反了吗？对调再试；GND 共地了吗？");
        System.out.println("  5. 波特率/校验位和仪表一致吗？（出厂常见 9600-8-N-1）");
        System.out.println("  6. 从站地址对吗？（出厂常见 1，范围 1~247）");
    }

    private static void printUsage() {
        System.out.println("""
                Modbus 探测：读一遍点位、猜字序、可选写线圈

                  --host <s>          主机，默认 127.0.0.1
                  --port <n>          端口，默认 1502
                  --com <COMx>        串口（给这个参数就走 RTU，忽略 --host/--port）
                  --baud <n>          波特率，默认 9600
                  --parity <s>        校验位 none/even/odd，默认 none
                  --data-bits <n>     数据位，默认 8
                  --stop-bits <n>     停止位，默认 1
                  --rs485-mode        芯片级 485 方向控制（仅板载原生 485 口；USB 转换器不要开）
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
