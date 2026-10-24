package com.iiot.collector.core;

import com.iiot.collector.proto.WordOrder;

/**
 * 一个点位的定义 —— <b>区域、偏移、类型、字序、量程全部来自配置文件，不写死在代码里</b>。
 *
 * <p>为什么字序必须是配置项：真设备不会告诉你它用 CDAB 还是 ABCD。现场做法是
 * 「读一次、看数值像不像量纲」（Plan A），或者翻厂家的通讯手册（Plan B），然后<b>把这个结论写进点表</b>。
 * 代码里硬编码一种字序，等于把某一台设备的行为当成协议标准。
 *
 * <p>为什么量程（min/max）也在这里：字序配错时读出来是天文数字（1e29），程序不会报错、
 * 也不会崩，只会把垃圾数据一路送到页面。量程是<b>最后一道防线</b> ——
 * 超出物理可能的范围就判 BAD，让下游知道"这个数别用"。
 *
 * @param id        点位名（唯一，平台侧的点位标识）
 * @param area      Modbus 数据区
 * @param offset    协议偏移（<b>0 基</b>；惯用编号 40001 对应这里 offset 0）
 * @param type      数据类型
 * @param wordOrder 32 位值的字序；BOOL 类型忽略，可为 {@code null}
 * @param unit      工程单位（仅用于展示，可空）
 * @param min       物理下限（可空 = 不校验）
 * @param max       物理上限（可空 = 不校验）
 */
public record PointDef(String id, Area area, int offset, Type type, WordOrder wordOrder, String unit,
                       Double min, Double max) {

    /** 兼容旧调用的构造器（不配量程）。 */
    public PointDef(String id, Area area, int offset, Type type, WordOrder wordOrder, String unit) {
        this(id, area, offset, type, wordOrder, unit, null, null);
    }

    /** Modbus 四个数据区。 */
    public enum Area {
        /** 线圈，FC01 读 / FC05 写。 */
        COIL,
        /** 离散输入，FC02 读。 */
        DISCRETE_INPUT,
        /** 保持寄存器，FC03 读 / FC06 写。 */
        HOLDING_REGISTER,
        /** 输入寄存器，FC04 读。 */
        INPUT_REGISTER
    }

    /** 本 demo 用到的数据类型。 */
    public enum Type {
        BOOL(1),
        FLOAT32(2),
        UINT32(2);

        private final int registerCount;

        Type(int registerCount) {
            this.registerCount = registerCount;
        }

        public int registerCount() {
            return registerCount;
        }

        public boolean isBoolean() {
            return this == BOOL;
        }
    }

    public int registerCount() {
        return type.registerCount();
    }

    public boolean isWritable() {
        return area == Area.COIL || area == Area.HOLDING_REGISTER;
    }

    /** 是否配了量程。 */
    public boolean hasRange() {
        return min != null || max != null;
    }

    /** 值是否落在量程内。没配量程一律算通过；非有限数（NaN/Inf）一律不通过。 */
    public boolean inRange(double value) {
        if (!Double.isFinite(value)) {
            return false;
        }
        if (min != null && value < min) {
            return false;
        }
        return max == null || value <= max;
    }

    /** 给人看的量程描述，用于日志与错误消息。 */
    public String rangeText() {
        if (!hasRange()) {
            return "（未配量程）";
        }
        String lower = min == null ? "-∞" : plain(min);
        String upper = max == null ? "+∞" : plain(max);
        return "[" + lower + ", " + upper + "]" + (unit == null || unit.isBlank() ? "" : unit);
    }

    private static String plain(Double value) {
        return value == Math.rint(value) ? String.valueOf(value.longValue()) : String.valueOf(value);
    }

    /** 校验点表是否自洽，返回问题描述；没问题返回 {@code null}。 */
    public String validate() {
        if (id == null || id.isBlank()) {
            return "点位缺少 id";
        }
        if (offset < 0) {
            return id + ": offset 不能为负（协议是 0 基，40001 对应 offset 0）";
        }
        if (!type.isBoolean() && wordOrder == null) {
            return id + ": " + type + " 必须配置 order（ABCD/CDAB/BADC/DCBA），否则解出来是天文数字";
        }
        if (min != null && max != null && min > max) {
            return id + ": 量程写反了（min=" + plain(min) + " > max=" + plain(max) + "）";
        }
        return null;
    }
}
