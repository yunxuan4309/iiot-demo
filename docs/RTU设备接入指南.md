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

## 七、虚拟串口自测（没硬件也能验证）

**原理**：装一对虚拟串口（`COM3 ↔ COM4`，如 com0com），
让**我们的仿真从站跑 RTU 模式挂在 COM3**，**采集端从 COM4 读** —— 硬件没到也能把整条链路验一遍。

| 步骤 | 命令 |
|---|---|
| 1 | 装 com0com（需要管理员），建一对 `COM3 ↔ COM4` |
| 2 | 从站 `collector\run-slave.cmd --port COM3 --rtu --baud 9600 --devices 1` |
| 3 | 探测 `collector\run-probe.cmd --com COM4 --baud 9600` |
| 4 | 采集 `collector\run-collect.cmd --points points-rtu.properties` |

**⇒ 这样"驱动代码是否正确"在硬件到货前就能证明；到货后只剩"接线 + 参数对一下"。**

> 当前状态：⏳ 需要先从站的 RTU 模式（`--rtu`），属于**从站改动** ——
> ⚠️ 用户已要求「从站不忙做」，所以这条**需要确认后再动**。
> 替代方案：用 **modbus 模拟器软件**（如 Modbus Slave / QModMaster）在虚拟串口上作从站，不碰我们的代码。

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

## 九、当前缺什么（2026-10-24 状态）

| # | 缺口 | 状态 |
|---|---|---|
| 1 | **`RtuModbusDriver`** | ✅ **已实现**（`ModbusSerialMaster` + `SerialParameters`，含 RS485 方向控制开关） |
| 2 | **点表支持串口参数** | ✅ **已实现**（`portType=rtu` + `comPort/baudRate/dataBits/stopBits/parity/rs485Mode`；模板 `points-rtu.properties` 已就绪） |
| 3 | **`ProbeMain --com`** | ✅ **已实现**（`--com/--baud/--parity/--data-bits/--stop-bits/--rs485-mode`，失败时打印 RTU 六步排查） |
| 4 | **一条 COM 口只能打开一次** | ✅ **已解决**：新增 `DriverRegistry`，按「物理链路」复用驱动 —— RTU 总线上所有 unitId 共用一条连接（半双工请求串行） |
| 5 | **虚拟串口自测（com0com）** | ⏳ **待做** —— 需要装 com0com（管理员）；用第三方 Modbus 模拟器作从站，**不碰我们的仿真从站** |
| 6 | TCP 回归 | ✅ **已验证**：重构后 10 台 600 点仍 **100%**（0 失败） |

> **⇒ 1~4 + 6 已完成，「设备到了简单了解就能接入」的代码侧已就绪；只剩第 5 项（硬件到前的额外保险）。**
