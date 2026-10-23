package com.iiot.collector.driver;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.util.BitVector;
import com.iiot.collector.core.DeviceDriver;
import com.iiot.collector.core.DeviceEndpoint;
import com.iiot.collector.core.DeviceIoException;
import com.iiot.collector.core.PointDef;
import com.iiot.collector.core.PointValue;
import com.iiot.collector.core.Quality;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modbus TCP 驱动 —— 仿真设备与（将来网关转出来的）真设备都走它。
 *
 * <p>三条实现上的讲究：
 * <ol>
 *   <li><b>按区域合并读</b>：同一次采样里，把 holding 区的点合成一帧（min..max 连续范围），
 *       而不是一个点一帧。点表里 offset 是 0/2 时就只读 4 个寄存器，而不是 2 帧 × 2 寄存器。</li>
 *   <li><b>不猜字序</b>：每个点用自己的 {@link PointDef#wordOrder()} 解码。
 *       同一台设备里温度 CDAB、运行时长 ABCD 是允许的，驱动不该有全局假设。</li>
 *   <li><b>不编造值</b>：读失败就抛 {@link DeviceIoException}，把连接标记为失效（下次重连）；
 *       至于「到底算 STALE 还是 BAD」，交给采样器决定。</li>
 * </ol>
 */
public final class TcpModbusDriver implements DeviceDriver {

    private final int timeoutMillis;
    /** 一个 endpoint 一条连接。多设备时这里就是"少量连接 × 多从站号"的模型。 */
    private final Map<DeviceEndpoint, ModbusTCPMaster> connections = new ConcurrentHashMap<>();

    public TcpModbusDriver(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public synchronized List<PointValue> read(DeviceEndpoint endpoint, List<PointDef> points) throws DeviceIoException {
        ModbusTCPMaster master = connection(endpoint);
        Instant ts = Instant.now();
        List<PointValue> result = new ArrayList<>(points.size());

        Map<PointDef.Area, List<PointDef>> byArea = new EnumMap<>(PointDef.Area.class);
        for (PointDef point : points) {
            byArea.computeIfAbsent(point.area(), key -> new ArrayList<>()).add(point);
        }

        try {
            for (Map.Entry<PointDef.Area, List<PointDef>> entry : byArea.entrySet()) {
                readArea(master, endpoint, entry.getKey(), entry.getValue(), ts, result);
            }
        } catch (ModbusException | RuntimeException e) {
            invalidate(endpoint);
            throw new DeviceIoException("从 " + endpoint + " 读取失败: " + e.getMessage(), e);
        }

        // 按区域分组读会打乱顺序，这里还原成点表里的顺序，避免平台侧按位置取值时错位
        Map<String, Integer> position = new java.util.HashMap<>(points.size() * 2);
        for (int i = 0; i < points.size(); i++) {
            position.put(points.get(i).id(), i);
        }
        result.sort(java.util.Comparator.comparingInt(value -> position.getOrDefault(value.pointId(), Integer.MAX_VALUE)));
        return result;
    }

    private void readArea(ModbusTCPMaster master, DeviceEndpoint endpoint, PointDef.Area area,
                          List<PointDef> group, Instant ts, List<PointValue> sink) throws ModbusException {
        int firstOffset = group.stream().mapToInt(PointDef::offset).min().orElseThrow();
        int endOffset = group.stream().mapToInt(p -> p.offset() + p.registerCount()).max().orElseThrow();
        int count = endOffset - firstOffset;

        switch (area) {
            case HOLDING_REGISTER, INPUT_REGISTER -> {
                InputRegister[] registers = area == PointDef.Area.HOLDING_REGISTER
                        ? master.readMultipleRegisters(endpoint.unitId(), firstOffset, count)
                        : master.readInputRegisters(endpoint.unitId(), firstOffset, count);

                int[] values = new int[count];
                for (int i = 0; i < count; i++) {
                    values[i] = registers[i].getValue() & 0xFFFF;
                }
                for (PointDef point : group) {
                    sink.add(decodeRegisterPoint(endpoint, point, ts, values, point.offset() - firstOffset));
                }
            }
            case COIL, DISCRETE_INPUT -> {
                BitVector bits = area == PointDef.Area.COIL
                        ? master.readCoils(endpoint.unitId(), firstOffset, count)
                        : master.readInputDiscretes(endpoint.unitId(), firstOffset, count);
                for (PointDef point : group) {
                    boolean bit = bits.getBit(point.offset() - firstOffset);
                    sink.add(new PointValue(endpoint.deviceId(), point.id(), ts, bit ? 1 : 0, Quality.GOOD, point.unit()));
                }
            }
        }
    }

    private PointValue decodeRegisterPoint(DeviceEndpoint endpoint, PointDef point, Instant ts, int[] values, int index) {
        double value = switch (point.type()) {
            case BOOL -> values[index] != 0 ? 1 : 0;
            case FLOAT32 -> point.wordOrder().floatOf(values[index], values[index + 1]);
            case UINT32 -> point.wordOrder().uint32Of(values[index], values[index + 1]);
        };

        // 字序错了会解出 NaN / Infinity —— 值本身不合法，直接判 BAD，别让它流到平台
        Quality quality = point.type().isBoolean() || Double.isFinite(value) ? Quality.GOOD : Quality.BAD;
        return new PointValue(endpoint.deviceId(), point.id(), ts, value, quality, point.unit());
    }

    @Override
    public synchronized void write(DeviceEndpoint endpoint, PointDef point, double value) throws DeviceIoException {
        if (!point.isWritable()) {
            throw new DeviceIoException(point.id() + " 所在区域（" + point.area() + "）不支持写");
        }
        ModbusTCPMaster master = connection(endpoint);
        try {
            if (point.area() == PointDef.Area.COIL) {
                master.writeCoil(endpoint.unitId(), point.offset(), value != 0);
            } else {
                master.writeSingleRegister(endpoint.unitId(), point.offset(),
                        new SimpleRegister((int) Math.round(value)));
            }
        } catch (ModbusException | RuntimeException e) {
            invalidate(endpoint);
            throw new DeviceIoException("写 " + point.id() + " 失败（写不幂等，重发前先读回确认）: "
                    + e.getMessage(), e);
        }
    }

    private ModbusTCPMaster connection(DeviceEndpoint endpoint) throws DeviceIoException {
        ModbusTCPMaster existing = connections.get(endpoint);
        if (existing != null) {
            if (existing.isConnected()) {
                return existing;
            }
            invalidate(endpoint);
        }
        try {
            ModbusTCPMaster master = new ModbusTCPMaster(endpoint.host(), endpoint.port(), timeoutMillis, false);
            master.connect();
            connections.put(endpoint, master);
            return master;
        } catch (Exception e) {
            throw new DeviceIoException("连接 " + endpoint.host() + ":" + endpoint.port()
                    + " 失败: " + e.getMessage(), e);
        }
    }

    private void invalidate(DeviceEndpoint endpoint) {
        ModbusTCPMaster master = connections.remove(endpoint);
        if (master != null) {
            try {
                master.disconnect();
            } catch (RuntimeException ignored) {
                // 连接已经坏了，disconnect 失败没什么可做的
            }
        }
    }

    @Override
    public synchronized void close() {
        for (DeviceEndpoint endpoint : new ArrayList<>(connections.keySet())) {
            invalidate(endpoint);
        }
    }
}
