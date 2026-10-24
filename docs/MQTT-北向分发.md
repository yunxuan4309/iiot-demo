# 北向 MQTT 分发（Mosquitto @ WSL2）

> 实测：**2026-10-24** · Mosquitto **2.0.11**（WSL2 Ubuntu 22.04）· Paho **1.2.5** · 采集端发布 **180 条 / 0 失败**

---

## 〇、为什么需要北向，以及为什么是 MQTT

### 南向和北向是两种不同的通信语义

```
设备侧（南向）              采集端                平台侧（北向）
Modbus RTU/TCP    →     collector     →    MQTT   →   MES / SCADA / 数据湖 / App / 大屏
一问一答、点对点                              发布订阅、一对多、可缓存
```

| | 南向 Modbus | 北向 MQTT |
|---|---|---|
| 谁发起 | **采集端轮询**（PLC 不会主动说） | **broker 推送** |
| 关系 | 点对点 | 一对多（一个数据源，N 个消费者） |
| 断线行为 | 重新建连 + 重读 | broker 保留消息，消费者重连即拿最新 |
| 典型消费者 | 只有采集端 | MES、SCADA、报表、大屏、手机 App |

**⇒ 这两种语义天生不同，所以中间必须有一个转换层 —— 这就是采集端存在的根本理由，不是"帮忙读个寄存器"那么简单。**

### 为什么不用 HTTP 轮询做北向

| | HTTP 轮询 | MQTT 发布订阅 |
|---|---|---|
| 新消费者上线 | 要等下一个轮询周期才有数 | **retain 消息立刻给最新值** |
| N 个消费者 | N 组轮询，服务端压力 ×N | 一次发布，broker 分发 |
| 实时性 | 取决于轮询间隔 | 发布即到（QoS 1） |

> 实测印证：**发布端停掉之后**，新订阅者 `mosquitto_sub -C 1` **立刻**拿到最后一条
> `{"val":71.94,"quality":"GOOD",...}` —— 这就是 retain 的价值。

---

## 一、Topic 设计

```
iiot/{deviceId}/{pointId}
```

例：

```
iiot/sim-01/temperature
iiot/sim-01/motor_running
iiot/sim-03/alarm
```

**通配订阅**：

| 订阅 | 含义 |
|---|---|
| `iiot/+/+` | 所有设备的所有点位 |
| `iiot/sim-01/#` | 单台设备的全部点位 |
| `iiot/+/temperature` | 所有设备的温度 |

> **为什么用 `iiot/` 前缀**：现场往往要接多个系统，前缀留出命名空间。
> **为什么 device 在 point 前面**：大部分查询是"看某台设备"，前者更利于 `+` 通配裁剪。

---

## 二、Payload：**必须带质量码**

```json
{"device":"sim-01","point":"temperature","val":64.22,
 "quality":"GOOD","ts":1729745611105,"unit":"°C"}
```

**为什么不只发一个 `64.22`**：

消费者无法区分「64.22 是刚采的」和「64.22 是 20 分钟前的旧值」。
北向消费者（MES/看板）拿到裸数字，就没法做"数据是否新鲜"的判断 ——
**这正是坑 3（断线不是 0）在北向的延伸**：分发出去的不是"值"，是"值 + 它的可信度 + 它的时刻"。

`ts` 用 **epoch 毫秒**（数字），不用字符串：避免"broker 时区 / 消费者时区"的歧义（见坑 6）。

---

## 三、两个关键参数

| 参数 | 选择 | 理由 |
|---|---|---|
| **QoS** | **1（至少一次）** | 工业数据丢一条可能意味着漏掉一次越限。QoS 2（恰好一次）握手更重，1 Hz 采集没必要；QoS 0 会丢 |
| **retain** | **true** | 新订阅者上线**立刻拿到最新值**，不用等下一个采集周期。这是 MQTT 相对 HTTP 轮询的核心优势 |

---

## 四、部署（WSL2，不用 root 密码）

```bash
# WSL 里（root 通过 wsl -u root 免密进入）
apt-get update -qq
apt-get install -y mosquitto mosquitto-clients

cat > /etc/mosquitto/conf.d/iiot.conf <<'EOF'
listener 1883 0.0.0.0
allow_anonymous true
EOF

systemctl enable --now mosquitto
```

> ⚠️ `allow_anonymous true` **只能用于本机 demo**。生产必须开认证（用户名密码 / 客户端证书）。

**Windows 侧访问**：WSL 的端口转发把它暴露成 `127.0.0.1:1883` 和 `[::1]:1883`，
**两个都能连**（与 TDengine 不同 —— 那个只有 `[::1]`，见 `docs/TDengine-部署与踩坑.md` 坑 C）。

**启停脚本**：`device\mqtt\start-mqtt.cmd` / `stop-mqtt.cmd` / `subscribe.cmd`

---

## 五、采集端接入

```powershell
collector\run-multi.cmd --fanout 10 --duration 60 --northbound mqtt
# 可选：--mqtt-url tcp://localhost:1883  --mqtt-qos 1  --mqtt-no-retain
```

单行日志：

```
北向 MQTT 已连接 · tcp://localhost:1883 · topic iiot/{device}/{point} · QoS 1 · retain=true
MQTT 北向: 已发布 180 条 · 失败 0
```

**设计要点：北向失败不影响南向采集。** `MqttPublisher.publish()` 内部 catch 掉异常只计数 ——
broker 挂了不能让采集停摆（这正是坑 5「隔离」思想在北向的复用）。

验证：

```powershell
device\mqtt\subscribe.cmd
# iiot/sim-01/temperature {"device":"sim-01","point":"temperature","val":74.57,"quality":"GOOD","ts":...,"unit":"°C"}
```

---

## 六、踩到的 4 个坑

| # | 坑 | 根因 / 解法 |
|---|---|---|
| 1 | **`pom.xml` 的注释里不能出现 `--`** | `< !-- 只在 --northbound mqtt 时连接 -- >` → Maven 报 `Non-parseable POM: in comment after two dashes`。去掉 `--` 即可 |
| 2 | **Paho v3 没有 `MqttMessage.setRetain()`** | retain 要通过重载传：`client.publish(topic, payload, qos, retained)` |
| 3 | ⭐ **JVM 打完报告不退出** | Paho 的客户端线程**不是守护线程**。实测 10 秒的任务跑了 3 分钟还没结束 —— 不是卡住，是"干完活但线程还挂着"。必须在收尾显式 `client.disconnect()` + `close()` |
| 4 | ⭐ **`.cmd` 里不能出现 `>` 或 `<`** | 写了个端口探测 `(echo > /dev/tcp/127.0.0.1/1883) 2>/dev/null`，**cmd 把 `>` 当重定向**，把 bash 命令截断成两半 → `bash: syntax error near unexpected token '2'`。改用 `ss -lnt \| grep 1883` |

---

## 七、面试口径

> 「南向是 Modbus，北向是 MQTT，**这两种语义天生不同** —— 南向只能问，北向要推。
> 所以中间必须有采集端做转换，不是"帮忙读个寄存器"。
>
> Topic 用 `iiot/{device}/{point}`，QoS 1（至少一次，工业数据丢了可能要命），**retain=true**
> 让新订阅者立刻拿到最新值 —— 这是 MQTT 相对 HTTP 轮询的核心优势，实测发布端停了、
> 新订阅者照样立刻拿到最后一条。
>
> Payload 里**必须带质量码和时间戳**，不能只发一个数字 —— 否则消费者分不清
> "64.22 是刚采的"还是"20 分钟前的旧值"，这是"断线不是 0"这个坑在北向的延伸。」
