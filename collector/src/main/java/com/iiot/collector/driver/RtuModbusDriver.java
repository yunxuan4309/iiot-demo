package com.iiot.collector.driver;

import com.ghgande.j2mod.modbus.facade.AbstractModbusMaster;
import com.ghgande.j2mod.modbus.facade.ModbusSerialMaster;
import com.ghgande.j2mod.modbus.util.SerialParameters;
import com.iiot.collector.core.DeviceEndpoint;
import com.iiot.collector.core.SerialSettings;

/**
 * Modbus RTU 驱动 —— 真传感器走这条（RS485）。
 *
 * <p>读写逻辑全在 {@link AbstractModbusDriver}，这里只负责"把 {@link SerialSettings}
 * 翻译成 j2mod 的 {@link SerialParameters} 并建连接"。
 *
 * <h2>为什么 RS485 的并发模型不一样</h2>
 * <b>一个 COM 口只能被打开一次。</b> 所以：
 * <ul>
 *   <li>同一条 485 总线上的所有设备（不同 unitId）<b>共用一条连接</b>
 *       —— {@link DeviceEndpoint#transportKey()} 用 COM 口做 key</li>
 *   <li>RS485 是<b>半双工</b>：发的时候不能收，所以请求必须<b>串行</b>
 *       —— {@link AbstractModbusDriver} 的读写方法都是 {@code synchronized}</li>
 * </ul>
 *
 * <h2>关于 rs485Mode</h2>
 * <b>普通 USB-485 转换器（FT232 等）不要开</b>：它们在硬件上自动切换收发方向，
 * 驱动看到的只是普通串口；开了反而会去调 {@code TIOCSRS485} ioctl，很多转换器不支持，
 * 轻则报错、重则打不开串口。只有<b>板载原生 485 口</b>才需要开。
 *
 * <h2>RTU 特有的排查顺序</h2>
 * 现场"一直超时"时，按这个顺序排除（<b>前四项都不是代码问题</b>）：
 * <ol>
 *   <li>A/B 是否接反</li>
 *   <li>是否共地（GND）</li>
 *   <li>波特率 / 校验位是否一致</li>
 *   <li>从站地址（unitId）是否对</li>
 *   <li>最后才是寄存器地址 / 字序</li>
 * </ol>
 */
public final class RtuModbusDriver extends AbstractModbusDriver {

    public RtuModbusDriver(int timeoutMillis) {
        super(timeoutMillis);
    }

    @Override
    protected AbstractModbusMaster open(DeviceEndpoint endpoint) throws Exception {
        SerialSettings serial = endpoint.serial();
        if (serial == null) {
            throw new IllegalStateException("RTU 设备缺少串口参数: " + endpoint.deviceId());
        }

        SerialParameters params = new SerialParameters();
        params.setPortName(endpoint.host());          // "COM3"
        params.setBaudRate(serial.baudRate());
        params.setDatabits(serial.dataBits());
        params.setStopbits(serial.stopBits());
        params.setParity(serial.parity());
        params.setEncoding("rtu");                     // j2mod 的 RTU 编码（含 CRC 与 3.5 字符间隔）
        params.setEcho(false);
        if (serial.rs485Mode()) {
            // 只有板载原生 485 口才走这里；USB-485 转换器保持 false
            params.setRs485Mode(true);
            params.setRs485TxEnableActiveHigh(true);
            params.setRs485DelayBeforeTxMicroseconds(100);
            params.setRs485DelayAfterTxMicroseconds(100);
        }

        ModbusSerialMaster master = new ModbusSerialMaster(params, timeoutMillis);
        master.connect();
        return master;
    }
}
