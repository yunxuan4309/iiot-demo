package com.iiot.collector.sim;

import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;
import com.ghgande.j2mod.modbus.util.SerialParameters;

/**
 * RTU 从站测试桩 —— <b>只为了在没有硬件时验证 {@code RtuModbusDriver} 真的能从串口读到数</b>。
 *
 * <p>它<b>不是</b>产品代码路径的一部分（产品里我们的"从站"是 {@link SimSlaveMain}，跑 TCP）。
 * 这里刻意不复用那个类，因为：
 * <ul>
 *   <li>需要的是 <b>RTU over 串口</b>，而 {@code SimSlaveMain} 是 TCP；</li>
 *   <li>测试桩要能<b>随手改数值</b>（验证字序），不该给产品从站加测试开关。</li>
 * </ul>
 *
 * <p><b>用法</b>（配合 com0com 虚拟串口对 COM5 ↔ COM6）：
 * <pre>
 *   collector\run-rtu-slave.cmd                 # 在 COM5 上起从站，温度 25.6
 *   collector\run-probe.cmd --com COM6          # 从 COM6 去读 —— 四种字序各解一遍
 * </pre>
 *
 * <p><b>为什么默认值是 25.6 而不是 25.5</b>：
 * {@code 25.5f = 0x41CC0000}，低 16 位是 <b>0</b>；
 * 那样 {ABCD@offset0} 和 {ABCD@offset1} 会解出同一个数，<b>字序就分不出来了</b>。
 * {@code 25.6f = 0x41CCCCCD}，高/低字都非零 —— 这正是 CODESYS 那次踩过的坑。
 */
public final class RtuSlaveMain {

    private RtuSlaveMain() {
    }

    public static void main(String[] args) throws Exception {
        String comPort = stringArg(args, "--com", "COM5");
        int baudRate = intArg(args, "--baud", 9600);
        int unitId = intArg(args, "--unit", 1);
        float temperature = floatArg(args, "--temperature", 25.6f);
        int seconds = intArg(args, "--seconds", 180);

        SerialParameters params = new SerialParameters();
        params.setPortName(comPort);
        params.setBaudRate(baudRate);
        params.setDatabits(8);
        params.setStopbits(1);
        params.setParity("none");
        params.setEncoding("rtu");
        params.setEcho(false);

        int bits = Float.floatToIntBits(temperature);
        SimpleRegister highWord = new SimpleRegister((bits >>> 16) & 0xFFFF);
        SimpleRegister lowWord = new SimpleRegister(bits & 0xFFFF);
        SimpleRegister counter = new SimpleRegister(0);

        SimpleProcessImage image = new SimpleProcessImage(unitId);
        image.addRegister(highWord);          // holding 0
        image.addRegister(lowWord);           // holding 1
        image.addRegister(counter);           // holding 2 —— 每秒 +1，证明从站活着
        for (int i = 0; i < 5; i++) {
            image.addRegister(new SimpleRegister(0));   // holding 3..7，让 probe 一读就是 8 个
        }

        ModbusSlave slave = ModbusSlaveFactory.createSerialSlave(params);
        slave.addProcessImage(unitId, image);
        slave.open();

        System.out.printf("RTU 从站已启动：%s @ %d 8N1 · unit=%d%n", comPort, baudRate, unitId);
        System.out.printf("  holding[0..1] = Float32 %s  (0x%08X → hi=0x%04X, lo=0x%04X)%n",
                temperature, bits, (bits >>> 16) & 0xFFFF, bits & 0xFFFF);
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
