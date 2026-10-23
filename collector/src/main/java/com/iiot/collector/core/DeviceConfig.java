package com.iiot.collector.core;

import java.util.List;

/**
 * 一台设备的完整配置：接入信息 + 点表 + 采样周期。
 *
 * @param endpoint     接入信息
 * @param points       点表（字序在这里，不在驱动里）
 * @param periodMillis 该设备的采样周期（毫秒）—— 现场每台设备的量程/刷新率不同，周期应当逐设备可配
 */
public record DeviceConfig(DeviceEndpoint endpoint, List<PointDef> points, long periodMillis) {

    public String deviceId() {
        return endpoint.deviceId();
    }

    /** 校验整台设备的配置，返回第一个问题；没问题返回 {@code null}。 */
    public String validate() {
        if (endpoint == null) {
            return "缺少 endpoint";
        }
        if (points == null || points.isEmpty()) {
            return "点表为空";
        }
        if (periodMillis <= 0) {
            return "periodMillis 必须 > 0";
        }
        for (PointDef point : points) {
            String problem = point.validate();
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }
}
