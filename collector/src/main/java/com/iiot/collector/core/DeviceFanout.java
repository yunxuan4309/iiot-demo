package com.iiot.collector.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备扇出 —— 把「一台设备的点表模板」复制成 N 台虚拟设备。
 *
 * <p>为什么需要它：100 台设备的点表不可能手写（100 × 4 个点 × 6 个配置项 = 2400 行）。
 * 现场的真实做法是「一台表配好，其余照抄，只改地址」；这里把"只改地址"这一步参数化。
 *
 * <p><b>关键约定</b>：仿真从站用 {@code SimSlaveMain --devices N} 在<b>同一个端口</b>上
 * 挂 N 个从站号（unitId 1..N）—— 这正是 **RS485 网关后挂多台表**的形态：
 * <pre>
 *   host/port 相同，unitId 不同  ⇒  一台网关 + N 台表
 * </pre>
 * 所以扇出时<b>只改 unitId 和 deviceId</b>，host/port/点表/周期全部继承模板。
 *
 * @see DeviceConfig
 */
public final class DeviceFanout {

    private DeviceFanout() {
    }

    /**
     * 把模板复制成 {@code count} 台设备。
     *
     * @param template 模板设备（它的 unitId 被忽略，从 1 开始重新编号）
     * @param count    总台数（>= 1）
     */
    public static List<DeviceConfig> expand(DeviceConfig template, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count 必须 >= 1");
        }
        String prefix = baseName(template.deviceId());
        // ⚠️ 必须与台数无关：曾经写成 "<=99 用 %02d，否则 %03d"，
        // 结果 --fanout 10 生成 sim-01..sim-10、--fanout 100 生成 sim-001..sim-100，
        // 同一个库里的设备 ID 出现两套格式（实测库里变成 110 台）。
        // %02d 是最小宽度，100 会自然变成 "100" ⇒ sim-01..sim-100 始终一致。
        String pattern = prefix + "-%02d";

        List<DeviceConfig> devices = new ArrayList<>(count);
        for (int unitId = 1; unitId <= count; unitId++) {
            String deviceId = String.format(pattern, unitId);
            DeviceEndpoint endpoint = new DeviceEndpoint(
                    deviceId,
                    template.endpoint().protocol(),
                    template.endpoint().host(),
                    template.endpoint().port(),
                    unitId,
                    // RTU 的串口参数要一起复制：扇出的是"同一条总线上的不同从站地址"
                    template.endpoint().serial());
            devices.add(new DeviceConfig(endpoint, template.points(), template.periodMillis()));
        }
        return devices;
    }

    /** {@code sim-01} → {@code sim}；{@code plc} → {@code plc}。 */
    private static String baseName(String deviceId) {
        String stripped = deviceId.replaceAll("-\\d+$", "");
        return stripped.isEmpty() ? "dev" : stripped;
    }
}
