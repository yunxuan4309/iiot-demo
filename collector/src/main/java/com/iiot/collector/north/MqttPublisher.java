package com.iiot.collector.north;

import com.iiot.collector.core.PointValue;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.LongAdder;

/**
 * 北向 MQTT 发布端 —— 把采集到的点位<b>实时分发</b>给第三方系统（MES / SCADA / 数据湖 / 手机 App）。
 *
 * <h2>为什么南向是 Modbus，北向却要 MQTT</h2>
 * <pre>
 *   设备侧（南向）        采集端              平台侧（北向）
 *   Modbus RTU/TCP  →  collector  →  MQTT  →  MES / SCADA / App
 *   一问一答、点对点              发布订阅、一对多、可缓存
 * </pre>
 * 南向必须"问"（PLC 不会主动告诉你），北向要"推"（几十个消费者同时要）。
 * <b>这两种语义天生不同，所以中间必须有一个转换层</b> —— 这就是采集端存在的根本理由，
 * 不是"帮忙读个寄存器"那么简单。
 *
 * <h2>Topic 规范</h2>
 * <pre>
 *   iiot/{deviceId}/{pointId}         例：iiot/sim-01/temperature
 *   通配订阅：iiot/+/+  （所有设备所有点位）
 *             iiot/sim-01/#（单台设备全部点位）
 * </pre>
 *
 * <h2>两个关键参数的选择</h2>
 * <ul>
 *   <li><b>QoS 1（至少一次）</b>：工业数据丢一条可能意味着漏掉一次越限。
 *       QoS 2（恰好一次）握手更重，对 1Hz 采集没必要；QoS 0 会丢。</li>
 *   <li><b>retain = true</b>：新订阅者一上线就拿到**最新值**，不用等下一个采集周期。
 *       这是 MQTT 相对 HTTP 轮询的核心优势 —— <b>页面/大屏刚打开就有数</b>。</li>
 * </ul>
 *
 * <h2>Payload</h2>
 * 结构化的 JSON，而不是裸数字 —— 因为<b>北向消费者需要质量码</b>：
 * <pre>{"device":"sim-01","point":"temperature","val":64.22,"quality":"GOOD",
 *  "ts":1729745611105,"unit":"°C"}</pre>
 * 只发 {@code 64.22} 的话，消费者无法区分"64.22 是新值"还是"64.22 是 20 分钟前的旧值"。
 */
public final class MqttPublisher implements AutoCloseable {

    private final MqttClient client;
    private final int qos;
    private final boolean retain;
    private final LongAdder published = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private volatile String lastError;

    public MqttPublisher(String brokerUrl, String clientId, int qos, boolean retain) {
        this.qos = qos;
        this.retain = retain;
        try {
            this.client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(5);
            options.setKeepAliveInterval(30);
            this.client.connect(options);
        } catch (MqttException e) {
            throw new IllegalStateException("连不上 MQTT broker（" + brokerUrl + "）: " + e.getMessage()
                    + " —— WSL 里先跑 device\\tdengine\\start-mqtt.cmd 或 systemctl start mosquitto", e);
        }
    }

    /** 发布一个点位。异常不外抛 —— 北向失败不应该影响南向采集。 */
    public void publish(PointValue value, String unit) {
        String topic = "iiot/" + value.deviceId() + "/" + value.pointId();
        byte[] payload = toJson(value, unit).getBytes(StandardCharsets.UTF_8);
        try {
            // Paho v3 的 retain 是通过这个重载传的（MqttMessage 上没有 setRetain）
            client.publish(topic, payload, qos, retain);
            published.increment();
        } catch (MqttException e) {
            failed.increment();
            lastError = e.getMessage();
        }
    }

    private static String toJson(PointValue value, String unit) {
        return "{\"device\":\"" + escape(value.deviceId())
                + "\",\"point\":\"" + escape(value.pointId())
                + "\",\"val\":" + (Double.isFinite(value.value()) ? value.value() : "null")
                + ",\"quality\":\"" + value.quality().name()
                + "\",\"ts\":" + value.ts().toEpochMilli()
                + ",\"unit\":\"" + escape(unit == null ? "" : unit) + "\"}";
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public long publishedCount() {
        return published.sum();
    }

    public long failedCount() {
        return failed.sum();
    }

    public String summaryLine() {
        return String.format("  MQTT 北向: 已发布 %,d 条 · 失败 %,d%s",
                published.sum(), failed.sum(),
                lastError == null ? "" : " · 最后错误: " + lastError);
    }

    @Override
    public void close() {
        try {
            if (client.isConnected()) {
                client.disconnect(1000);
            }
            client.close();
        } catch (MqttException ignored) {
            // 关闭阶段的异常不值得打断收尾
        }
    }
}
