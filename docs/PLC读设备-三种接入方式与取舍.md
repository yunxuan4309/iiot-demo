# PLC 怎么读设备：三种接入方式与我们的取舍

> 写于 2026-10-30。回答一个问题：**为什么三菱（GX Works2 / GX Simulator2）现在接不了真实设备，而 CODESYS 能？**
> 面试用途：这一篇是「协议与设备接入」的自证材料，能正面回答"你是不是只会调库"。

---

## 一、一句话结论

**能不能接设备，只看两样东西：有没有物理通信口，以及有没有对应协议栈（主站）。**
三菱仿真器两样都没有；真三菱 PLC 有门槛（要加模块）；CODESYS 软 PLC 借用 PC 网卡/串口，所以能。

---

## 二、三种接入方式，一张表看明白

| 方式 | 物理通信口 | 协议栈 | 能否被上位机采集 | 成本 |
|---|---|---|---|---|
| **GX Simulator2**（仿真器） | ❌ **没有**：X/Y/D 全是内存里的位和字 | ❌ 不跑 Modbus 主/从站 | ❌ 谁都连不上（只有 GX Works2 自己能看） | ¥0 |
| **真 FX3U** | 🟡 编程口是 **RS-422**（只给编程软件）；RS-485 / 以太网都要**另买模板** | 🟡 加 485 板后可用 `ADPRW` 做 Modbus RTU 主站 | ✅ 但还要加以太网模块（或走 MX Component 授权） | ¥1000+ |
| **真 FX5U** | ✅ **自带 RS-485 + 以太网** | ✅ Modbus RTU 主站 / Modbus TCP 客户端·服务端 | ✅ 一步到位 | 二手 ¥800~1200 |
| **CODESYS Control Win**（软 PLC） | ✅ **借用 PC 的网卡**（当前当 Modbus TCP 从站，1503）；理论上也可加 `Modbus Master, COM Port` 用本机串口 | ✅ 内置 Modbus TCP 从站/主站、OPC UA | ✅ Java 能连、能读能写 | ¥0 |

> **关键区分**：仿真器（simulator）复现的是 **CPU 指令与软元件**；真实运行时（runtime，如 CODESYS Control Win、西门子 PLCSIM Advanced、真 PLC）复现的是 **一台能对外通信的 PLC**。
> 顺带一句能加分的：**软 PLC ≠ 仿真器**。CODESYS Control Win 是装在 Windows 上的**真运行时**（任务调度、通信栈、扫描周期都是真的）；PLCSIM / GX Simulator 才是"模拟 CPU"。

---

## 三、为什么三菱仿真器接不了设备（三层原因，逐层说清）

1. **没有物理口**：仿真器的 `X`（输入）/`Y`（输出）/`D`（数据）只是 PC 内存里的位和字。你写 `D0 = 40`，它不是"从某个端口收到的 40"，而是"内存里改了个数"。**没有口，就没有数据进出**。
2. **不跑协议栈**：Modbus 需要一侧当**主站**（主动发 FC03 去读）。仿真器里没有 Modbus 协议栈 —— 你在梯形图里写 `ADPRW` 或 `CMP`，那是 **PLC 指令**，不是"对外发 Modbus 报文"。
3. **对外只开自家门**：三菱留给第三方的通道是 **MX Component** 这类自家组件（`GX Simulator2 通信` 在 MX Component 手册里是作为一种 CPU COM 通信目标）。要通也是三菱的私有 API，**不是 Modbus，不是工业标准**。

⇒ 结论：**仿真器能验证"逻辑对不对"，不能验证"数据取得上来没有"。**

---

## 四、真实工业逻辑长什么样（这才是标准形态）

```
传感器/仪表 ──RS-485(Modbus RTU)、模拟量 4~20mA、IO-Link──┐
                                                        ▼
                                                   [ PLC ]
                                        ┌──────────────┴──────────────┐
                                        ▼                             ▼
                              [执行器：电机/阀/风扇]        [以太网/Modbus TCP/OPC UA]
                                                                        ▼
                                                          [SCADA / 上位机 / 时序库]
```

**要点：现场设备先接 PLC，PLC 做采集与判断，上位机再去读 PLC。**
（也有例外：能耗表、独立仪表常由上位机直读 —— 但那属于"旁路采集"，不是主控回路。）

---

## 五、我们当前的形态，以及为什么"不标准"

当前（2026-10-28 实测通过的那套）：

```
真传感器 ──RS-485(COM7)──> Java（RtuModbusDriver 读 FC03）
                                │  BridgeMain 只搬运、不判断
                                ├──Modbus TCP 写保持寄存器 %IW0──> CODESYS PLC
                                │                                     └─ rTemp := %IW0/10 → xAlarm := rTemp > 32
                                └──MQTT/HTTP──> 平台
```

**这是"Java 代采 + 写进 PLC"**，见 [BridgeMain.java:16-25](../collector/src/main/java/com/iiot/collector/BridgeMain.java)、[points-codesys-writable.properties:10-17](../collector/src/main/resources/points-codesys-writable.properties)。

为什么这么干：
- 手上的 **CODESYS 是软 PLC**，没有现场总线口（485 要另配，且 COM7 被 Java 占着）；
- 这样能把"采集"和"判断"**分开演示**，讲起来清楚。

**代价（要主动承认）**：采集方向与真实产线相反 —— 真实产线是 PLC 读设备，我们暂时是 Java 读设备。（**2026-10-30 已改造为 PLC 直读，见 §六**）

---

## 六、改造已完成（2026-10-30 实测）

**做法**：CODESYS 里加串口主站，**PLC 自己当 Modbus RTU 主站**去读传感器。

```
设备树：
Device
├── PLC逻辑 / Application / PLC_PRG
├── Ethernet ── ModbusTCP_Server_Device        ← 保留：Java 仍从这里读结果
└── Modbus_COM (COM5 · 9600 · 8-N-1)           ← 串口
    └── Modbus_Client_COM_Port (RTU · 超时 1000ms · 勾「自动重启通信」)
        └── Modbus_Server_COM_Port (从站地址 1)  ← 描述"远端那台传感器"
            └── Channel 0：FC03 · 起始 0 · 长度 2 · 周期 100ms
```

**PLC_PRG**：

```iecst
wHumidityRaw AT %IW10 : WORD;   // holding[0] 湿度（uint16 ×0.1）
wTempRaw     AT %IW11 : WORD;   // holding[1] 温度（int16 ×0.1，支持负温）
rHumidity := WORD_TO_REAL(wHumidityRaw) / 10.0;
rTemp     := INT_TO_REAL(WORD_TO_INT(wTempRaw)) / 10.0;
xAlarm    := rTemp > 32.0;      // 判断仍在 PLC
```

**实测证据**（无硬件，用 com0com 的 `COM5 ↔ COM6` + `RtuSlaveMain --layout sensor`）：

| 变量 | 值 | 证明什么 |
|---|---|---|
| `wTempIn`（Java 写进 %IW0 的那个） | **0** | **Java 没参与** |
| `wHumidityRaw` / `wTempRaw` | **612 / 356** | PLC 从串口读到的原始寄存器 |
| `rTemp` / `rHumidity` | **35.6 / 61.2** | PLC 自己算 |
| `xAlarm` | **TRUE** | PLC 自己判断越限 |

截图：`D:\PLC学习\截图-面试用\PLC直读-35.6C-xAlarm-TRUE.png`

**这次学到的两个工程点**：

1. **通信失败必须自愈**：中途重启对端 → 主站超时 → 没勾「自动重启通信」就停在错误态，值被通道的「错误处理 = 保持最后值」**冻结**：界面上看起来还有数，实际已经不刷新了。这正是"数据新鲜度"必须靠时间戳/心跳判断、不能只看"有值"的原因。
2. **串口是独占资源**：CODESYS 在线时占着 COM5，Java 再打开同一个口直接失败。所以"PLC 读"和"上位机读"在物理层就是二选一 —— 现场才需要网关或多串口卡。

---

## 七、面试问答（可直接背）

**Q1：为什么不用三菱的仿真器采数据？**
> 「GX Simulator2 是 CPU 仿真器，X/Y/D 都只是内存里的位和字，**没有物理通信口，也不跑 Modbus 协议栈**，所以外部软件读不到它。它适合验证梯形图逻辑，不适合验证数据链路。要采数据得用带口的真 PLC，或者像我现在这样用 CODESYS 的真实运行时。」

**Q2：那你的 PLC 怎么拿到温度的？是不是假 PLC？**
> 「早期是 **Java 代采**：传感器只有 485、软 PLC 没有现场总线口，所以 Java 读后写进 PLC 的保持寄存器。**后来我按真实产线改掉了**：在 CODESYS 里加串口 Modbus 主站，**PLC 自己读传感器**（`%IW10/%IW11`），Java 退到"只读 PLC"。改造后实测：`wTempIn=0`（Java 没参与）、`wTempRaw=356`、`rTemp=35.6`、`xAlarm=TRUE`。」

**Q3：软 PLC 和真 PLC 差在哪？**
> 「软件运行时能跑真正的任务调度和通信栈，但它跑在 Windows 上：没有硬实时、没有电气隔离、没有看门狗和断电保持，也依赖 PC 不蓝屏。所以它能证明**协议和逻辑**，证明不了**产线级可靠性**。」

**Q4：现场给你一台 FX5U，你怎么接这个传感器？**
> 「FX5U 自带 RS-485 和以太网：485 口做 Modbus RTU 主站读传感器（`ADPRW` 或 Modbus 主站配置），以太网做 Modbus TCP 服务端给上位机读 —— 不买任何扩展模块，一步到位。这也是我不建议买 FX3U 的原因：FX3U 本体没有 485、也没有以太网，要加两块板才等价。」

---

## 八、一句话总结（最值钱的那句）

> 「**仿真器模拟的是 CPU 和内存，不是设备。** 让它验证逻辑，让真运行时验证链路 —— 这两件事分开做，才说得清。」
