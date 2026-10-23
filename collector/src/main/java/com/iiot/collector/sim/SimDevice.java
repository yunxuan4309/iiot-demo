package com.iiot.collector.sim;

import com.ghgande.j2mod.modbus.procimg.SimpleDigitalIn;
import com.ghgande.j2mod.modbus.procimg.SimpleDigitalOut;
import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.iiot.collector.proto.WordOrder;

/**
 * 一台仿真设备的点表 + 进程映像，与计划书 §3.3 的四个点位一一对应：
 *
 * <table border="1">
 *   <caption>点位表</caption>
 *   <tr><th>点位</th><th>类型</th><th>Modbus 区 / 偏移</th><th>功能码</th></tr>
 *   <tr><td>temperature</td><td>Float32（占 2 寄存器）</td><td>holding, offset 0..1</td><td>FC03</td></tr>
 *   <tr><td>runtime_hours</td><td>UInt32（占 2 寄存器）</td><td>holding, offset 2..3</td><td>FC03</td></tr>
 *   <tr><td>motor_running</td><td>BOOL（线圈）</td><td>coil 0</td><td>FC01 / FC05 写</td></tr>
 *   <tr><td>alarm</td><td>BOOL（离散输入）</td><td>input 0</td><td>FC02</td></tr>
 * </table>
 *
 * <p><b>注意</b>：温度故意用 <b>CDAB</b>、运行时长用 <b>ABCD</b>。现实里同一台设备的点表混着两种字序
 * 很常见，所以采集端不能全局假设一种字序，必须<b>按点位配置</b>。
 */
public final class SimDevice {

    /** temperature 的起始寄存器偏移（0 基；对应惯用编号 40001）。 */
    public static final int REG_TEMP = 0;
    /** runtime_hours 的起始寄存器偏移（对应 40003）。 */
    public static final int REG_HOURS = 2;
    /** motor_running 的线圈偏移（对应 00001）。 */
    public static final int COIL_MOTOR = 0;
    /** alarm 的离散输入偏移（对应 10001）。 */
    public static final int INPUT_ALARM = 0;

    /** 报警阈值（℃），与计划书 §16 的 ST 程序块 `xAlarm := rTemp > 80;` 保持一致。 */
    public static final double ALARM_THRESHOLD_C = 80.0;

    private final int unitId;
    private final WordOrder tempOrder;
    private final WordOrder hoursOrder;
    private final TempSignal temp;

    private final SimpleProcessImage image;
    private final SimpleRegister tempHi = new SimpleRegister(0);
    private final SimpleRegister tempLo = new SimpleRegister(0);
    private final SimpleRegister hoursHi = new SimpleRegister(0);
    private final SimpleRegister hoursLo = new SimpleRegister(0);
    private final SimpleDigitalOut coilMotor = new SimpleDigitalOut(false);
    private final SimpleDigitalIn inputAlarm = new SimpleDigitalIn(false);

    /** 设备已运行时长（小时）。开局就是 1234.5 h，像一台跑了一个多月的设备，而不是刚开机。 */
    private double runtimeHours = 1234.5;
    private double temperatureC;

    public SimDevice(int unitId, WordOrder tempOrder, WordOrder hoursOrder, double baseTemperatureC) {
        this.unitId = unitId;
        this.tempOrder = tempOrder;
        this.hoursOrder = hoursOrder;
        this.temp = new TempSignal(baseTemperatureC);
        this.temperatureC = baseTemperatureC;

        this.image = new SimpleProcessImage(unitId);
        image.addRegister(REG_TEMP, tempHi);
        image.addRegister(REG_TEMP + 1, tempLo);
        image.addRegister(REG_HOURS, hoursHi);
        image.addRegister(REG_HOURS + 1, hoursLo);
        image.addDigitalOut(COIL_MOTOR, coilMotor);
        image.addDigitalIn(INPUT_ALARM, inputAlarm);

        writeTemperature();
        writeRuntimeHours();
    }

    /** 推进一个采样周期：更新温度、累计运行时长、刷新报警位。线圈由主站写，这里不动。 */
    public void tick(long intervalMillis) {
        temperatureC = temp.next();
        runtimeHours += intervalMillis / 3_600_000.0;
        writeTemperature();
        writeRuntimeHours();
        inputAlarm.set(temperatureC > ALARM_THRESHOLD_C);
    }

    private void writeTemperature() {
        int[] registers = tempOrder.registersOf((float) temperatureC);
        tempHi.setValue(registers[0]);
        tempLo.setValue(registers[1]);
    }

    private void writeRuntimeHours() {
        int[] registers = hoursOrder.registersOfUInt32((long) runtimeHours);
        hoursHi.setValue(registers[0]);
        hoursLo.setValue(registers[1]);
    }

    public SimpleProcessImage image() {
        return image;
    }

    public int unitId() {
        return unitId;
    }

    public double temperatureC() {
        return temperatureC;
    }

    /** 一行状态，直接给人看。 */
    public String statusLine() {
        return String.format("unit=%-2d temp=%7.2f°C runtime=%10.2f h motor=%-5s alarm=%-5s",
                unitId, temperatureC, runtimeHours, coilMotor.isSet(), inputAlarm.isSet());
    }
}
