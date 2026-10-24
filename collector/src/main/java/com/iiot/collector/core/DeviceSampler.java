package com.iiot.collector.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 采样器 —— 在驱动之上补「数据可信度」这一层，专门对付坑 3 和「字序配错」。
 *
 * <p>分工很清楚：
 * <ul>
 *   <li><b>驱动</b>：协议怎么读。读不到就抛异常，不编值。</li>
 *   <li><b>采样器</b>：这次读失败 / 读到的数不合理时，这一批点应该是什么质量码。</li>
 * </ul>
 *
 * <p>规则：
 * <ol>
 *   <li>读成功且落在量程内 → {@link Quality#GOOD}，并记住「上次好值」</li>
 *   <li>读成功但<b>超出量程</b> → {@link Quality#BAD}（值原样保留，方便排查，但下游不能用）。
 *       典型场景：字序配错，CDAB 读成 ABCD 就是 1e29 这种天文数字</li>
 *   <li>读失败 + 有过好值 → {@link Quality#STALE}，保留上次的值（平台看到质量码就知道是旧的）</li>
 *   <li>读失败 + 从没读到过 → {@link Quality#BAD}，值用 NaN</li>
 * </ol>
 *
 * <p><b>绝不把读失败写成 0</b>：下游看到「0℃」会当成真温度，比没有数据危险得多。
 */
public final class DeviceSampler {

    private final DeviceConfig config;
    private final DeviceDriver driver;
    private final Map<String, PointValue> lastKnown = new HashMap<>();
    private final Map<String, PointDef> pointDefs = new HashMap<>();

    private String lastError;
    private String lastRangeError;
    private Instant lastGoodAt;

    public DeviceSampler(DeviceConfig config, DeviceDriver driver) {
        this.config = config;
        this.driver = driver;
        for (PointDef point : config.points()) {
            pointDefs.put(point.id(), point);
        }
    }

    /** 采一轮。**不会抛异常** —— 失败会体现为质量码。 */
    public synchronized List<PointValue> poll() {
        try {
            List<PointValue> raw = driver.read(config.endpoint(), config.points());
            lastError = null;
            lastRangeError = null;

            List<PointValue> checked = new ArrayList<>(raw.size());
            for (PointValue value : raw) {
                checked.add(checkRange(value));
            }
            for (PointValue value : checked) {
                if (value.isGood()) {
                    lastKnown.put(value.pointId(), value);
                }
            }
            lastGoodAt = Instant.now();
            return checked;
        } catch (DeviceIoException e) {
            return degrade(e.getMessage());
        }
    }

    /**
     * 量程校验 —— 「字序配错」的最后一道防线。
     *
     * <p>驱动不知道物理意义（它只负责按配置解出数），所以校验放在这里：
     * 一个温度点配了 [-40, 120]，读出来 1e29 就一定是解错了，判 BAD 而不是照单全收。
     */
    private PointValue checkRange(PointValue value) {
        if (!value.isGood()) {
            return value;
        }
        PointDef def = pointDefs.get(value.pointId());
        if (def == null || !def.hasRange()) {
            return value;
        }
        if (def.inRange(value.value())) {
            return value;
        }
        if (lastRangeError == null) {
            lastRangeError = value.pointId() + "=" + value.value() + " 超出量程 " + def.rangeText()
                    + "（常见原因：点表里的 order 配错了）";
        }
        return new PointValue(value.deviceId(), value.pointId(), value.ts(), value.value(), Quality.BAD, value.unit());
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

    /** 上一轮的通信错误消息；读成功时为 {@code null}。 */
    public String lastError() {
        return lastError;
    }

    /** 上一轮的量程错误消息（读到数但不合理）；没有则为 {@code null}。 */
    public String lastRangeError() {
        return lastRangeError;
    }

    /** 上一次读成功的时刻（用来算「这个 STALE 值到底有多旧」）。 */
    public Instant lastGoodAt() {
        return lastGoodAt;
    }
}
