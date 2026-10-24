package com.iiot.collector.driver;

import com.iiot.collector.core.DeviceDriver;
import com.iiot.collector.core.DeviceEndpoint;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 驱动注册表 —— <b>按"物理链路"复用连接</b>，而不是按设备。
 *
 * <h2>为什么必须有它</h2>
 * TCP 可以一台设备一条连接；但 <b>RTU 不行</b>：
 * 一个 COM 口<b>只能被打开一次</b>，总线上 10 台设备（unitId 1~10）必须共用同一条串口连接。
 *
 * <p>之前每台设备线程各自 {@code new TcpModbusDriver(...)}，对 TCP 没问题（多设备必须每台独立，
 * 因为 {@code read()} 是 synchronized）；但换成 RTU 就会<b>反复打开同一个 COM 口</b>，
 * 第二台直接失败。
 *
 * <p>所以：<b>同一个 {@link DeviceEndpoint#transportKey()} 只创建一个驱动</b>。
 * <ul>
 *   <li>RTU：key = {@code RTU|COM3} → 总线上所有设备共用</li>
 *   <li>TCP：key = {@code TCP|sim-01} → 每台设备独立（保持原行为）</li>
 * </ul>
 */
public final class DriverRegistry implements AutoCloseable {

    private final int timeoutMillis;
    private final Map<String, DeviceDriver> drivers = new ConcurrentHashMap<>();

    public DriverRegistry(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** 取（或创建）该链路对应的驱动。线程安全。 */
    public DeviceDriver driverFor(DeviceEndpoint endpoint) {
        return drivers.computeIfAbsent(endpoint.transportKey(), key ->
                endpoint.isRtu()
                        ? new RtuModbusDriver(timeoutMillis)
                        : new TcpModbusDriver(timeoutMillis));
    }

    /** 已创建的链路数（诊断用）。 */
    public int linkCount() {
        return drivers.size();
    }

    @Override
    public void close() {
        for (DeviceDriver driver : new ArrayList<>(drivers.values())) {
            driver.close();
        }
        drivers.clear();
    }
}
