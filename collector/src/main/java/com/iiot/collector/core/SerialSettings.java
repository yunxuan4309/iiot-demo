package com.iiot.collector.core;

/**
 * 串口（RTU）参数 —— 现场最容易配错的东西，所以单独建模、单独校验。
 *
 * <p>工业仪表的出厂默认几乎都是 <b>{@code 9600-8-N-1}</b>，但<b>不符合默认的也很多</b>。
 * 现场排查时"一直超时"的前四个嫌疑，就有三个在这里：
 * <ol>
 *   <li>波特率不对</li>
 *   <li>校验位不对（8N1 写成 8E1）</li>
 *   <li>数据位/停止位不对（少见但存在）</li>
 * </ol>
 *
 * <p>{@code rs485Mode} 单独说：<b>普通 USB-485 转换器（FT232 等）不要开</b> ——
 * 它们在硬件上自动做收发方向切换，驱动看到的只是一个普通串口；
 * 开了反而会去调 {@code TIOCSRS485} ioctl，很多转换器不支持。
 * 只有<b>板载原生 485 口</b>才需要开。
 */
public record SerialSettings(int baudRate, int dataBits, int stopBits, String parity, boolean rs485Mode) {

    /** 工业仪表最常见的默认值。 */
    public static final int DEFAULT_BAUD = 9600;

    public SerialSettings {
        if (baudRate <= 0) {
            throw new IllegalArgumentException("baudRate 必须 > 0，实际 " + baudRate);
        }
        if (dataBits < 5 || dataBits > 8) {
            throw new IllegalArgumentException("dataBits 必须是 5~8，实际 " + dataBits);
        }
        if (stopBits < 1 || stopBits > 2) {
            throw new IllegalArgumentException("stopBits 必须是 1 或 2，实际 " + stopBits);
        }
        parity = normalizeParity(parity);
    }

    /** 默认 9600-8-N-1，不开 rs485Mode（USB-485 转换器适用）。 */
    public static SerialSettings defaults() {
        return new SerialSettings(DEFAULT_BAUD, 8, 1, "none", false);
    }

    private static String normalizeParity(String raw) {
        if (raw == null || raw.isBlank()) {
            return "none";
        }
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "none", "n", "no" -> "none";
            case "even", "e" -> "even";
            case "odd", "o" -> "odd";
            case "mark", "m" -> "mark";
            case "space", "s" -> "space";
            default -> throw new IllegalArgumentException(
                    "parity 只能是 none/even/odd/mark/space，实际 " + raw);
        };
    }

    /** 现场记法：{@code 9600-8-N-1}。 */
    public String describe() {
        String p = switch (parity) {
            case "even" -> "E";
            case "odd" -> "O";
            case "mark" -> "M";
            case "space" -> "S";
            default -> "N";
        };
        return baudRate + "-" + dataBits + p + stopBits;
    }
}
