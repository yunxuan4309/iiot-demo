package com.iiot.collector.core;

import java.util.List;

/**
 * 遥测存储 —— <b>把「存哪里」封在接口后面，换库只改一个实现类</b>（计划书 §3.4）。
 *
 * <p>为什么要有这层：时序库的选择在工业现场几乎一定会变（客户已有 InfluxDB / TDengine /
 * PI System，或者干脆只给一个 Kafka）。业务侧只应该知道「我要写一批点」，
 * 不应该知道 SQL 长什么样。
 *
 * <p>约定：
 * <ul>
 *   <li>{@link #write} 接收<b>一批</b>点，实现方负责批量提交 —— 逐点写会把吞吐打死</li>
 *   <li>实现必须<b>线程安全</b>：每台设备一条虚拟线程，会并发调用</li>
 *   <li>质量码必须一起落库（{@link PointValue#quality()}）——
 *       只存值不存质量码，等于把"这个数可不可信"的信息丢了</li>
 * </ul>
 */
public interface TelemetryRepository extends AutoCloseable {

    /** 写入一批点。实现方负责批量与并发安全。 */
    void write(List<PointValue> batch);

    /** 给人看的实现描述，用于启动日志。 */
    default String describe() {
        return getClass().getSimpleName();
    }

    /** 关闭连接/刷盘。默认无操作。 */
    @Override
    default void close() {
    }
}
