# 04 PLC 与北向：OPC UA 与 MQTT

> 覆盖 JD 职责 4「Modbus TCP/IP、OPC UA」与「MES、SCADA 对接」两块的实操与边界。
> 口径：**软 PLC 是真的运行时，不是仿真器**；**OPC UA 只做通了匿名链路，没做证书**。这两条都要主动说清。
> 证据：`docs/PLC读设备-三种接入方式与取舍.md`、`docs/OPC-UA-配置与实测.md`、`docs/CODESYS/LD与FBD-怎么发挥作用.md`、
> `docs/GXWorks2/梯形图与仿真-踩坑.md`、`docs/MQTT-北向分发.md`、`tools/opcua_probe.py`。

---

## 〇、PLC 在这个项目里的位置

```
        ┌──────────────── CODESYS Control Win V3 x64（软 PLC，真运行时）────────────────┐
        │                                                                            │
 [传感器]│  Modbus RTU 主站            PLC_PRG (ST)          Modbus TCP 从站 :1503     │──→ Java 采集端
 COM7 ──→  Modbus_COM → Client   →   rTemp / xAlarm   →     %QW → FC04 输入寄存器     │    FC04 / FC02
 9600-8N1  Server(从站1) · FC03      LD_Check · FBD_Check    %QX → FC02 离散输入      │
        │  Channel 起始0 长度2 100ms                          OPC UA 服务端 :4840       │──→ UaExpert / opcua_probe.py
        └────────────────────────────────────────────────────────────────────────────┘
```

| 端口 | 谁 | 用途 |
|---|---|---|
| **1503** | CODESYS | PLC 当 Modbus TCP 从站，给上位机读 |
| **4840** | CODESYS | OPC UA 服务端（自描述地址空间） |
| **1502** | `SimSlaveMain` | 仿真从站（没真表时顶替） |
| **1883 / 8080 / 6030 / 6041** | Mosquitto / 平台 / TDengine | 北向与存储 |

> 一句话：**同一份 PLC 变量，既能被 Modbus 按编号读走，也能被 OPC UA 按名字浏览到** —— 这就是本篇的主线。

---

### Q1. CODESYS 软 PLC 是什么？和真 PLC 差在哪？🔴

【追问1】软 PLC 不就是仿真器吗？
【追问2】没有硬实时，凭什么说它「真」？

【简答】
CODESYS Control Win V3 x64 是**装在 Windows 上的真实运行时**：有真的任务调度、真的通信栈、真的扫描周期。它和真 PLC 的差别不在「逻辑真假」，而在**实时性、隔离性、掉电保持**这三件硬件属性。

【深挖】
- **「真」在哪**：`PLC_PRG` 挂在 `MainTask` 上周期执行；`%IW/%QW/%QX` 是真实 I/O 映像；内置 **Modbus TCP 从站（1503）**、**Modbus RTU 主站**、**OPC UA 服务端（4840）** —— 这些都不是模拟的，外部客户端能连、能读、能写。
- **差在哪（三条，必须主动说）**：① **无硬实时** —— 跑在 Windows 上，扫描周期会抖，做不到 IRT / 等时同步；② **无电气隔离** —— PC 的网口/串口不是工业隔离口，现场干扰直接进主板；③ **无断电保持/看门狗** —— 掉电丢当前值，蓝屏就停，也没有硬件看门狗兜底。
- **和仿真器的区别（最容易混）**：**软 PLC ≠ 仿真器**。CODESYS Control Win 复现的是「一台能对外通信的 PLC」；PLCSIM / GX Simulator 复现的是「CPU 指令与软元件」，**没有通信口**。
- **一个真实约束**：免费运行时**跑 2 小时要重启**，所以长时间演示/压测要规划重启点 —— 这不是 bug，是授权策略。
- 面试话术："软 PLC 能证明**协议和逻辑**，证明不了**产线级可靠性**。它没有硬实时、没有隔离、没有断电保持 —— 所以我把『保护』留在 PLC 程序里讲逻辑，把『可靠性』明确说成是硬件的责任。"

---

### Q2. 为什么让 PLC 当 Modbus RTU 主站去读传感器？🔴

【追问1】Java 读不是更简单吗？
【追问2】改造前后差在哪？

【简答】
因为**真实产线就是这么分工的**：采集与判断在 PLC，上位机只搬运。我早期图省事让 Java 代采、再写进 PLC，后来按真实形态改成了 **PLC 自己当 RTU 主站读传感器**。

【深挖】
- **真实工业逻辑**：现场设备（传感器/仪表/变频器）先接 PLC，**PLC 做采集与判断**（超限就断输出、做联锁），上位机再去读 PLC 的结果。**保护不能依赖服务器** —— 服务器会挂、网会断、JVM 会 GC 停顿。
- **改造后的设备树**（2026-10-30 实测）：`Device → Ethernet(ModbusTCP_Server_Device)` 保留给 Java 读；新增 `Modbus_COM (COM5 · 9600 · 8-N-1) → Modbus_Client_COM_Port (RTU · 超时 1000ms) → Modbus_Server_COM_Port (从站 1) → Channel 0 (FC03 · 起始 0 · 长度 2 · 周期 100ms)`。
- **`COM5` 要说清**：真传感器在 **COM7**，但 PLC 直读那一轮手上没有真表，用的是 **com0com 虚拟串口对（COM5 ↔ COM6）** + `RtuSlaveMain --layout sensor` 顶替。真表接回来时，**COM7 同一时刻只能被一个程序打开** —— PLC 占了，Java 就读不了，反之亦然。
- **早期形态（要能讲清代价）**：`BridgeMain` 让 Java 读传感器 → 再 Modbus TCP 写进 PLC 的保持寄存器 `%IW0` → PLC 里 `rTemp := %IW0/10`。这能让「采集」和「判断」分开演示，但**采集方向与真实产线相反**。
- **两个工程教训**：① **通信失败必须自愈** —— 中途重启对端后主站超时，不勾「自动重启通信」就停在错误态，通道「错误处理=保持最后值」会让界面**看起来还有数、实际已不刷新**；② **串口是独占资源** —— PLC 在线占着 COM，Java 再开同一个口直接失败，所以「PLC 读」和「上位机直读」物理上二选一。
- 面试话术："一开始我是 Java 代采再写进 PLC，因为软 PLC 没有现场总线口、COM7 又被 Java 占着。后来我把它改成了 **PLC 自己当 RTU 主站** —— 采集和判断都回到 PLC，Java 退回只读。**这个改动就是为了让形态和真实产线一致。**"

---

### Q3. `%IW10/%IW11`、`wTempIn` 为什么是 0、`xAlarm := rTemp > 32.0` 怎么来的？🔴

【追问1】为什么用 %IW10/%IW11 而不是 %IW0/%IW1？
【追问2】`wTempIn=0` 能证明什么？

【简答】
`%IW10/%IW11` 是 RTU 主站通道把读回的寄存器映射进 PLC 的输入字；`wTempIn` 是早期「Java 代采写进 `%IW0`」留下的变量，改造后**没人写它，所以恒为 0**；`xAlarm` 是 PLC 自己算出来的判断结果。

【深挖】
- **PLC_PRG 的关键几行**（照实说，别改数）：

  ```iecst
  wHumidityRaw AT %IW10 : WORD;   // holding[0] 湿度（uint16 ×0.1）
  wTempRaw     AT %IW11 : WORD;   // holding[1] 温度（int16 ×0.1，支持负温）
  rHumidity := WORD_TO_REAL(wHumidityRaw) / 10.0;
  rTemp     := INT_TO_REAL(WORD_TO_INT(wTempRaw)) / 10.0;
  xAlarm    := rTemp > 32.0;      // 判断仍在 PLC
  ```

- **`%IW10/%IW11` 是通道映射的结果**：RTU 主站通道「起始 0、长度 2」读回 2 个寄存器，映射进输入字区；**映射到哪个字由工程配置决定，我们落在 `%IW10/%IW11`**。⇒ **偏移是通道配的，`%I` 地址是映射配的，两处要一起看**，只对一处就会读错。
- **实测值（吹热风那一轮）**：`wTempRaw=356`、`wHumidityRaw=612` → `rTemp=35.6℃`、`rHumidity=61.2%RH`、`xAlarm=TRUE`（`35.6 > 32.0`）。**换算和判断都在 PLC 里，Java 一行没参与。**
- **`wTempIn=0` 是「反证」**：它是 Java 代采时期写进 `%IW0` 的变量；改造后 Java 不再写，所以**恒为 0**。**这个 0 恰恰证明「数据不是 Java 灌进来的」** —— 面试时这是最省事的一条铁证。
- **`INT_TO_REAL(WORD_TO_INT(...))` 为什么要转两次**：`WORD` 是无符号，温度要按**有符号**解（负温时 `-53` 表示 `-5.3℃`）；先 `WORD_TO_INT` 拿回补码语义，再转 REAL —— 少一步，负温就会变成 65483。
- 面试话术："`wTempIn=0` 而 `wTempRaw=356`、`rTemp=35.6`、`xAlarm=TRUE` —— 这四个数放一起就说明**采集和判断都在 PLC 里**，Java 只在一旁读结果。"

---

### Q4. PLC 同时当 Modbus TCP 从站（1503）给上位机读，映射关系怎么对？🔴

【追问1】为什么 `%QW` 会跑到 FC04 输入寄存器？
【追问2】点表怎么配？

【简答】
CODESYS 的 Modbus 从站把 **`%QW` 映射成 FC04 输入寄存器、`%QX` 映射成 FC02 离散输入**（`%IW`→FC03、`%IX`→FC01）。**方向和直觉相反**，所以点表要按这个映射配，不能按「输出就该是被写」的直觉猜。

【深挖】
**映射表**（`codesys.properties` 头部注释，实测于 2026-10-24）：

| PLC 变量 | 区域 | 功能码 | Java 点表写法 |
|---|---|---|---|
| `rTemp : REAL`（`%QW0..%QW1`） | 输入寄存器 | **FC04** | `area=input, offset=0, type=float32, order=CDAB` |
| `xAlarm : BOOL`（`%QX0.0`） | 离散输入 | **FC02** | `area=discrete, offset=0, type=bool` |
| `%IW…` | 保持寄存器 | FC03 | 本工程没用 |
| `%IX…` | 线圈 | FC01 | 本工程没用 |

- **一个 REAL 占 2 个字**，所以 `rTemp` 占 `offset 0..1`；`xAlarm` 是位，占离散输入 `offset 0`。
- **字序必须实测**：`90.1 = 0x42B43333` → `reg[0]=0x3333, reg[1]=0x42B4` ⇒ **CDAB**（低位字在前）。用 `90.0` 验不出来（`0x42B40000` 低位字是 0，两种解释都显示 90.00）—— **验字序要挑带小数的值**。
- **Java 侧零特殊处理**：驱动不认识「CODESYS」，它只按 `area/offset/type/order` 读。**换一台真 PLC，只改点表。**
- 面试话术："PLC 当从站时，`%QW` 不是『保持寄存器』而是**输入寄存器 FC04** —— 这是 CODESYS 的约定。我第一次也按直觉配错了，后来靠『读出来是天文数字』反查到字序、靠『一直超时』反查到区域。"

---

### Q5. LD / ST / FBD 三语言同跑一台 PLC，同一逻辑三处实现怎么对齐？🟡

【追问1】变量名都不一样，怎么保证是同一个信号？
【追问2】图形化语言的价值在哪？

【简答】
三个 POU（`PLC_PRG` 用 ST、`LD_Check` 用 LD、`FBD_Check` 用 FBD）**都挂在同一个 `MainTask` 上**，变量名不同但 `AT` 同一地址，所以共享同一块内存。在线监视时**三处的真假必须同时一致** —— 这就是对齐的判据。

【深挖】
- **三种表达，一个逻辑**：ST `xAlarm := rTemp > 32.0;` · LD 触点 → 线圈 · FBD `GT` 框 → 分配。**IEC 61131-3 保证语义等价**，工具只是外壳。
- **为什么能共享**：`PLC_PRG.rTemp AT %QW0`、`LD_Check.rTempLD AT %QW0`、`FBD_Check.rTempFB AT %QW0` —— **变量名只是标签，真正的身份是地址**。同一个房间三个门牌号。
- **对齐判据（在线看，不看代码）**：`rTemp=25` 时三处报警位都是 `FALSE`；`rTemp=90` 时三处都是 `TRUE` —— LD 的**整条路径高亮**、FBD 的 **GT 框高亮**、ST 的表达式值变 TRUE。**三处同真同假才算对齐**。
- **踩过的坑**：① **FBD 输出脚不能直接填变量名**，必须拖一个「分配」元件；② **I/O 变量不吃声明里的初值**（`rTemp AT %QW0 : REAL := 25.0` 下载后是 0，因为 I/O 映像由外部驱动）；③ **改值必须「写入值」**（双击只是填「准备值」）；④ **在线时工程只读**，改声明要先注销。
- **正规做法是 GVL**：只声明一次 `rTemp AT %QW0`，三个 POU 直接引用 —— 我们为了改动最小用了「三处 `AT` 同地址」，**能跑但容易误解**，这点我认。
- 面试话术："三种语言不是三个技术，是三种表达方式：程序员看 ST、电工看 LD（像继电器电路）、控制工程师看 FBD（信号流）。我用**在线同真同假**证明它们逻辑等价 —— 不是靠我说，是靠监视画面。"

---

### Q6. 三菱 GX Works2 那套怎么做的？为什么仿真器采不了数据？🔴

【追问1】梯形图跑通了，为什么还说「接不了设备」？
【追问2】CMP 前面为什么要挂 M8000？

【简答】
FX3U 梯形图里 `LD M8000 → CMP D0 K32 M0`，`M0` 驱动 `Y0`，`D0=40` 时 `Y0` 导通、`D0=20` 时断开，GX Simulator2 里实测通过。
但**仿真器没有物理通信口、也不跑 Modbus 协议栈**，所以外部软件读不到它 —— **能验证逻辑，不能验证数据链路**。

【深挖】
- **梯形图两条梯级**：`步0: -| M8000 |----[ CMP D0 K32 M0 ]`、`步8: -| M0 |----( Y0 )` —— 每周期比较 `D0 > K32`，结果进 `M0`，`M0` 为 ON 则 `Y0` 输出。
- **为什么挂 `M8000`**：`CMP`/`MOV` 是**无触点指令**，没有通断状态，不能直接挂在母线上（GX Works2 会把它推到行末、与右母线重叠 ⇒ 报「存在无法转换的梯形图」）。`M8000` 是 FX 的常 ON 特殊继电器，经典用途就是给无触点指令做母线入口。
- **实测现象（颜色别记错）**：`D0=40` → `M0=ON`、`-|M0|` 与 `(Y000)` **蓝色高亮**；`D0=20` → 回细线。**GX Works2 是蓝色表示导通，CODESYS 才是绿色** —— 照「变绿」去找会以为没生效。
- **怎么确认仿真器真在跑**：GX Works2 **1.635M** + GX Simulator2，进程里要同时看到 `FXSimRun2.exe` 与 `SimManager.exe`；监视模式标题栏是「[PRG]监视 执行中 MAIN (只读)」、状态栏出现「模拟」、扫描周期显示 100ms。**只看画面不算，要看进程。**
- **仿真器为什么采不了（三层）**：① **没有物理口** —— `X/Y/D` 只是 PC 内存里的位和字，写 `D0=40` 是「改了个数」，不是「从端口收到的 40」；② **不跑协议栈** —— `ADPRW`/`CMP` 是 PLC 指令，不是「对外发 Modbus 报文」；③ **对外只开自家门** —— 三菱给第三方的通道是 MX Component 这类私有组件，不是 Modbus。
- **真 PLC 的门槛**：真 FX3U 编程口是 **RS-422**（只给编程软件），RS-485/以太网都要**另买模板**；FX5U 自带 485 + 以太网，485 做 RTU 主站读表、以太网做 TCP 服务端给上位机，**一步到位**。
- 面试话术："**仿真器模拟的是 CPU 和内存，不是设备。** 让它验证逻辑，让真运行时验证链路 —— 这两件事分开做，才说得清。"

---

### Q7. OPC UA 的地址空间 / 节点 / Browse 怎么理解？符号配置决定什么？🟡

【追问1】节点 ID 长什么样？
【追问2】为什么浏览不到我勾的变量？

【简答】
OPC UA 把服务端暴露的东西组织成一棵**地址空间树**，每个节点有 `NodeId`、名字、类型、当前值。客户端**Browse** 就能发现有什么，不需要点表。
CODESYS 里**「符号配置」勾了谁，就暴露谁** —— 没勾的变量在地址空间里根本不存在。

【深挖】
- **实测节点形态**：`ns=4;|var|CODESYS Control Win V3 x64.Application.PLC_PRG.rTemp`（字符串型 NodeId：`ns=4; |var|<PLC名>.Application.<POU>.<变量>`）。
- **Browse 出来的树**（`py -3 tools/opcua_probe.py`）：`DeviceSet → CODESYS Control Win V3 x64 → Resources → Application → Programs → PLC_PRG → {rHumidity, rTemp, wHumidityRaw, wTempRaw, xAlarm}`，每个变量带 **类型 + 当前值**（`Float 35.599998474121094`、`UInt16 356`、`Boolean True`）。
- **树证明三件事**：① 层级 = 设备 → 资源 → 应用 → 程序 → 变量；② **只有勾进「符号配置」的变量才出现**（没勾的不暴露，这本身就是最朴素的安全边界）；③ **变量自带名字 + 类型 + 值，客户端不需要点表** —— 这是 Modbus 做不到的。
- **配置四步（顺序别错）**：① `Device → 通信设置 → 顶部下拉「设备」→ 改变Runtime安全策略` 勾「允许匿名登录」；② `Application` 右键 → 添加对象 → **符号配置**，勾「支持 OPC UA 特征」；③ 在符号配置编辑器里**勾选要暴露的变量**并编译；④ **在线登录（下载）** —— 符号配置随下载一起传送。
- **最常见的坑**：「勾了、编译了，但 Browse 不到任何变量」→ **符号配置没有下载到 PLC**。它不是普通源码，改完必须下载或在线上修改才生效。
- 面试话术："Modbus 是**快递柜编号**，OPC UA 是**带标签的快递**。我用 `asyncua` 把地址空间 Browse 成树，一眼就能看到 PLC 里有什么、什么类型、现在多少 —— 这一步不需要任何点表。"

---

### Q8. OPC UA 匿名 vs 证书？我们怎么实测的？🟡

【追问1】为什么填了账号密码还是被拒？
【追问2】生产该怎么配？

【简答】
我们跑通的是**匿名 + SecurityPolicy None**，只用来验证链路。生产必须**关匿名、上 Basic256Sha256、导入客户端证书**。
中间踩到两个坑：CODESYS 默认拒匿名（`BadIdentityTokenInvalid`）；SecurityPolicy=None 通道上**明文密码被规范禁止**，所以填账号也不行。

【深挖】
- **实测输出**（2026-10-30，`tools/opcua_probe.py`，asyncua 2.1.0）：`rTemp = 35.599998474121094`、`wTempRaw = 356`、`wHumidityRaw = 612`、`xAlarm = True` —— 与 CODESYS 在线视图**完全一致**。
- **`35.599998474121094` 不是错**：`REAL` 是 32 位浮点，`35.6` 本来就存不下精确值，这是 Float32 的正常精度。**能被问到这里，答「这是 IEEE754 单精度的表示误差」就够。**
- **坑 1 · 匿名被拒**：客户端能连上，但 `ActivateSession` 返回 `BadIdentityTokenInvalid` —— CODESYS 默认开着设备用户管理、不允许匿名。
- **坑 2 · 填了账号也不行**：asyncua 打印 `Sending plain-text password`，服务端仍拒 —— 因为走的是 `SecurityPolicy = None` 通道，而 OPC UA 规范**禁止**在无加密通道上传用户名明文密码。⇒ 要么匿名（演示），要么 `Basic256Sha256` + 客户端证书被服务端信任（生产）。
- **坑 3 · 找不到 OPC UA 设置页**：SP22 把入口挪到了**通信设置页顶部的「设备」下拉菜单**里（叫「改变Runtime安全策略」），不在标签页里。
- **安全边界对照**：认证 匿名 → 用户+证书；通道 None → Basic256Sha256；暴露范围 只勾 5 个变量 → 最小权限 + 符号权限（匿名账号只给 view）。
- 面试话术："**『能读通』和『能上线』是两件事。** 匿名只是把链路验证出来；生产我会关匿名、上签名加密、把客户端证书导入 PLC 信任列表 —— 这三步我没做，所以我说它是 demo 级。"

---

### Q9. 为什么 OPC UA 和 Modbus 不是替代关系？对接 MES/SCADA 怎么选？🔴

【追问1】那为什么不全用 OPC UA？
【追问2】对方只说「要 Modbus」，怎么办？

【简答】
**Modbus 传数值、没有语义；OPC UA 传带名字/类型/结构的信息模型。** 一个解决「快而简单」，一个解决「自描述与跨系统集成」。
所以现场设备用 Modbus，跨系统（MES/SCADA/云）用 OPC UA —— **南向 Modbus、北向 OPC UA/MQTT**，两者分工而不是替代。

【深挖】
- **本质差别**：Modbus 只知道「第 3 个保持寄存器是 2 字节」，含义靠人配点表；OPC UA 的变量**自带名字、类型、工程单位、层级关系**，客户端可以 Browse 发现。类比：**Modbus 是快递柜编号，OPC UA 是带标签的快递。**
- **实时性上 OPC UA 不是赢家**：它毫秒~百毫秒级，**不是为实时控制设计的**；运动控制用 EtherCAT / Profinet IRT。**Modbus 采样 1 秒一次完全够**，这也是它 1979 年活到今天的原因：简单、跨厂商、免费。
- **选型看对方要什么，不看我偏好**：① 老设备/仪表/变频器 → 基本只有 Modbus（RTU 或 TCP）；② MES/SCADA/云平台 → 更偏 OPC UA（有信息模型、能带结构）；③ 对方已有网关/驱动（Kepware、Ignition）→ 问清它出的是哪种；④ 有安全/审计要求 → OPC UA 的证书与权限是加分项，Modbus 完全没有认证。
- **我的实操边界（诚实）**：Modbus RTU + TCP 我都自己写过主站、真设备验证过；OPC UA 我是**用 CODESYS 自带服务端 + asyncua 客户端跑通了匿名读取**，**没有做过证书、没有对接过真实 MES**。
- **一句话把两者接起来**：同一份 PLC 数据，Java 用 Modbus TCP 读、UaExpert 用 OPC UA 读，**值一致** —— 这证明「协议只是通道，数据模型才是语义」。
- 面试话术："选型我不站队。**现场层要便宜、要快、要跨厂商，用 Modbus；跨系统要能自解释、要权限，用 OPC UA。** 我的项目两条都通着，所以我能说清差别，而不是背概念。"

---

### Q10. MQTT 北向怎么设计的？topic / QoS / retain / 为什么没开认证？🔴

【追问1】为什么不用 HTTP 轮询？
【追问2】payload 为什么不能只发一个数字？

【简答】
Topic 用 `iiot/{device}/{point}`，**QoS 1（至少一次）**、**retain = true**。消费者一上线就拿到最新值，不用等下一个采集周期。
**没开认证是因为 broker 在本机 WSL2 里做 demo**，`allow_anonymous true` —— 生产必须开。

【深挖】
- **南北向语义不同**：南向必须「问」（Modbus 从站不会主动上报），北向要「推」（N 个消费者同时要）。**这两种语义天生不同，所以中间必须有采集端做转换** —— 这是采集端存在的根本理由，不是「帮忙读个寄存器」。
- **Topic 设计**：`iiot/{deviceId}/{pointId}`，通配 `iiot/+/+`（所有设备所有点）、`iiot/sim-01/#`（单台全部点）、`iiot/+/temperature`（所有设备温度）。**device 在 point 前面**，因为大多数查询是「看某台设备」。
- **QoS 1 而不是 2**：工业数据丢一条可能意味着漏掉一次越限；QoS 2 握手更重，1Hz 采集没必要；QoS 0 会丢。
- **retain 的价值（实测）**：发布端停掉之后，新订阅者 `mosquitto_sub -C 1` **立刻**拿到最后一条 `{"val":71.94,"quality":"GOOD",...}` —— 这就是「页面刚打开就有数」。
- **payload 必须带质量码和时间戳**：`{"device":"sim-01","point":"temperature","val":64.22,"quality":"GOOD","ts":1729745611105,"unit":"°C"}`。只发 `64.22`，消费者分不清「刚采的」还是「20 分钟前的旧值」 —— **这是「断线不是 0」在北向的延伸**：分发出去的不是「值」，是「值 + 可信度 + 时刻」。`ts` 用 epoch 毫秒，避免时区歧义。
- **隔离原则复用**：`MqttPublisher.publish()` 内部 catch 掉异常只计数（实测 180 条 / 0 失败）—— **broker 挂了不能让采集停摆**。
- **两个踩过的坑**：① Paho v3 **没有 `MqttMessage.setRetain()`**，retain 要通过 `client.publish(topic, payload, qos, retained)` 重载传；② **Paho 的客户端线程不是守护线程**，不显式 `disconnect()+close()`，JVM 打完报告也不退出（实测 10 秒的任务跑了 3 分钟还没结束）。
- **认证这条要主动认**：`allow_anonymous true` 只用于本机 demo；生产要用户名密码或客户端证书 + TLS。**做了没说，比没说更糟。**
- 面试话术："北向我选 MQTT 不选 HTTP 轮询，核心是 **retain**：新消费者一上线就有最新值，而且一次发布能给 N 个消费者。payload 里我坚持带质量码和时间戳 —— **没有可信度的数据，在工业场景里等于噪声。**"

---

## 二、换个问法，一句话接上

| 换个问法 | 一句话怎么答 |
|---|---|
| 你的 PLC 是真的吗？ | 是真运行时（CODESYS Control Win V3 x64），有真任务调度和真通信栈；但跑在 Windows 上，没有硬实时和电气隔离 |
| 怎么证明 PLC 在读设备？ | `wTempIn=0`（Java 没写）、`wTempRaw=356`、`rTemp=35.6`、`xAlarm=TRUE` —— 采集和判断都在 PLC 里 |
| OPC UA 你做到哪一步？ | 匿名 + SecurityPolicy None 读通了 `PLC_PRG.rTemp` 等 5 个变量；证书、权限、对接 MES 都没做 |
| 会三菱吗？ | FX3U 梯形图 + GX Simulator 跑通过（CMP/M8000/Y0）；但仿真器没有通信口，采不了数据，这点我踩过 |
| 你会几种 PLC 语言？ | LD/ST/FBD 在同一台 PLC 上各写一遍同一逻辑，在线同真同假；标准是 IEC 61131-3，不是某个软件 |
| 为什么北向用 MQTT？ | 南向只能问、北向要推；QoS 1 保不丢、retain 让新订阅者立刻拿到最新值 |

---

## 附：涉及的文件

| 文件 | 作用 |
|---|---|
| `codesys/iiot-demo-codesys.project` | PLC 工程（RTU 主站 + TCP 从站 + OPC UA 符号配置） |
| `collector/src/main/resources/codesys.properties` | 读 PLC 的点表（FC04 + FC02，`order=CDAB`） |
| `collector/src/main/java/com/iiot/collector/north/MqttPublisher.java` | 北向发布（topic/QoS/retain/payload） |
| `tools/opcua_probe.py` | OPC UA 探针：连接、列命名空间、Browse 成树、按名字读值 |
| `docs/OPC-UA-配置与实测.md` | 配置四步 + 5 个坑 + 安全边界 |
| `docs/PLC读设备-三种接入方式与取舍.md` | 为什么仿真器接不了设备、PLC 直读改造 |
| `docs/CODESYS/LD与FBD-怎么发挥作用.md` | 三语言同一逻辑、共享地址、CODESYS 四个「值」 |
| `docs/GXWorks2/梯形图与仿真-踩坑.md` | CMP/M8000、仿真操作路径、颜色差异 |
| `docs/MQTT-北向分发.md` | 南北向语义、retain 实测、4 个坑 |
