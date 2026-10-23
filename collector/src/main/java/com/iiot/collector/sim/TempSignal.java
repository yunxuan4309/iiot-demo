package com.iiot.collector.sim;

import java.util.Random;

/**
 * 一个「像真设备」的温度信号 —— 对应计划书 §九 坑 0：假数据一眼假。
 *
 * <p>{@code Math.random()} 画出来是锯齿，任何人看一眼就知道是假的。真设备的值至少有四层质感：
 * <ol>
 *   <li><b>惯性</b>：一阶低通 {@code v = 0.95v + 0.05·目标}，不会一步跳到新值</li>
 *   <li><b>缓慢漂移</b>：3 分钟周期的正弦，模拟环境温度 / 负载的慢变化</li>
 *   <li><b>偶发尖峰</b>：约 0.5% 概率单点跳 6~12℃，模拟干扰，随后被低通拉回</li>
 *   <li><b>白噪声</b>：每次 ±0.08℃ 左右的抖动</li>
 * </ol>
 *
 * <p>随机数用<b>固定种子</b>，所以每次 demo 的曲线可复现 —— 演示和录屏时这点很关键。
 */
public final class TempSignal {

    /** 漂移周期（秒）。 */
    private static final double DRIFT_PERIOD_SECONDS = 180.0;
    /** 低通系数：越大越"迟钝"，越像大热容的工业设备。 */
    private static final double SMOOTHING = 0.95;
    /** 单次尖峰的概率。 */
    private static final double SPIKE_PROBABILITY = 0.005;

    private final Random random = new Random(20261023L);
    private final double base;
    private final long startedAtNanos = System.nanoTime();

    private double value;

    public TempSignal(double baseCelsius) {
        this.base = baseCelsius;
        this.value = baseCelsius;
    }

    /** 产生下一个采样值。 */
    public double next() {
        double elapsedSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0;
        double drift = 3.0 * Math.sin(2 * Math.PI * elapsedSeconds / DRIFT_PERIOD_SECONDS);
        double noise = random.nextGaussian() * 0.08;
        double target = base + drift + noise;

        value = SMOOTHING * value + (1 - SMOOTHING) * target;

        if (random.nextDouble() < SPIKE_PROBABILITY) {
            double magnitude = 6.0 + random.nextDouble() * 6.0;
            value += random.nextBoolean() ? magnitude : -magnitude;
        }
        return value;
    }

    /** 当前值（不推进）。 */
    public double current() {
        return value;
    }
}
