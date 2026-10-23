package com.iiot.collector.core;

import com.iiot.collector.proto.WordOrder;

/**
 * 一个点位的定义 —— <b>区域、偏移、类型、字序全部来自配置文件，不写死在代码里</b>。
 *
 * <p>为什么字序必须是配置项：真设备不会告诉你它用 CDAB 还是 ABCD。现场做法是
 * 「读一次、看数值像不像量纲」（Plan A），或者翻厂家的通讯手册（Plan B），然后<b>把这个结论写进点表</b>。
 * 代码里硬编码一种字序，等于把某一台设备的行为当成协议标准。
 *
 * @param id        点位名（唯一，平台侧的点位标识）
 * @param area      Modbus 数据区
 * @param offset    协议偏移（<b>0 基</b>；惯用编号 40001 对应这里 offset 0）
 * @param type      数据类型
 * @param wordOrder 32 位值的字序；BOOL 类型忽略，可为 {@code null}
 * @param unit      工程单位（仅用于展示，可空）
 */
public record PointDef(String id, Area area, int offset, Type type, WordOrder wordOrder, String unit) {

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
        return null;
    }
}
