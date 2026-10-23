package com.iiot.collector.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 采样器 —— 在驱动之上补「数据可信度」这一层，专门对付坑 3。
 *
 * <p>分工很清楚：
 * <ul>
 *   <li><b>驱动</b>：协议怎么读。读不到就抛异常，不编值。</li>
 *   <li><b>采样器</b>：这次读失败时，这一批点应该是什么质量码。</li>
 * </ul>
 *
 * <p>规则：
 * <ol>
 *   <li>读成功 → 全部 GOOD，并记住「上次好值」</li>
 *   <li>读失败 + 有过好值 → <b>STALE</b>，保留上次的值（平台看到质量码就知道是旧的）</li>
 *   <li>读失败 + 从没读到过 → <b>BAD</b>，值用 NaN</li>
 * </ol>
 *
 * <p><b>绝不把读失败写成 0</b>：下游看到「0℃」会当成真温度，比没有数据危险得多。
 */
public final class DeviceSampler {

    private final DeviceConfig config;
    private final DeviceDriver driver;
    private final Map<String, PointValue> lastKnown = new HashMap<>();

    private String lastError;
    private Instant lastGoodAt;

    public DeviceSampler(DeviceConfig config, DeviceDriver driver) {
        this.config = config;
        this.driver = driver;
    }

    /** 采一轮。**不会抛异常** —— 失败会体现为质量码。 */
    public synchronized List<PointValue> poll() {
        try {
            List<PointValue> values = driver.read(config.endpoint(), config.points());
            lastError = null;
            lastGoodAt = Instant.now();
            for (PointValue value : values) {
                if (value.isGood()) {
                    lastKnown.put(value.pointId(), value);
                }
            }
            return values;
        } catch (DeviceIoException e) {
            return degrade(e.getMessage());
        }
    }

    private List<PointValue> degrade(String error) {
        lastError = error;
        Instant now = Instant.now();
        List<PointValue> values = new ArrayList<>(config.points().size());
        for (PointDef point : config.points()) {
            PointValue previous = lastKnown.get(point.id());
            values.add(previous == null
                    ? new PointValue(config.deviceId(), point.id(), now, Double.NaN, Quality.BAD, point.unit())
                    : new PointValue(config.deviceId(), point.id(), now, previous.value(), Quality.STALE, point.unit()));
        }
        return values;
    }

    public DeviceConfig config() {
        return config;
    }

    /** 上一轮的错误消息；读成功时为 {@code null}。 */
    public String lastError() {
        return lastError;
    }

    /** 上一次读成功的时刻（用来算「这个 STALE 值到底有多旧」）。 */
    public Instant lastGoodAt() {
        return lastGoodAt;
    }
}
