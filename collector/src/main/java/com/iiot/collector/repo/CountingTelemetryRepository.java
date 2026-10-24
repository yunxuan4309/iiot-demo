package com.iiot.collector.repo;

import com.iiot.collector.core.PointValue;
import com.iiot.collector.core.Quality;
import com.iiot.collector.core.TelemetryRepository;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * 计数型存储 —— <b>零依赖的占位实现</b>，用来先把「采集 → 入库」这条链路跑通。
 *
 * <p>它不真的持久化，只统计"如果落库会写多少点、多少 GOOD/STALE/BAD"。
 * 存在的意义：
 * <ol>
 *   <li>先证明链路的<b>吞吐和延迟</b>（换真库之前，把采集端的性能基线定下来）</li>
 *   <li>换 TDengine / QuestDB 时，用同一个 {@link TelemetryRepository} 接口对比，
 *       <b>数字才有可比性</b>（同样的采集端、同样的点表）</li>
 * </ol>
 *
 * <p>⚠️ 它<b>不是</b> demo 的最终存储 —— 最终必须有真库，否则"时序数据"这个故事不成立。
 */
public final class CountingTelemetryRepository implements TelemetryRepository {

    private final LongAdder batches = new LongAdder();
    private final LongAdder points = new LongAdder();
    private final LongAdder good = new LongAdder();
    private final LongAdder stale = new LongAdder();
    private final LongAdder bad = new LongAdder();

    private volatile Instant lastWriteAt;

    @Override
    public void write(List<PointValue> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        batches.increment();
        points.add(batch.size());
        for (PointValue value : batch) {
            switch (value.quality()) {
                case GOOD -> good.increment();
                case STALE -> stale.increment();
                case BAD -> bad.increment();
            }
        }
        lastWriteAt = Instant.now();
    }

    public long batches() {
        return batches.sum();
    }

    public long points() {
        return points.sum();
    }

    public long good() {
        return good.sum();
    }

    public long stale() {
        return stale.sum();
    }

    public long bad() {
        return bad.sum();
    }

    public Instant lastWriteAt() {
        return lastWriteAt;
    }

    @Override
    public String describe() {
        return "counting（不落盘，仅计数 —— 用于建立采集端性能基线）";
    }
}
