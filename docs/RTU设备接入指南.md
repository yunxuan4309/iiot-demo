# RS485 设备接入指南（RTU）

> **目标**：传感器到货后，**照这份清单走 10 步就能接上**，不需要重新研究代码。
> 2026-10-24 编写 · 状态见每节的「当前状态」标记。

---

## 〇、到货后 10 步（先看这张）

| # | 步骤 | 谁做 | 命令 / 动作 | 当前状态 |
|---|---|---|---|---|
| 1 | 装 USB-485 驱动（FT232 → VCP） | 人 | 设备管理器看有没有 `USB Serial Port (COMx)` | 待硬件 |
| 2 | 接 12V 电源 + 485 线（A→A，B→B） | 人 | 见 §2 接线 | 待硬件 |
| 3 | 查 COM 口号 | 人 | `[System.IO.Ports.SerialPort]::GetPortNames()` | ✅ 可用 |
| 4 | **探测能不能读到**（含四种字序对比） | 人 | `collector\run-probe.cmd --com COM3 --baud 9600` | ✅ **已实现** |
| 5 | 确认从站地址 / 波特率 / 校验位 | 人 | 看厂家手册；探测失败时逐个试 | 待硬件 |
| 6 | 填点表 `points-rtu.properties` | 人 | 见 §5，改 COM/波特率/字序/量程（模板已就绪） | ✅ **已实现** |
| 7 | 采集一次（不存库） | 人 | `collector\run-collect.cmd --points points-rtu.properties --cycles 5` | ✅ **已实现** |
| 8 | 采集 + 写 TDengine | 人 | `collector\run-multi-tdengine.cmd --points points-rtu.properties --fanout 1` | ⏳ |
| 9 | 页面看到数据 | 人 | `platform\run-platform.cmd` → `localhost:8080` | ✅ |
| 10 | 拔线演练（拔 485 线 → 应变 STALE 不是 0） | 人 | 看采集端输出 | ✅ 逻辑已就绪 |

---

## 一、为什么是 RTU 而不是 TCP

| | **Modbus RTU** | **Modbus TCP** |
|---|---|---|
| 物理层 | **RS485 双绞线**（A/B 两根） | 网线 / WiFi |
| 一台电脑接几台 | 一条 485 总线可挂 **32~247 台**（地址区分） | 每台一个 IP:port |
| 距离 | 1200 m（9600 bps 时） | 100 m（网线） |
| 现场占比 | **仪表/传感器绝大多数是 RTU** | PLC、网关、新设备 |

**⇒ 你要接的温湿度变送器是 RS485 → 走 RTU。**

---

## 二、接线（最容易翻车的一步）

```
        传感器                        USB-485 转换器
    ┌──────────────┐                ┌──────────────┐
    │  12V+   ─────┼──── 12V 电源 +  │              │
    │  GND    ─────┼──── 电源 −     │   A  ─────────┼── A
    │  A (485+) ───┼────────────────┼── B           │
    │  B (485−) ───┼────────────────┼── GND         │
    └──────────────┘                └──────────────┘
                                          │ USB
                                       电脑
```

### 三个必须注意

| # | 要点 | 说明 |
|---|---|---|
| **1** | **A 接 A、B 接 B** | 有些厂家标 `A+ / B−`，有些标 `D+ / D−`，还有的把 A/B 标反。**接反了完全读不到**（现象：一直超时，和"地址错"一模一样） |
| **2** | **必须共地（GND）** | 只接 A/B 不接 GND，短距离可能勉强能通，长一点就不稳定 |
| **3** | **12V 供电别接反** | 反接可能烧传感器 |

> 💡 **接反了怎么办**：把 A/B 对调再试。这是现场第一件要做的事，不是最后一件事。

---

## 三、驱动（FT232）

| 项 | 说明 |
|---|---|
| 芯片 | FTDI **FT232**（你买的那个版本） |
| 驱动 | **VCP（Virtual COM Port）驱动**，FTDI 官网有 |
| Win10/11 | 通常**自动装上**（设备管理器 → 端口 → `USB Serial Port (COMx)`） |
| 常见问题 | 显示黄色感叹号 → 驱动没装；显示 `COM3` 但打不开 → 被别的软件占用 |

**查 COM 口**（PowerShell）：

```powershell
[System.IO.Ports.SerialPort]::GetPortNames()
```

---

## 四、串口参数：四个必须和设备一致

| 参数 | 常见值 | 说明 |
|---|---|---|
| **波特率** | **9600**（最常见）/ 19200 / 38400 / 115200 | 错了就是乱码或超时 |
| **数据位** | **8** | 几乎总是 8 |
| **停止位** | **1** | 几乎总是 1 |
| **校验位** | **None**（8N1）/ Even / Odd | 工业仪表多为 8N1 |
| **从站地址** | **1**（出厂常见）/ 1~247 | 一台总线多设备时必须唯一 |

> 📌 **记法：`9600-8-N-1-地址1`** 是最常见的出厂默认。不通时优先怀疑这几项。

---

## 五、点表（RTU 版）

新增一个文件 `collector\src\main\resources\points-rtu.properties`：

```properties
# ── 串口设备（RTU）──
device.rtu-01.portType=rtu              # 关键：不再是 tcp
device.rtu-01.comPort=COM3              # 步骤 3 查到的口号
device.rtu-01.baudRate=9600
device.rtu-01.dataBits=8
device.rtu-01.stopBits=1
device.rtu-01.parity=none               # none / even / odd
device.rtu-01.unitId=1

device.rtu-01.periodMillis=2000         # RTU 慢一点更稳（半双工）
device.rtu-01.points=temperature,humidity

# 温度：手册写"寄存器 0x0000，Float32，只读"
device.rtu-01.point.temperature.area=holding
device.rtu-01.point.temperature.offset=0
device.rtu-01.point.temperature.type=float32
device.rtu-01.point.temperature.order=ABCD      # ← 用 probe 现场判定！
device.rtu-01.point.temperature.unit=°C
device.rtu-01.point.temperature.min=-40
device.rtu-01.point.temperature.max=120

# 湿度：寄存器 0x0002
device.rtu-01.point.humidity.area=holding
device.rtu-01.point.humidity.offset=2
device.rtu-01.point.humidity.type=float32
device.rtu-01.point.humidity.order=ABCD
device.rtu-01.point.humidity.unit=%RH
device.rtu-01.point.humidity.min=0
device.rtu-01.point.humidity.max=100
```

> ⚠️ **`order` 一定要用 probe 现场判定**，不要抄别人的。
> 国内仪表 CDAB 很常见，但艾克姆具体哪一版要看实测。

---

## 五·5、⭐ 真实仪表的常见形态：**「16 位整数 + 缩放」**

> **2026-10-25 补**：真传感器到货时才发现，点表原来**只支持 bool / float32 / uint32**，
> 而**真实国产仪表的绝大多数是"16 位整数 + 线性缩放"** —— 这类设备**一个都读不了**。
> 已补齐，这一节记录为什么必须有它。

### 现象：手册写"分辨率 0.1℃"，寄存器里却是 253

厂商界面显示 `温度 29.8℃`，但寄存器里存的是 **`298`**：

```
253  → 253 × 0.1 = 25.3 ℃
501  → 501 × 0.1 = 50.1 %RH
```

**为什么厂家不直接存浮点**：16 位整数省一半寄存器、省一半报文，而且**没有精度歧义**
（浮点存 25.3 可能变成 25.299999）。工业仪表几十年都这么干。

### 点表怎么写

```properties
# 温度：1 个寄存器，int16（必须是有符号 —— 否则 -5.3℃ 会读成 65483）
device.sensor-01.point.temperature.area=holding
device.sensor-01.point.temperature.offset=0
device.sensor-01.point.temperature.type=int16
device.sensor-01.point.temperature.scale=0.1
device.sensor-01.point.temperature.unit=°C

# 湿度：uint16（不会为负的量用无符号）
device.sensor-01.point.humidity.offset=1
device.sensor-01.point.humidity.type=uint16
device.sensor-01.point.humidity.scale=0.1
```

### ⭐ 三个必须记住的点

| # | 坑 | 说明 |
|---|---|---|
| 1 | **温度必须 `int16` 不能 `uint16`** | `-5.3℃` 存的是 `-53`，按无符号解释是 **65483** → ×0.1 = **6548.3℃**；量程校验会判 BAD，但**容易误以为是字序问题** |
| 2 | **16 位类型不需要 `order`** | 字序只对**跨两个寄存器**的 32 位类型有意义。给 int16 配 order 是"用 32 位思维误伤 16 位"（原来的 `validate()` 就误伤，已修） |
| 3 | **`scale` 是设备属性，不是代码逻辑** | 这台 ×0.1、那台可能 ×0.01。**写进点表**，换设备只改配置 |

### 已用虚拟串口做过回归（2026-10-25，不依赖真硬件）

在 `RtuSlaveMain` 里放了固定值，四个点覆盖全部四条解码路径：

```
reg[3] = 0xFFCB  → int16 = -53  → scale 0.1 → -5.30°C    ✅ 补码/负温度
reg[4] = 0x01F5  → uint16 = 501 → scale 0.1 → 50.10%RH   ✅ 无符号+缩放
reg[2] = counter → uint16 6→10  → scale 省略 = 1.0       ✅ 默认值
reg[0..1]        → float32 25.60                         ✅ 老路径没坏
```

```powershell
collector\run-rtu-slave.cmd --com COM5 --seconds 120
collector\run-collect.cmd --points collector\src\main\resources\points-rtu-int16-test.properties --cycles 5
```

> **⇒ 真设备通电前，解码链路已经验证过了**；到货只剩"改 `comPort` + 确认寄存器地址"。

---

## 六、RTU 特有的 5 个坑

| # | 坑 | 现象 | 解决 |
|---|---|---|---|
| 1 | **A/B 接反** | 一直超时（和地址错、波特率错现象一样） | 对调 A/B |
| 2 | **没共地** | 短距离能通、长距离不稳 | 接 GND |
| 3 | **波特率/校验位不对** | 超时或读到乱码 | 逐个试 9600/19200/38400，8N1/8E1 |
| 4 | **从站地址不是 1** | 一直超时 | 手册查；或 1~247 逐个扫 |
| 5 | **半双工：发完要切回接收** | 偶尔丢帧 | 转换器一般自动处理；必要时加帧间隔 |

> 📌 **RTU 的排查顺序**：接线（A/B、GND）→ 驱动（COM 口）→ 参数（波特率/校验）→ 地址 → 寄存器地址/字序。
> **"一直超时"这四个字，前面 4 项都可能，所以必须按顺序排除**，不要一上来就怀疑代码。

---

## 七、虚拟串口自测（没硬件也能验证）—— ✅ **2026-10-24 已实测通过**

### 结论先给

```
rtu-test@COM6(9600-8N1)/unit=1  点位 1 个
[16:27:23.281] rtu-test GOOD  temperature=25.60°C
[16:27:24.193] rtu-test GOOD  temperature=25.60°C
...
probe: 保持寄存器 offset 0 + ABCD → 25.60（四种字序里**唯一**合理解）
       holding[2] 计数器 11 → 23（12 秒涨 12）⇒ 每次都是真去串口读，不是缓存
```

**⇒ `RtuModbusDriver` 从"写完没跑过"变成"真的从串口读到数"。硬件到货后只剩「改 `comPort` + 接线」。**

### 本次实际用的方案（和原来设想不同，更好）

| | 原设想 | 实际做法 |
|---|---|---|
| 虚拟串口 | com0com `COM3 ↔ COM4` | com0com 3.0.0.0 **signed**，安装器直接给了 `COM5 ↔ COM6`（**不用改名字**） |
| 从站 | 第三方 Modbus 模拟器，**或**给仿真从站加 `--rtu` | ⭐ **用 j2mod 自带的从站 API 写了个 40 行测试桩** `RtuSlaveMain`，**零安装** |

> **为什么"40 行测试桩"不算违反「从站不忙做」**：
> 那条裁决针对的是**改产品里的 TCP 仿真从站**（`SimSlaveMain`）。
> `RtuSlaveMain` 是**独立的测试桩**：不进产品路径、只管 RTU over 串口、只为了能随手改一个数来验字序。

### 复现步骤（3 条命令）

```powershell
collector\run-rtu-slave.cmd --com COM5 --temperature 25.6   # 从站（j2mod ModbusSerialListener）
collector\run-probe.cmd --com COM6 --unit 1                 # 探测（四区域 + 四字序）
collector\run-collect.cmd --points collector\src\main\resources\points-rtu-test.properties --cycles 5
```

### ⭐ 两个必须记住的坑

**坑 A：验证值必须"高低字都非零"**

| 值 | Float32 | 低字 | 结果 |
|---|---|---|---|
| `25.5` | `0x41CC0000` | **0** | ❌ `ABCD@0` 和 `ABCD@1` 解出同一个数，**字序分不出来** |
| **`25.6`** | `0x41CCCCCD` | `0xCCCD` | ✅ 四种字序里**只有一种**合理 |

> 这是 CODESYS 那次的同一个教训（当时用 90.0 验不出 `CDAB`，换成 90.1 才分明）。
> **验证字序 = 必须用带非零小数部分的值。**

**坑 B：PowerShell 直接调 java 会拆坏 `-D` 参数**

```powershell
# ❌ 报 ClassNotFoundException: /encoding=UTF-8
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp ... Main

# ✅ 用 .cmd 包装（cmd 不改写参数）
cmd /c "collector\run-rtu-slave.cmd --com COM5"
```

### 顺带验证到的"配对关系"怎么查

com0com 装完不知道哪两个口是一对？查注册表最可靠：

```powershell
Get-ItemProperty "HKLM:\HARDWARE\DEVICEMAP\SERIALCOMM"
# \Device\com0com11 -> COM6   ┐ 这两个是一对
# \Device\com0com21 -> COM5   ┘
```

**动手前先做一步"串口对通不通"的最小验证**（跟 Modbus 无关，纯收发）：

```powershell
$p5 = New-Object System.IO.Ports.SerialPort("COM5",9600,'None',8,'One')
$p6 = New-Object System.IO.Ports.SerialPort("COM6",9600,'None',8,'One')
$p5.Open(); $p6.Open(); $p5.Write("HELLO"); Start-Sleep -m 300; $p6.ReadExisting()
```

实测双向各 15 字节、内容完全一致 —— **先排除"串口对本身不通"，再去查 Modbus 层**。

---

## 八、验收清单（到货后打勾）

- [ ] 设备管理器能看到 `USB Serial Port (COMx)`
- [ ] 传感器 12V 供电，指示灯正常
- [ ] A→A、B→B、GND 接好
- [ ] `GetPortNames()` 能看到该 COM 口
- [ ] `run-probe --com COMx --baud 9600` **能读到寄存器**
- [ ] 四种字序对比中，**只有一种落在合理区间**（温度 10~40℃）
- [ ] 点表 `order` 按实测结果填写
- [ ] `run-collect --points points-rtu.properties` 读到温度/湿度
- [ ] 写进 TDengine，页面能看到
- [ ] **拔掉 485 线** → 采集端变 **STALE**（不是 0），插回自动恢复
- [ ] 把这台设备的实测参数记进 `docs/pitfalls.md`（字序、波特率、地址）

---

## 九、当前缺什么（2026-10-24 16:30 更新）

| # | 缺口 | 状态 |
|---|---|---|
| 1 | **`RtuModbusDriver`** | ✅ **已实现**（`ModbusSerialMaster` + `SerialParameters`，含 RS485 方向控制开关） |
| 2 | **点表支持串口参数** | ✅ **已实现**（`portType=rtu` + `comPort/baudRate/dataBits/stopBits/parity/rs485Mode`；模板 `points-rtu.properties` 已就绪） |
| 3 | **`ProbeMain --com`** | ✅ **已实现**（`--com/--baud/--parity/--data-bits/--stop-bits/--rs485-mode`，失败时打印 RTU 六步排查） |
| 4 | **一条 COM 口只能打开一次** | ✅ **已解决**：`DriverRegistry` 按「物理链路」复用驱动 —— RTU 总线上所有 unitId 共用一条连接（半双工请求串行） |
| 5 | **虚拟串口自测（com0com）** | ✅ **2026-10-24 16:27 实测通过**：com0com `COM5↔COM6` + j2mod 测试桩 `RtuSlaveMain`；实测 `temperature=25.60°C`、字序 **ABCD**、计数器 11→23 证明实时读取。详见 §七 |
| 6 | TCP 回归 | ✅ **已验证**：重构后 10 台 600 点仍 **100%**（0 失败） |

> **⇒ 1~6 全部完成。** 硬件到货后只剩三件**物理**事：
> **① 接 A→A / B→B / GND ② 看设备管理器拿到的新 COM 号填进点表 ③ 用 `run-probe --com COMx` 确认字序（大概率仍是 ABCD 或 CDAB 之一）。**
>
> ⚠️ 仍需现场确认的**未知量**（虚拟串口验不出来，因为是软件模拟的）：
> **真实波特率/校验位是否与手册一致**、**RS485 方向控制是否需要 `--rs485-mode`**、**传感器 12V 供电是否稳**。

---

## 十、真设备实测记录（2026-10-28）✅ **通了**

### 设备与最终参数

| 项 | 实测值 |
|---|---|
| 设备 | **艾克姆 IKM10000WS01** 温湿度变送器（固件 V025002） |
| 供电 | DC 5V（宽压 5~30V，用 12V 转 5V 模块供） |
| 接线 | `VIN`←5V · `GND`←电源负 **+ 转换器 GND（共地）** · `A`←`T/R+` · `B`←`T/R-` |
| 串口 | **COM7**（FT232R + FTDI VCP 2.12.36.20）· **9600 · 8 · N · 1** · 从站地址 **1** |
| 区域 | **保持寄存器（FC03）** —— FC04 返回异常码 `01`（非法功能），这台**不支持输入寄存器** |
| **温度** | `offset = 1` · `int16` · `scale = 0.1` |
| **湿度** | `offset = 0` · `uint16` · `scale = 0.1` |
| 点表 | `collector/src/main/resources/points-sensor.properties` |

### 实测原始帧（这是最硬的证据）

```
请求  01 03 00 00 00 04 44 09          ← 从站1 读保持寄存器 0起4个
应答  01 03 08 | 02 86 | 00 F8 | 1F 5A | D3 99 | E8 8F
                ↑646        ↑248
             湿度 64.6%RH   温度 24.8℃
```

### 实测结果

```
[15:53:22.344] sensor-01 GOOD  temperature=24.70°C  humidity=64.40%RH
[15:53:25.199] sensor-01 GOOD  temperature=24.80°C  humidity=64.50%RH
[15:53:27.196] sensor-01 GOOD  temperature=24.70°C  humidity=64.40%RH
...
30 点 / 100.00% GOOD / 0 失败 / TDengine 写入 16 批 0 失败
库：sensor-01 与 plc-01（CODESYS）、sim-01~10（仿真）并存 —— 三类数据源同库同页
```

**单轮耗时**：首轮 **128 ms**（建连），之后稳定 **29~43 ms**（9600 bps 下 8 字节请求 + 9 字节应答 ≈ 17.7 ms 理论，其余是转换器与从站处理时间）。

### ⭐ 本次最大的教训：**排障顺序错了，白扫 16 组参数**

**当时的错误做法**：

```
现象：读不到
动作：扫 8 种波特率 × 2 种校验 × 8 个从站地址 = 16 轮，全部无回应
```

**真相**：**A/B 两根线还没接**（用户："我还没有把传感器和 usb 接起来呢"）。
电源接通后**指示灯亮了**，我就以为"通电正常 ⇒ 可以扫参数了"，但**信号线没接，任何参数都不可能回应**。

**⇒ 正确顺序（以后照这个来）**：

```
① 物理层面先确认"齐全"：电源？A？B？GND（共地）？
     —— 缺任何一根，后面所有参数扫描都是白费
② 再确认"电平"：接地是否真的接通（本次通电后灯亮 = 电有了，但不等于 A/B 通了）
③ 才开始扫参数（波特率/校验/地址/区域）
```

> 📌 **和"坑 4 的 I/O 地址"是同一类错误**：**在没有确认前置条件的情况下，就去调参数**。
> **排障不是"试更多的可能性"，而是"按依赖顺序逐层确认"。**

### 与"虚拟串口自测"的分工（两者都必要）

| 验到了什么 | 虚拟串口（10-24） | 真设备（10-28） |
|---|---|---|
| `RtuModbusDriver` 能读写串口 | ✅ | ✅ |
| j2mod 的 `SerialParameters` 用法对 | ✅ | ✅ |
| int16/uint16 + scale 解码 | ✅ | ✅ |
| **真实波特率/校验位** | ❌ 软件模拟，验不出 | ✅ **9600-8-N-1 确认** |
| **共地是否必需** | ❌ 虚拟串口不需要 | ✅ **实测：不共地不行** |
| **寄存器表（哪个 offset 是什么）** | ❌ 自己编的 | ✅ **实测 0=湿度、1=温度（与假设相反）** |
| **FC04 支不支持** | ❌ | ✅ **不支持（异常码 01）** |
| **真实延迟** | ❌ | ✅ **首轮 128ms / 后续 29~43ms** |

> **⇒ 虚拟串口证明了"代码没错"，真设备才证明"接法没错"。两者缺一不可。**