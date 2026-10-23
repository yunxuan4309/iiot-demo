package com.iiot.collector.core;

/**
 * 一台设备的接入信息 —— 真设备与仿真设备都用它表达，驱动层只认这个。
 *
 * @param deviceId 设备标识（设备台账主键）
 * @param protocol 协议名：{@code TCP_MODBUS} / {@code RTU_MODBUS}（RTU 待实现）
 * @param host     地址：TCP 是 IP，RTU 将来是 COM 口
 * @param port     端口（RTU 忽略）
 * @param unitId   Modbus 从站号（RTU 是设备地址，TCP 上常见 1）
 */
public record DeviceEndpoint(String deviceId, String protocol, String host, int port, int unitId) {

    public static final String TCP_MODBUS = "TCP_MODBUS";

    public static DeviceEndpoint tcp(String deviceId, String host, int port, int unitId) {
        return new DeviceEndpoint(deviceId, TCP_MODBUS, host, port, unitId);
    }

    @Override
    public String toString() {
        return deviceId + "@" + host + ":" + port + "/unit=" + unitId;
    }
}
