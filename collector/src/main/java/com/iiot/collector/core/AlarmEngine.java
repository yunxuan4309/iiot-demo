package com.iiot.collector.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 告警引擎 —— <b>去抖 + 回差 + 分级 + 质量码门禁</b>。
 *
 * <h2>为什么不能"越限就报"</h2>
 * 实测过一笔账：10 分钟 3 台设备，按"采样点"报警会产生 <b>836 条</b>，
 * 而真实越限事件只有 <b>10 次</b> —— <b>放大 83.6 倍</b>。
 * 现场管这叫 <b>alarm flood（报警风暴）</b>，ISA-18.2 有专门规范。
 * 手机/企业微信会被刷爆，真正需要处理的 10 次淹在 836 条里。
 *
 * <h2>四条机制，缺一不可</h2>
 * <pre>
 *   NORMAL ──越限且【持续 N 秒】──▶ ACTIVE(WARNING) ──继续升高──▶ ACTIVE(CRITICAL)
 *      ▲                                  │
 *      └────── 回落到【触发值 − 回差】──────┘
 * </pre>
 * <ol>
 *   <li><b>持续时间去抖</b>：越过阈值不算，<b>持续</b> {@code minDurationMillis} 才算</li>
 *   <li><b>回差（hysteresis）</b>：恢复阈值 = 触发阈值 − {@code recoverOffset}，
 *       否则值在阈值附近抖动就会反复 RAISE/CLEAR</li>
 *   <li><b>分级</b>：WARNING / CRITICAL，升级只报一次（不重复刷 WARNING）</li>
 *   <li><b>质量码门禁</b>：<b>只对 GOOD 数据判告警</b> ——
 *       设备掉线后那个"最后值"是 STALE，拿它反复报警是最烦人的假告警</li>
 * </ol>
 *
 * <h2>本类不做的事</h2>
 * 不做通知（短信/企微/邮件），不做告警抑制（同源告警合并），不做排班。
 * 只做"该不该产生一条告警"这个判断，以及"产生了多少条"的计数。
 */
public final class AlarmEngine {

    /** 告警级别。 */
    public enum Level {
        /** 越过预警阈值。 */
        WARNING,
        /** 越过严重阈值。 */
        CRITICAL
    }

    /** 告警事件类型。 */
    public enum EventType {
        /** 新产生一条告警（去抖后）。 */
        RAISED,
        /** 已告警的点位级别升高（WARNING → CRITICAL）。 */
        UPGRADED,
        /** 回差恢复，告警解除。 */
        CLEARED
    }

    /**
     * 一个点位的告警规则。
     *
     * @param pointId           点位名（如 {@code temperature}）
     * @param warningHigh       预警阈值（高于它进入待确认）
     * @param criticalHigh      严重阈值
     * @param recoverOffset     回差：恢复阈值 = 触发阈值 − 本值
     * @param minDurationMillis 越限必须持续多久才真正报警
     */
    public record Rule(String pointId, double warningHigh, double criticalHigh,
                       double recoverOffset, long minDurationMillis) {
        public Rule {
            if (criticalHigh <= warningHigh) {
                throw new IllegalArgumentException("criticalHigh 必须大于 warningHigh：" + pointId);
            }
            if (recoverOffset < 0) {
                throw new IllegalArgumentException("recoverOffset 不能为负：" + pointId);
            }
        }
    }

    /** 一条告警事件。 */
    public record Event(long tsMillis, String deviceId, String pointId,
                        EventType type, Level level, double value, long overDurationMillis) {

        private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        /** 控制台单行输出（纯 ASCII 标记，避免 cmd 编码问题）。 */
        public String format() {
            String tag = switch (type) {
                case RAISED -> "[ALARM]  ";
                case UPGRADED -> "[UPGRADE]";
                case CLEARED -> "[CLEAR]  ";
            };
            String clock = LocalTime.ofInstant(Instant.ofEpochMilli(tsMillis), ZoneId.systemDefault())
                    .format(CLOCK);
            String extra = type == EventType.CLEARED
                    ? "回差恢复"
                    : String.format("持续 %.1fs", overDurationMillis / 1000.0);
            return String.format("%s %s %-9s %-12s %-8s %8.2f  (%s)",
                    clock, tag, deviceId, pointId, level, value, extra);
        }
    }

    /** 每个「设备 × 点位」一份状态。 */
    private static final class State {
        Level active;          // null = 正常
        long overSince = -1;   // 首次越限的时刻（-1 = 当前不越限）
        boolean wasOver;       // 上一轮是否越限（用来数"阈值穿越次数"）
    }

    private final Map<String, Rule> rules;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    // ── 计数：用来证明"去抖到底省掉了多少条" ──
    private final LongAdder overSamples = new LongAdder();
    private final LongAdder thresholdCrossings = new LongAdder();
    private final LongAdder raised = new LongAdder();
    private final LongAdder upgraded = new LongAdder();
    private final LongAdder cleared = new LongAdder();
    private final LongAdder skippedNotGood = new LongAdder();

    public AlarmEngine(Map<String, Rule> rules) {
        this.rules = Map.copyOf(rules);
    }

    /** 没有任何规则时返回 null，调用方据此跳过告警逻辑。 */
    public static AlarmEngine loadDefault() {
        Properties props = new Properties();
        try (InputStream in = AlarmEngine.class.getResourceAsStream("/alarms.properties")) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 alarms.properties 失败", e);
        }
        return fromProperties(props);
    }

    /** 从外部文件加载（便于现场改规则不改代码）。 */
    public static AlarmEngine load(Path file) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return fromProperties(props);
    }

    /**
     * 从 properties 解析规则。键形如：
     * <pre>
     *   alarm.temperature.warningHigh=80
     *   alarm.temperature.criticalHigh=85
     *   alarm.temperature.recoverOffset=5
     *   alarm.temperature.durationMillis=5000
     * </pre>
     */
    public static AlarmEngine fromProperties(Properties props) {
        Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith("alarm.")) {
                continue;
            }
            String rest = key.substring("alarm.".length());
            int dot = rest.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String point = rest.substring(0, dot);
            String field = rest.substring(dot + 1);
            grouped.computeIfAbsent(point, p -> new LinkedHashMap<>()).put(field, props.getProperty(key).trim());
        }

        Map<String, Rule> rules = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : grouped.entrySet()) {
            String point = entry.getKey();
            Map<String, String> fields = entry.getValue();
            if (!fields.containsKey("warningHigh") || !fields.containsKey("criticalHigh")) {
                continue;
            }
            rules.put(point, new Rule(
                    point,
                    Double.parseDouble(fields.get("warningHigh")),
                    Double.parseDouble(fields.get("criticalHigh")),
                    fields.containsKey("recoverOffset") ? Double.parseDouble(fields.get("recoverOffset")) : 0,
                    fields.containsKey("durationMillis") ? Long.parseLong(fields.get("durationMillis")) : 0));
        }
        return rules.isEmpty() ? null : new AlarmEngine(rules);
    }

    public Map<String, Rule> rules() {
        return rules;
    }

    /**
     * 送入一个采样点，返回本轮产生的事件（0~2 条：最多一条升级 + 一条恢复不可能同时）。
     *
     * <p>线程安全：不同设备线程并发调用，状态按 {@code deviceId/pointId} 分段。
     */
    public List<Event> onSample(PointValue value) {
        Rule rule = rules.get(value.pointId());
        if (rule == null) {
            return List.of();
        }
        // 质量码门禁：STALE/BAD 不参与告警判断。
        // 设备掉线后"最后值"会被反复送进来，拿它报警就是假告警。
        if (value.quality() != Quality.GOOD) {
            skippedNotGood.increment();
            return List.of();
        }
        if (!Double.isFinite(value.value())) {
            return List.of();
        }

        long ts = value.ts().toEpochMilli();
        String key = value.deviceId() + '\u0001' + value.pointId();
        State state = states.computeIfAbsent(key, k -> new State());
        List<Event> events = new ArrayList<>(2);

        double v = value.value();
        boolean over = v > rule.warningHigh();
        if (over) {
            overSamples.increment();
            if (!state.wasOver) {
                thresholdCrossings.increment();
            }
        }
        state.wasOver = over;

        if (state.active == null) {
            if (!over) {
                state.overSince = -1;
                return events;
            }
            if (state.overSince < 0) {
                state.overSince = ts;
            }
            long overFor = ts - state.overSince;
            if (overFor < rule.minDurationMillis()) {
                return events;   // 还在去抖窗口里，先不报
            }
            Level level = v > rule.criticalHigh() ? Level.CRITICAL : Level.WARNING;
            state.active = level;
            raised.increment();
            events.add(new Event(ts, value.deviceId(), value.pointId(),
                    EventType.RAISED, level, v, overFor));
            return events;
        }

        // 已在告警中
        if (v > rule.criticalHigh() && state.active == Level.WARNING) {
            state.active = Level.CRITICAL;
            upgraded.increment();
            events.add(new Event(ts, value.deviceId(), value.pointId(),
                    EventType.UPGRADED, Level.CRITICAL, v, ts - state.overSince));
            return events;
        }

        double trigger = state.active == Level.CRITICAL ? rule.criticalHigh() : rule.warningHigh();
        double recoverAt = trigger - rule.recoverOffset();
        if (v < recoverAt) {
            Level wasLevel = state.active;
            state.active = null;
            state.overSince = -1;
            cleared.increment();
            events.add(new Event(ts, value.deviceId(), value.pointId(),
                    EventType.CLEARED, wasLevel, v, 0));
        }
        return events;
    }

    /** 汇总：三个数字放一起，一眼看出"去抖省掉了多少"。 */
    public String report() {
        long over = overSamples.sum();
        long cross = thresholdCrossings.sum();
        long alarm = raised.sum();
        StringBuilder sb = new StringBuilder();
        sb.append("══════════════════════════════════════════════════════════════\n");
        sb.append("  告警引擎（去抖 + 回差 + 分级 + 质量码门禁）\n");
        sb.append("══════════════════════════════════════════════════════════════\n");
        for (Rule rule : rules.values()) {
            sb.append(String.format("  规则 %-14s 预警 >%.1f  严重 >%.1f  回差 %.1f  持续 %d ms%n",
                    rule.pointId(), rule.warningHigh(), rule.criticalHigh(),
                    rule.recoverOffset(), rule.minDurationMillis()));
        }
        sb.append(String.format("  越限采样点        %,d   （若按采样点报警 = 就是这么多条）%n", over));
        sb.append(String.format("  阈值穿越次数      %,d   （若一穿一报 = 就是这么多条）%n", cross));
        sb.append(String.format("  ★ 实际告警条数    %,d   （去抖 + 回差之后）%n", alarm));
        sb.append(String.format("  升级 / 恢复       %,d / %,d%n", upgraded.sum(), cleared.sum()));
        if (alarm > 0) {
            sb.append(String.format("  ★ 相对采样点压缩  %.1fx%n", (double) over / alarm));
            sb.append(String.format("  ★ 相对穿越压缩    %.1fx%n", (double) cross / alarm));
        }
        sb.append(String.format("  STALE/BAD 被拦下  %,d 个采样点（质量码门禁）%n", skippedNotGood.sum()));
        sb.append("══════════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    public long raisedCount() {
        return raised.sum();
    }
}
