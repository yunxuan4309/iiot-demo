package com.iiot.platform.web;

import com.iiot.platform.tdengine.TdengineClient;
import com.iiot.platform.tdengine.TdengineClient.TdengineException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 监测平台的只读接口。
 *
 * <p>三个接口对应页面的三块内容：
 * <ul>
 *   <li>{@code /api/overview} 顶部指标卡（总点数、设备数、质量分布）</li>
 *   <li>{@code /api/latest} 左侧设备列表的"当前值"表格</li>
 *   <li>{@code /api/series} 右侧时序曲线</li>
 * </ul>
 *
 * <p><b>设计取舍</b>：这里<b>不做聚合下推优化</b>（本来可以按窗口 avg 降采样）。
 * demo 的数据量（100 台 × 4 点 × 1Hz）在 10 分钟窗口里只有 24 万行，
 * 直接 `order by ts` 拿原始点给 ECharts 完全够用 —— <b>先让链路跑通，再谈优化</b>。
 */
@RestController
@RequestMapping("/api")
public class TelemetryController {

    private final TdengineClient td;

    /**
     * 数据"过期"阈值（秒）：最新点的时刻距今超过这个值，就认为数据不再新鲜。
     *
     * <p><b>为什么必须做这个判断</b>：库里存的是"写进去那一刻的事实"——
     * 采集端停了、网断了、进程被杀了，库里最后一条**仍然是 GOOD**。
     * 如果页面照原样显示，就变成"**一个数字永远不动，但看起来一切正常**"。
     * 现场的坑就是这样：值班员以为系统正常，其实采集早挂了。
     *
     * <p>默认 15 秒（采样 1 秒的 15 倍）—— 给网络抖动和短暂重连留余量。
     */
    private final long staleAfterSeconds;

    public TelemetryController(TdengineClient td,
                               @Value("${iiot.stale.after-seconds:15}") long staleAfterSeconds) {
        this.td = td;
        this.staleAfterSeconds = staleAfterSeconds;
    }

    /** 顶部指标：总点数、设备数、子表数、质量分布、最新时间。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        Long total = asLong(td.queryScalar("select count(*) as total from " + td.database() + ".telemetry"));
        result.put("totalPoints", total == null ? 0 : total);
        result.put("latestTs", td.queryScalar("select last(ts) as latest from " + td.database() + ".telemetry"));
        result.put("childTables", asLong(td.queryScalar(
                "select count(*) as n from information_schema.ins_tables where db_name='" + td.database() + "'")));

        List<Map<String, Object>> devices =
                td.query("select distinct device from " + td.database() + ".telemetry");
        result.put("deviceCount", devices.size());
        List<String> names = new ArrayList<>(devices.size());
        for (Map<String, Object> row : devices) {
            names.add(String.valueOf(row.get("device")));
        }
        names.sort(String::compareTo);
        result.put("devices", names);

        Map<String, Long> quality = new LinkedHashMap<>();
        for (Map<String, Object> row : td.query(
                "select quality, count(*) as n from " + td.database() + ".telemetry partition by quality")) {
            quality.put(String.valueOf(row.get("quality")), asLong(row.get("n")));
        }
        result.put("quality", quality);

        Long good = quality.getOrDefault("GOOD", 0L);
        result.put("goodRate", total == null || total == 0 ? null
                : Math.round(10000.0 * good / total) / 100.0);

        // ★ 有多少台设备的"最新数据"已经过期（采集端停了 / 网断了）
        int staleDevices = 0;
        for (Map<String, Object> row : td.query(
                "select device, last(ts) as ts from " + td.database() + ".telemetry partition by device")) {
            Long age = ageSeconds(row.get("ts"));
            if (age != null && age > staleAfterSeconds) {
                staleDevices++;
            }
        }
        result.put("staleDevices", staleDevices);
        result.put("staleAfterSeconds", staleAfterSeconds);
        return result;
    }

    /**
     * 每台设备每个点位的"最新值"。
     *
     * <p>用 TDengine 的 {@code last()} + {@code partition by} 在库内算好，
     * 而不是把明细全拉到应用层再取最后一条 —— 数据量一大后者会直接拖死平台。
     */
    @GetMapping("/latest")
    public List<Map<String, Object>> latest() {
        List<Map<String, Object>> rows = td.query(
                "select device, point, last(val) as val, last(ts) as ts, last(quality) as quality"
                        + " from " + td.database() + ".telemetry partition by device, point");

        Map<String, Map<String, Object>> byDevice = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            String device = String.valueOf(row.get("device"));
            String point = String.valueOf(row.get("point"));
            Map<String, Object> deviceNode = byDevice.computeIfAbsent(device, key -> {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("device", key);
                node.put("points", new LinkedHashMap<String, Object>());
                return node;
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> points = (Map<String, Object>) deviceNode.get("points");
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("val", row.get("val"));
            p.put("ts", row.get("ts"));
            p.put("quality", row.get("quality"));
            // ★ 新鲜度：库里的 quality 是"写进去那一刻"的质量码，
            //   它不会因为采集端停了而变 STALE —— 所以要在查询时重新算"多久没更新了"。
            Long age = ageSeconds(row.get("ts"));
            p.put("ageSeconds", age);
            boolean stale = age != null && age > staleAfterSeconds;
            p.put("stale", stale);
            points.put(point, p);
            if (stale) {
                deviceNode.put("stale", Boolean.TRUE);
            }
        }
        // 每个设备默认 stale=false
        for (Map<String, Object> node : byDevice.values()) {
            node.putIfAbsent("stale", Boolean.FALSE);
        }
        return new ArrayList<>(byDevice.values());
    }

    /** 把 TDengine 返回的 UTC ISO 时间戳换算成"距今多少秒"。解析不了返回 {@code null}。 */
    private static Long ageSeconds(Object ts) {
        if (ts == null) {
            return null;
        }
        try {
            java.time.Instant instant = java.time.Instant.parse(String.valueOf(ts));
            return Math.max(0, java.time.Duration.between(instant, java.time.Instant.now()).getSeconds());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 某个设备某个点位的时序（最近 N 分钟）。 */
    @GetMapping("/series")
    public Map<String, Object> series(@RequestParam("device") String device,
                                      @RequestParam("point") String point,
                                      @RequestParam(value = "minutes", defaultValue = "10") int minutes) {
        String safeDevice = requireSafe("device", device);
        String safePoint = requireSafe("point", point);
        int windowMinutes = Math.max(1, Math.min(minutes, 24 * 60));

        List<Map<String, Object>> rows = td.query(
                "select ts, val, quality from " + td.database() + ".telemetry"
                        + " where device='" + safeDevice + "' and point='" + safePoint + "'"
                        + " and ts > now - " + windowMinutes + "m"
                        + " order by ts");

        List<Object[]> points = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            points.add(new Object[]{row.get("ts"), row.get("val"), row.get("quality")});
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("device", safeDevice);
        result.put("point", safePoint);
        result.put("minutes", windowMinutes);
        result.put("count", points.size());
        result.put("points", points);
        return result;
    }

    /** TDengine 不可用时给出可读的 503，而不是 500 堆栈。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(TdengineException.class)
    public ResponseEntity<Map<String, Object>> handleTdengine(TdengineException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "TDengine 不可用");
        body.put("detail", e.getMessage());
        body.put("hint", "先跑 device\\tdengine\\start-tdengine.cmd");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** 参数不合法（比如 device 名带引号想注入）→ 400，不要 500。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "参数不合法");
        body.put("detail", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 白名单校验 —— 这两个参数直接拼进 SQL，必须挡住注入。
     *
     * <p>只允许字母/数字/下划线/短横线：设备名和点位名在我们的点表里就是这个格式。
     */
    private static String requireSafe(String what, String value) {
        if (value == null || !value.matches("[A-Za-z0-9_\\-]{1,64}")) {
            throw new IllegalArgumentException(what + " 只允许字母/数字/下划线/短横线，实际: " + value);
        }
        return value;
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
