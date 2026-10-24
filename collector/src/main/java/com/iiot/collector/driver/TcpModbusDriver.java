package com.iiot.collector.driver;

import com.ghgande.j2mod.modbus.facade.AbstractModbusMaster;
import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.iiot.collector.core.DeviceEndpoint;

/**
 * Modbus TCP 驱动 —— 仿真从站、网关转出来的设备都走它。
 *
 * <p>读写逻辑全在 {@link AbstractModbusDriver}，这里只负责"建一条 TCP 连接"。
 */
public final class TcpModbusDriver extends AbstractModbusDriver {

    public TcpModbusDriver(int timeoutMillis) {
        super(timeoutMillis);
    }

    @Override
    protected AbstractModbusMaster open(DeviceEndpoint endpoint) throws Exception {
        ModbusTCPMaster master = new ModbusTCPMaster(
                endpoint.host(), endpoint.port(), timeoutMillis, false);
        master.connect();
        return master;
    }
}
