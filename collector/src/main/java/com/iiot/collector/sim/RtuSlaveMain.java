package com.iiot.collector.sim;

import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;
import com.ghgande.j2mod.modbus.util.SerialParameters;

/**
 * RTU 从站测试桩 —— 没有硬件时，用它冒充现场仪表。
 *
 * <p>它<b>不是</b>产品代码路径的一部分（产品里我们的"从站"是 {@link SimSlaveMain}，跑 TCP）。
 * 刻意不复用那个类，因为：需要的是 <b>RTU over 串口</b>；而且测试桩要能<b>随手改数值/改布局</b>，
 * 不该给产品从站加测试开关。
 *
 * <p><b>两种布局</b>（`--layout`）：
 *
 * <table border="1">
 *   <caption>寄存器布局</caption>
 *   <tr><th>offset</th><th><code>float</code>（默认，用于验证字序）</th><th><code>sensor</code>（复刻真传感器）</th></tr>
 *   <tr><td>0</td><td>Float32 高字</td><td><b>湿度</b> uint16 × 0.1（如 644 → 64.4%RH）</td></tr>
 *   <tr><td>1</td><td>Float32 低字</td><td><b>温度</b> int16 × 0.1（如 256 → 25.6℃，支持负温）</td></tr>
 *   <tr><td>2</td><td>计数器（每秒 +1，证明从站活着）</td><td>计数器（同上）</td></tr>
 * </table>
 *
 * <p><b>为什么要 <code>sensor</code> 布局</b>：真实仪表（艾克姆 IKM10000WS01）就是
 * 「16 位整数 + 缩放 0.1」，不是 Float32。用同一布局做测试，CODESYS 里的 Modbus 主站配置
 * 才能**配一次、换真表只改 COM 号**。
 *
 * <p><b>用法</b>（配合 com0com 虚拟串口对 COM5 ↔ COM6）：
 * <pre>
 *   # ① 复刻真传感器（offset0=湿度、offset1=温度），给 CODESYS 的 Modbus 主站读
 *   java -cp collector\target\collector-0.1.0-SNAPSHOT.jar com.iiot.collector.sim.RtuSlaveMain ^
 *        --com COM6 --layout sensor --humidity 64.4 --temperature 25.6
 *
 *   # ② 字序测试桩（Float32，验证 ABCD/CDAB/BADC/DCBA）
 *   collector\run-rtu-slave.cmd                 # 默认 --layout float，温度 25.6
 *   collector\run-probe.cmd --com COM6          # 从另一头读 —— 四种字序各解一遍
 * </pre>
 *
 * <p><b>为什么默认温度是 25.6 而不是 25.5</b>：
 * {@code 25.5f = 0x41CC0000}，低 16 位是 <b>0</b>；那样 {ABCD@offset0} 和 {ABCD@offset1}
 * 会解出同一个数，<b>字序就分不出来了</b>。{@code 25.6f = 0x41CCCCCD} 高/低字都非零 ——
 * 这正是 CODESYS 那次踩过的坑。
 */
public final class RtuSlaveMain {

    private RtuSlaveMain() {
    }

    public static void main(String[] args) throws Exception {
        String comPort = stringArg(args, "--com", "COM5");
        int baudRate = intArg(args, "--baud", 9600);
        int unitId = intArg(args, "--unit", 1);
        float temperature = floatArg(args, "--temperature", 25.6f);
        float humidity = floatArg(args, "--humidity", 64.4f);
        String layout = stringArg(args, "--layout", "float").trim().toLowerCase();
        int seconds = intArg(args, "--seconds", 180);

        if (!layout.equals("float") && !layout.equals("sensor")) {
            System.err.println("--layout 只支持 float 或 sensor，实际是: " + layout);
            System.exit(2);
        }

        SerialParameters params = new SerialParameters();
        params.setPortName(comPort);
        params.setBaudRate(baudRate);
        params.setDatabits(8);
        params.setStopbits(1);
        params.setParity("none");
        params.setEncoding("rtu");
        params.setEcho(false);

        SimpleRegister counter = new SimpleRegister(0);
        SimpleProcessImage image = new SimpleProcessImage(unitId);

        int bits = Float.floatToIntBits(temperature);
        if (layout.equals("float")) {
            SimpleRegister highWord = new SimpleRegister((bits >>> 16) & 0xFFFF);
            SimpleRegister lowWord = new SimpleRegister(bits & 0xFFFF);
            image.addRegister(highWord);          // holding 0
            image.addRegister(lowWord);           // holding 1
            image.addRegister(counter);           // holding 2
            // 下面两个寄存器专门验证「16 位整数 + 缩放」（真实国产仪表的主流形态）：
            //   holding 3 = 0xFFCB → int16 -53 → ×0.1 → -5.3℃（补码/负温度）
            //   holding 4 = 501    → uint16 501 → ×0.1 → 50.1%RH
            image.addRegister(new SimpleRegister(0xFFCB));
            image.addRegister(new SimpleRegister(501));
        } else {
            // sensor 布局：与艾克姆 IKM10000WS01 对齐（offset0 湿度、offset1 温度，均 ×0.1）
            int humidityRaw = Math.round(humidity * 10f) & 0xFFFF;          // uint16
            int temperatureRaw = Math.round(temperature * 10f) & 0xFFFF;    // int16 补码
            image.addRegister(new SimpleRegister(humidityRaw));    // holding 0 湿度
            image.addRegister(new SimpleRegister(temperatureRaw)); // holding 1 温度
            image.addRegister(counter);                            // holding 2
        }
        for (int i = image.getRegisterCount(); i < 8; i++) {
            image.addRegister(new SimpleRegister(0));   // 补齐到 8，让 probe 一次读得顺
        }

        ModbusSlave slave = ModbusSlaveFactory.createSerialSlave(params);
        slave.addProcessImage(unitId, image);
        slave.open();

        System.out.printf("RTU 从站已启动：%s @ %d 8N1 · unit=%d · layout=%s%n",
                comPort, baudRate, unitId, layout);
        if (layout.equals("float")) {
            System.out.printf("  holding[0..1] = Float32 %s  (0x%08X → hi=0x%04X, lo=0x%04X)%n",
                    temperature, bits, (bits >>> 16) & 0xFFFF, bits & 0xFFFF);
            System.out.println("  holding[3] = 0xFFCB(-5.3℃)  holding[4] = 501(50.1%RH)  ← 16 位整数+缩放");
        } else {
            System.out.printf("  holding[0] = 湿度 %s → %d (0x%04X)%n",
                    humidity, Math.round(humidity * 10f), Math.round(humidity * 10f) & 0xFFFF);
            System.out.printf("  holding[1] = 温度 %s → %d (0x%04X)%n",
                    temperature, Math.round(temperature * 10f), Math.round(temperature * 10f) & 0xFFFF);
            System.out.println("  ← 与真传感器 IKM10000WS01 同布局（int16 × 0.1）");
        }
        System.out.println("  holding[2]    = 每秒 +1 的计数器（证明从站活着）");
        System.out.printf("  %d 秒后自动退出（Ctrl+C 也可）%n%n", seconds);

        Thread ticker = new Thread(() -> {
            int n = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                n++;
                counter.setValue(n & 0xFFFF);
                if (n % 10 == 0) {
                    System.out.printf("  [从站] counter=%d，温度仍是 %s%n", n, temperature);
                }
            }
        }, "rtu-slave-counter");
        ticker.setDaemon(true);
        ticker.start();

        Thread.sleep(seconds * 1000L);

        ticker.interrupt();
        slave.close();
        System.out.println("RTU 从站已关闭");
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

    private static float floatArg(String[] args, String name, float fallback) {
        String raw = stringArg(args, name, null);
        return raw == null ? fallback : Float.parseFloat(raw);
    }
}
