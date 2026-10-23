package com.iiot.collector.proto;

/**
 * 寄存器字序 —— 一个 32 位值占两个 16 位寄存器时，字节怎么落到寄存器里。
 *
 * <p>Modbus 只规定「寄存器是 16 位」，<b>不规定</b> 32 位值怎么拆。同一个 Float32，按不同约定
 * 读出来可能是 62.13℃，也可能是 1e38 这种天文数字或者 0。国内仪表 / 变送器常见
 * <b>CDAB（两个寄存器互换）</b>，读出来是天文数字基本就是它。
 *
 * <pre>
 *   语义字节  b0 b1 b2 b3      （b0 为最高字节，即大端 / 网络序）
 *
 *   ABCD  →  reg0 = b0b1, reg1 = b2b3    标准大端
 *   CDAB  →  reg0 = b2b3, reg1 = b0b1    两个字交换（最常见的坑）
 *   BADC  →  reg0 = b1b0, reg1 = b3b2    字内字节交换
 *   DCBA  →  reg0 = b3b2, reg1 = b1b0    全反（小端）
 * </pre>
 */
public enum WordOrder {

    ABCD,
    CDAB,
    BADC,
    DCBA;

    public static WordOrder parse(String text) {
        for (WordOrder order : values()) {
            if (order.name().equalsIgnoreCase(text)) {
                return order;
            }
        }
        throw new IllegalArgumentException("未知字序: " + text + "（可选 ABCD / CDAB / BADC / DCBA）");
    }

    /**
     * 线上两个寄存器 → 语义上的 4 个字节（下标 0 是最高字节）。
     *
     * @param reg0 第一个寄存器的值（0..65535）
     * @param reg1 第二个寄存器的值（0..65535）
     */
    public byte[] bytesOf(int reg0, int reg1) {
        int hi = reg0 & 0xFFFF;
        int lo = reg1 & 0xFFFF;
        byte b0 = (byte) (hi >>> 8);
        byte b1 = (byte) hi;
        byte b2 = (byte) (lo >>> 8);
        byte b3 = (byte) lo;
        return switch (this) {
            case ABCD -> new byte[] {b0, b1, b2, b3};
            case CDAB -> new byte[] {b2, b3, b0, b1};
            case BADC -> new byte[] {b1, b0, b3, b2};
            case DCBA -> new byte[] {b3, b2, b1, b0};
        };
    }

    /** 按本字序把两个寄存器解析成 Float32。 */
    public float floatOf(int reg0, int reg1) {
        byte[] b = bytesOf(reg0, reg1);
        int bits = (b[0] & 0xFF) << 24
                | (b[1] & 0xFF) << 16
                | (b[2] & 0xFF) << 8
                | (b[3] & 0xFF);
        return Float.intBitsToFloat(bits);
    }

    /** 按本字序把两个寄存器解析成 UInt32。 */
    public long uint32Of(int reg0, int reg1) {
        byte[] b = bytesOf(reg0, reg1);
        return ((long) (b[0] & 0xFF) << 24)
                | ((long) (b[1] & 0xFF) << 16)
                | ((long) (b[2] & 0xFF) << 8)
                | (b[3] & 0xFF);
    }

    /** Float32 → 线上两个寄存器（返回值在 0..65535，可直接塞进 SimpleRegister）。 */
    public int[] registersOf(float value) {
        int bits = Float.floatToIntBits(value);
        int b0 = (bits >>> 24) & 0xFF;
        int b1 = (bits >>> 16) & 0xFF;
        int b2 = (bits >>> 8) & 0xFF;
        int b3 = bits & 0xFF;
        return switch (this) {
            case ABCD -> new int[] {b0 << 8 | b1, b2 << 8 | b3};
            case CDAB -> new int[] {b2 << 8 | b3, b0 << 8 | b1};
            case BADC -> new int[] {b1 << 8 | b0, b3 << 8 | b2};
            case DCBA -> new int[] {b3 << 8 | b2, b1 << 8 | b0};
        };
    }

    /** UInt32 → 线上两个寄存器（返回值在 0..65535）。 */
    public int[] registersOfUInt32(long value) {
        long v = value & 0xFFFFFFFFL;
        int b0 = (int) ((v >>> 24) & 0xFF);
        int b1 = (int) ((v >>> 16) & 0xFF);
        int b2 = (int) ((v >>> 8) & 0xFF);
        int b3 = (int) (v & 0xFF);
        return switch (this) {
            case ABCD -> new int[] {b0 << 8 | b1, b2 << 8 | b3};
            case CDAB -> new int[] {b2 << 8 | b3, b0 << 8 | b1};
            case BADC -> new int[] {b1 << 8 | b0, b3 << 8 | b2};
            case DCBA -> new int[] {b3 << 8 | b2, b1 << 8 | b0};
        };
    }
}
