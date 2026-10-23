package com.iiot.collector.core;

import java.time.Instant;

/**
 * 一次采样里的一个点 —— 采集链路的「最小数据单元」，后面入库就是它。
 *
 * @param deviceId 设备台账里的设备标识
 * @param pointId  点位名
 * @param ts       采样时刻（本机时钟；真实现场要用 NTP 对时，见计划书坑 6）
 * @param value    值；{@code BOOL} 用 0/1 表示，{@code BAD} 时可能是 {@link Double#NaN}
 * @param quality  质量码 —— 平台必须看它，不能只看 value
 * @param unit     工程单位，可空
 */
public record PointValue(String deviceId, String pointId, Instant ts, double value, Quality quality, String unit) {

    public boolean isGood() {
        return quality == Quality.GOOD;
    }

    /** 给控制台用的紧凑表示：good 值正常打印，STALE/BAD 后面带标记。 */
    public String format() {
        String number = switch (quality) {
            case GOOD -> String.format("%.2f", value);
            case STALE -> String.format("%.2f", value) + "(STALE)";
            case BAD -> "--(BAD)";
        };
        return unit == null || unit.isBlank() ? number : number + unit;
    }
}
