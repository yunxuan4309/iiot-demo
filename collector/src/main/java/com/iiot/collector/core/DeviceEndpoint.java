package com.iiot.collector.core;

/**
 * 一台设备的接入信息 —— 真设备与仿真设备都用它表达，驱动层只认这个。
 *
 * <p>两种传输：
 * <ul>
 *   <li><b>TCP_MODBUS</b>：{@code host:port} + unitId（仿真从站、网关转出来的设备）</li>
 *   <li><b>RTU_MODBUS</b>：{@code host} 就是 <b>COM 口名</b>（如 {@code COM3}），
 *       串口参数在 {@link #serial()} 里；{@code port} 无意义（固定 0）</li>
 * </ul>
 *
 * @param deviceId 设备标识（设备台账主键）
 * @param protocol {@link #TCP_MODBUS} 或 {@link #RTU_MODBUS}
 * @param host     TCP 是 IP，RTU 是 COM 口名
 * @param port     TCP 端口；RTU 恒为 0
 * @param unitId   Modbus 从站号（RTU 是设备地址 1~247，TCP 上常见 1）
 * @param serial   串口参数；TCP 时为 {@code null}
 */
public record DeviceEndpoint(String deviceId, String protocol, String host, int port, int unitId,
                             SerialSettings serial) {

    public static final String TCP_MODBUS = "TCP_MODBUS";
    public static final String RTU_MODBUS = "RTU_MODBUS";

    public static DeviceEndpoint tcp(String deviceId, String host, int port, int unitId) {
        return new DeviceEndpoint(deviceId, TCP_MODBUS, host, port, unitId, null);
    }

    public static DeviceEndpoint rtu(String deviceId, String comPort, int unitId, SerialSettings serial) {
        if (serial == null) {
            throw new IllegalArgumentException("RTU 设备必须提供串口参数: " + deviceId);
        }
        return new DeviceEndpoint(deviceId, RTU_MODBUS, comPort, 0, unitId, serial);
    }

    public boolean isRtu() {
        return RTU_MODBUS.equals(protocol);
    }

    /**
     * 同一条物理链路的所有设备必须共用一条连接。
     *
     * <p>RTU：一个 COM 口<b>只能被打开一次</b>，所以总线上所有 unitId 共用一条连接
     * （半双工，请求必须串行 —— 驱动用 {@code synchronized} 保证）。
     * TCP：每台设备各自一条连接（现状）。
     */
    public String transportKey() {
        return isRtu() ? "RTU|" + host : "TCP|" + deviceId;
    }

    @Override
    public String toString() {
        if (isRtu()) {
            return deviceId + "@" + host + "(" + (serial == null ? "?" : serial.describe())
                    + ")/unit=" + unitId;
        }
        return deviceId + "@" + host + ":" + port + "/unit=" + unitId;
    }
}
