package com.iiot.collector.driver;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.facade.AbstractModbusMaster;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modbus 驱动的共同部分 —— <b>TCP 与 RTU 只有"怎么建连接"不同，读写逻辑完全一样</b>。
 *
 * <p>j2mod 的 {@code ModbusTCPMaster} 和 {@code ModbusSerialMaster} 都继承
 * {@code AbstractModbusMaster}，读寄存器/读线圈/写线圈/写寄存器的方法<b>全在父类上</b>。
 * 所以把协议逻辑抽到这里，子类只实现 {@link #open(DeviceEndpoint)} —— 这就是
 * 「新增一种传输方式只写一个方法」的由来。
 *
 * <h2>三条实现上的讲究（与传输方式无关）</h2>
 * <ol>
 *   <li><b>按区域合并读</b>：同一次采样里，把 holding 区的点合成一帧（min..max 连续范围），
 *       而不是一个点一帧。offset 是 0/2 时就只读 4 个寄存器，而不是 2 帧。</li>
 *   <li><b>不猜字序</b>：每个点用自己的 {@link PointDef#wordOrder()} 解码。
 *       同一台设备里温度 CDAB、运行时长 ABCD 是允许的。</li>
 *   <li><b>不编造值</b>：读失败就抛 {@link DeviceIoException}，把连接标记为失效（下次重连）；
 *       「算 STALE 还是 BAD」是采样器的职责。</li>
 * </ol>
 *
 * <h2>线程安全</h2>
 * 所有公开方法都是 {@code synchronized} —— 对 RTU 尤其重要：<b>RS485 是半双工</b>，
 * 同一条总线上的多个从站必须串行访问，不能并发。
 */
public abstract class AbstractModbusDriver implements DeviceDriver {

    protected final int timeoutMillis;
    /** 一条链路一条连接。RTU 的 key 是 COM 口（总线共享），TCP 的是设备。 */
    private final Map<String, AbstractModbusMaster> connections = new ConcurrentHashMap<>();
    /** 记住每个链路属于哪个 endpoint（打印用）。 */
    private final Map<String, DeviceEndpoint> endpoints = new ConcurrentHashMap<>();

    protected AbstractModbusDriver(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** 建立（并连上）一条链路。子类只写这个。 */
    protected abstract AbstractModbusMaster open(DeviceEndpoint endpoint) throws Exception;

    @Override
    public synchronized List<PointValue> read(DeviceEndpoint endpoint, List<PointDef> points)
            throws DeviceIoException {
        AbstractModbusMaster master = connection(endpoint);
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

        // 按区域分组读会打乱顺序，还原成点表顺序，避免平台侧按位置取值时错位
        Map<String, Integer> position = new HashMap<>(points.size() * 2);
        for (int i = 0; i < points.size(); i++) {
            position.put(points.get(i).id(), i);
        }
        result.sort(Comparator.comparingInt(v -> position.getOrDefault(v.pointId(), Integer.MAX_VALUE)));
        return result;
    }

    private void readArea(AbstractModbusMaster master, DeviceEndpoint endpoint, PointDef.Area area,
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
                    sink.add(new PointValue(endpoint.deviceId(), point.id(), ts, bit ? 1 : 0,
                            Quality.GOOD, point.unit()));
                }
            }
        }
    }

    private PointValue decodeRegisterPoint(DeviceEndpoint endpoint, PointDef point, Instant ts,
                                           int[] values, int index) {
        // 1) 先按"寄存器里的原始位模式"解出 raw
        //    INT16 必须按补码还原成负数 —— 负温度（-5.3℃）存的是 -53，直接当无符号会读成 65483
        double raw = switch (point.type()) {
            case BOOL -> values[index] != 0 ? 1 : 0;
            case INT16 -> (short) values[index];
            case UINT16 -> values[index] & 0xFFFF;
            case FLOAT32 -> point.wordOrder().floatOf(values[index], values[index + 1]);
            case UINT32 -> point.wordOrder().uint32Of(values[index], values[index + 1]);
        };
        // 2) 再按点表的 scale 换算成工程值（大多数真实仪表的 253 = 25.3℃ 就走这一步）
        double value = point.type().isBoolean() || point.scale() == 1.0 ? raw : raw * point.scale();
        // 字序错了会解出 NaN / Infinity —— 值本身不合法，直接判 BAD
        Quality quality = point.type().isBoolean() || Double.isFinite(value) ? Quality.GOOD : Quality.BAD;
        return new PointValue(endpoint.deviceId(), point.id(), ts, value, quality, point.unit());
    }

    @Override
    public synchronized void write(DeviceEndpoint endpoint, PointDef point, double value)
            throws DeviceIoException {
        if (!point.isWritable()) {
            throw new DeviceIoException(point.id() + " 所在区域（" + point.area() + "）不支持写");
        }
        AbstractModbusMaster master = connection(endpoint);
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

    private AbstractModbusMaster connection(DeviceEndpoint endpoint) throws DeviceIoException {
        String key = endpoint.transportKey();
        AbstractModbusMaster existing = connections.get(key);
        if (existing != null) {
            if (existing.isConnected()) {
                return existing;
            }
            invalidate(endpoint);
        }
        try {
            AbstractModbusMaster master = open(endpoint);
            connections.put(key, master);
            endpoints.put(key, endpoint);
            return master;
        } catch (Exception e) {
            throw new DeviceIoException("连接 " + endpoint + " 失败: " + e.getMessage(), e);
        }
    }

    private void invalidate(DeviceEndpoint endpoint) {
        String key = endpoint.transportKey();
        AbstractModbusMaster master = connections.remove(key);
        endpoints.remove(key);
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
        for (String key : new ArrayList<>(connections.keySet())) {
            AbstractModbusMaster master = connections.remove(key);
            endpoints.remove(key);
            if (master != null) {
                try {
                    master.disconnect();
                } catch (RuntimeException ignored) {
                    // 关闭阶段的异常不值得打断收尾
                }
            }
        }
    }
}
