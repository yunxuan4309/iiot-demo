package com.iiot.collector.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 采集指标 —— <b>回答"这套采集到底行不行"，而不是"它跑起来了没有"</b>。
 *
 * <p>口径（和电商的 QPS 完全不是一回事，见计划书 §10）：
 * <table border="1">
 *   <caption>指标定义</caption>
 *   <tr><th>指标</th><th>定义</th></tr>
 *   <tr><td><b>采集完整率</b></td><td>GOOD 点数 ÷ 期望点数（期望 = 每轮点数 × 轮次）</td></tr>
 *   <tr><td><b>采集延迟</b></td><td>读一轮的耗时 P50 / P95 / P99</td></tr>
 *   <tr><td><b>入库吞吐</b></td><td>GOOD 点 / 秒</td></tr>
 *   <tr><td><b>BAD/STALE 占比</b></td><td>不可信点数 ÷ 总点数</td></tr>
 * </table>
 *
 * <p><b>为什么不报 QPS</b>：工业采集的瓶颈是「轮询周期 × 设备数 × 每台耗时」，
 * 不是「每秒能接多少请求」。一个 100 台的采集器可能只有 100 req/s，但每台 10ms 的
 * 超时就会把整体拖垮 —— 报 QPS 看不出这个问题。
 */
public final class CollectStats {

    /** 单台设备的计数。用 LongAdder：每台一条线程，写多读少。 */
    private static final class DeviceCounter {
        final String deviceId;
        final LongAdder rounds = new LongAdder();
        final LongAdder commFailures = new LongAdder();
        final LongAdder rangeErrors = new LongAdder();
        final LongAdder expected = new LongAdder();
        final LongAdder good = new LongAdder();
        final LongAdder stale = new LongAdder();
        final LongAdder bad = new LongAdder();
        volatile String lastError;
        volatile String lastRangeError;
        volatile Instant lastGoodAt;
        volatile Throwable crashed;

        DeviceCounter(String deviceId) {
            this.deviceId = deviceId;
        }
    }

    private final Map<String, DeviceCounter> devices = new ConcurrentHashMap<>();
    private final Instant startedAt = Instant.now();
    private final int pointsPerRound;
    /** 环形缓冲存延迟样本，避免无限增长。 */
    private final long[] latencies;
    private final AtomicLong latencyCursor = new AtomicLong();

    public CollectStats(int pointsPerRound, int latencyCapacity) {
        this.pointsPerRound = pointsPerRound;
        this.latencies = new long[Math.max(1024, latencyCapacity)];
    }

    /** 记一轮。{@code latencyMicros} 用<b>微秒</b>：本机回环读一轮往往不到 1ms，用毫秒会被截断成 0。 */
    public void record(String deviceId, List<PointValue> batch, long latencyMicros,
                       boolean commOk, String rangeError) {
        DeviceCounter counter = devices.computeIfAbsent(deviceId, DeviceCounter::new);
        counter.rounds.increment();
        counter.expected.add(batch.size());
        if (!commOk) {
            counter.commFailures.increment();
        }
        if (rangeError != null) {
            counter.rangeErrors.increment();
            counter.lastRangeError = rangeError;
        }
        for (PointValue value : batch) {
            switch (value.quality()) {
                case GOOD -> counter.good.increment();
                case STALE -> counter.stale.increment();
                case BAD -> counter.bad.increment();
            }
        }
        if (commOk) {
            counter.lastError = null;
            counter.lastGoodAt = Instant.now();
        }
        int slot = (int) Math.floorMod(latencyCursor.getAndIncrement(), latencies.length);
        latencies[slot] = latencyMicros;
    }

    /** 设备线程整体崩了（应当极少发生 —— 隔离机制的兜底记录）。 */
    public void deviceCrashed(String deviceId, Throwable error) {
        DeviceCounter counter = devices.computeIfAbsent(deviceId, DeviceCounter::new);
        counter.crashed = error;
        counter.lastError = String.valueOf(error);
    }

    public void noteCommError(String deviceId, String error) {
        DeviceCounter counter = devices.computeIfAbsent(deviceId, DeviceCounter::new);
        counter.lastError = error;
    }

    /** 生成最终报告（多行，直接打印）。 */
    public String report() {
        long elapsedMillis = Duration.between(startedAt, Instant.now()).toMillis();
        double elapsedSeconds = Math.max(elapsedMillis / 1000.0, 0.001);

        long expected = 0;
        long good = 0;
        long stale = 0;
        long bad = 0;
        long commFailures = 0;
        long rangeErrors = 0;
        for (DeviceCounter counter : devices.values()) {
            expected += counter.expected.sum();
            good += counter.good.sum();
            stale += counter.stale.sum();
            bad += counter.bad.sum();
            commFailures += counter.commFailures.sum();
            rangeErrors += counter.rangeErrors.sum();
        }
        long total = good + stale + bad;

        StringBuilder out = new StringBuilder();
        out.append("\n");
        out.append("══════════════════════════════════════════════════════════════\n");
        out.append("  采集报告（口径见 CollectStats 注释，不用 QPS）\n");
        out.append("══════════════════════════════════════════════════════════════\n");
        out.append(String.format("  设备数          %d 台（每台 %d 点）%n", devices.size(), pointsPerRound));
        out.append(String.format("  运行时长        %.1f s%n", elapsedSeconds));
        out.append(String.format("  期望点数        %d%n", expected));
        out.append(String.format("  实际总点数      %d（GOOD %d / STALE %d / BAD %d）%n", total, good, stale, bad));
        out.append(String.format("  ★ 采集完整率    %s（GOOD/期望）%n", percent(good, expected)));
        out.append(String.format("  ★ 入库吞吐      %.1f 点/秒%n", good / elapsedSeconds));
        out.append(String.format("  ★ 采集延迟      P50 %.2f ms · P95 %.2f ms · P99 %.2f ms%n",
                percentile(50) / 1000.0, percentile(95) / 1000.0, percentile(99) / 1000.0));
        out.append(String.format("  ★ 不可信占比    %s（STALE+BAD / 总点数）%n", percent(stale + bad, total)));
        out.append(String.format("  通信失败轮次    %d%n", commFailures));
        out.append(String.format("  量程越界轮次    %d%s%n", rangeErrors,
                rangeErrors > 0 ? "  ← 通常是点表里 order 配错了" : ""));

        List<DeviceCounter> problems = new ArrayList<>();
        for (DeviceCounter counter : devices.values()) {
            if (counter.commFailures.sum() > 0 || counter.rangeErrors.sum() > 0 || counter.crashed != null) {
                problems.add(counter);
            }
        }
        if (!problems.isEmpty()) {
            problems.sort(Comparator.comparing(counter -> counter.deviceId));
            out.append("  · 有问题的设备：\n");
            int shown = 0;
            for (DeviceCounter counter : problems) {
                if (shown++ >= 10) {
                    out.append(String.format("      …… 还有 %d 台%n", problems.size() - 10));
                    break;
                }
                out.append(String.format("      %-10s 失败 %d 轮 · 量程越界 %d 轮%s%s%n",
                        counter.deviceId,
                        counter.commFailures.sum(),
                        counter.rangeErrors.sum(),
                        counter.lastError == null ? "" : " · " + abbreviate(counter.lastError),
                        counter.crashed == null ? "" : " · 线程崩溃: " + counter.crashed));
                if (counter.lastRangeError != null) {
                    out.append(String.format("                 量程: %s%n", abbreviate(counter.lastRangeError)));
                }
            }
        }
        out.append("══════════════════════════════════════════════════════════════\n");
        return out.toString();
    }

    /** 一行进度，用于运行中打点。 */
    public String progressLine() {
        long expected = 0;
        long good = 0;
        long stale = 0;
        long bad = 0;
        for (DeviceCounter counter : devices.values()) {
            expected += counter.expected.sum();
            good += counter.good.sum();
            stale += counter.stale.sum();
            bad += counter.bad.sum();
        }
        long elapsedSeconds = Math.max(Duration.between(startedAt, Instant.now()).getSeconds(), 1);
        return String.format("  · %ds  GOOD %d / 期望 %d（%s）· STALE %d · BAD %d · %.0f 点/秒",
                elapsedSeconds, good, expected, percent(good, expected), stale, bad, good / (double) elapsedSeconds);
    }

    /**
     * 计算分位数。
     *
     * <p>⚠️ 这里有个容易写错的地方：环形缓冲里<b>未写过的槽位是 0</b>。
     * 如果连 0 一起排序，P50 会被算成 0（真实遇到过）。所以必须先用
     * {@code latencyCursor} 知道<b>写入了多少个样本</b>，只统计有效区间。
     */
    private long percentile(int p) {
        long total = latencyCursor.get();
        if (total <= 0) {
            return 0;
        }
        long[] used;
        if (total <= latencies.length) {
            used = new long[(int) total];
            System.arraycopy(latencies, 0, used, 0, (int) total);
        } else {
            // 已绕满至少一圈，整个数组都是有效样本
            used = latencies.clone();
        }
        java.util.Arrays.sort(used);
        int index = (int) Math.min(used.length - 1L, Math.round((p / 100.0) * (used.length - 1)));
        return used[index];
    }

    private static String percent(long part, long whole) {
        if (whole <= 0) {
            return "—";
        }
        return String.format("%.2f%%", 100.0 * part / whole);
    }

    private static String abbreviate(String text) {
        return text.length() <= 70 ? text : text.substring(0, 70) + "…";
    }
}
