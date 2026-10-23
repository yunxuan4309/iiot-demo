package com.iiot.collector.core;

import java.util.List;

/**
 * 设备抽象层 —— <b>整个 demo 的技术价值所在</b>（计划书 §3.2）。
 *
 * <p>平台侧只依赖这个接口：真传感器（RTU）与仿真设备（TCP）在它后面长得一模一样，
 * 上层代码不知道谁真谁假。新增 OPC UA 时，只要再写一个实现，上层一行不改。
 *
 * <p>约定：
 * <ul>
 *   <li>{@link #read} 返回的每个点都是 {@link Quality#GOOD}；<b>读失败就抛异常</b>，
 *       不要在这里编造 0 或上一次的值 —— 降级成 STALE/BAD 是采样器的职责
 *       （驱动只管「协议」，采样器管「数据可信度」）</li>
 *   <li>字序、类型、偏移全部来自 {@link PointDef}，驱动不假设任何设备的私有约定</li>
 * </ul>
 */
public interface DeviceDriver extends AutoCloseable {

    /**
     * 读一批点。实现应当按「区域 + 连续范围」合并请求，而不是一个点一帧。
     *
     * @throws DeviceIoException 连不上 / 超时 / 从站返回异常码
     */
    List<PointValue> read(DeviceEndpoint endpoint, List<PointDef> points) throws DeviceIoException;

    /**
     * 写一个点。注意<b>写操作不幂等</b>（坑 4）：超时后重发可能让电机启停两次，
     * 调用方必须在超时后先读回确认，而不是无脑重试。
     */
    void write(DeviceEndpoint endpoint, PointDef point, double value) throws DeviceIoException;

    /** 关闭底层连接。 */
    @Override
    void close();
}
