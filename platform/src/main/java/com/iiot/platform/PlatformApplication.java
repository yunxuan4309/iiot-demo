package com.iiot.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 在线监测平台 —— <b>只读</b>的一方。
 *
 * <p>架构上刻意和采集端分开：
 * <pre>
 *   采集端（collector）  → 写 →  TDengine  → 读 →  平台（platform，本模块）
 * </pre>
 * 平台<b>不碰设备、不碰 Modbus</b>，它只查询时序库。这样做的原因很实际：
 * <ul>
 *   <li>采集端挂了，历史数据还在，平台照样能看过去 30 分钟 —— 排障时很重要</li>
 *   <li>平台被刷新压垮，不影响采集（采集是 10ms 级任务，平台是秒级展示）</li>
 *   <li>两边的发布节奏可以完全独立</li>
 * </ul>
 *
 * <p>启动后访问 {@code http://localhost:8080} 就是监控页面。
 */
@SpringBootApplication
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
