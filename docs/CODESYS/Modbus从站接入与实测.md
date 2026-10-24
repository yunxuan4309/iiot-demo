# CODESYS 当 Modbus 从站：接入与实测

> 实测：**2026-10-24** · CODESYS **3.5 SP22 Patch 4**（`CODESYS Control Win V3 - x64` 软 PLC）· 采集端零代码改动

## 这件事的意义

```
CODESYS 软 PLC（真运行时，10ms 扫描）
   ├─ 自己的控制逻辑：xAlarm := rTemp > 80.0   ← ★ 决策在 PLC
   └─ 把 rTemp / xAlarm 暴露成 Modbus 从站
          ↓ Modbus TCP :1503
   Java 采集端（只读，不干预）
          ↓
      TDengine → 页面
```

**⇒ 同一个采集端，既能读我们自己写的仿真从站，也能读一台别人写的工业级 PLC。**
**换设备只改一个点表文件，代码一行不动** —— 这就是 `DeviceDriver` 抽象的价值，被真 PLC 验证了一次。

---

## 一、最终可用的配置（先抄这个）

### 1. 设备树

```
Device (CODESYS Control Win V3 x64)
├ PLC逻辑
│  └ Application
│     ├ 库管理器
│     ├ PLC_PRG (PRG)
│     └ 任务配置 → MainTask (IEC-Tasks) → PLC_PRG
└ Ethernet (Ethernet)                    ← 必须先加这个
   └ ModbusTCP_Server_Device             ← 再在它下面加 Modbus 从站
```

### 2. PLC 程序 `PLC_PRG`

```iecst
PROGRAM PLC_PRG
VAR
    rTemp  AT %QW0    : REAL := 25.0;   // 占 %QW0 + %QW1 两个字
    xAlarm AT %QX20.0 : BOOL;           // ★ 20 是设备分配的字节号，不是 0！
END_VAR

xAlarm := rTemp > 80.0;
```

### 3. Modbus 从站参数

| 字段 | 值 |
|---|---|
| 服务器端口 | **1503** |
| 保持寄存器 | 10 个（→ `%IW0..9`）**本工程没用** |
| 输入寄存器 | 10 个（→ `%QW0..9`）**rTemp 在这里** |
| 离散位区域 | ✅ 勾选 |
| 线圈 | 0 个（→ `%IX`） |
| 离散输入 | **1** 个（→ `%QB20`）**xAlarm 在这里** |
| 起始地址 | 全 0 |
| 看门狗 / 绑定到适配器 | 不勾 |

### 4. 采集端点表 `collector/src/main/resources/codesys.properties`

```properties
device.plc-01.portType=tcp
device.plc-01.host=127.0.0.1
device.plc-01.port=1503
device.plc-01.unitId=1
device.plc-01.periodMillis=1000
device.plc-01.points=temperature,alarm

device.plc-01.point.temperature.area=input      # FC04
device.plc-01.point.temperature.offset=0
device.plc-01.point.temperature.type=float32
device.plc-01.point.temperature.order=CDAB      # ★ 实测值
device.plc-01.point.temperature.unit=°C
device.plc-01.point.temperature.min=-40
device.plc-01.point.temperature.max=200

device.plc-01.point.alarm.area=discrete         # FC02
device.plc-01.point.alarm.offset=0
device.plc-01.point.alarm.type=bool
```

---

## 二、实测结果

### 探测（`run-probe --port 1503`）

```
== 输入寄存器（FC04 · 惯用编号 30001+ · PLC 侧 %QW） ==
  reg[0] = 0x3333   13107     ← 低位字
  reg[1] = 0x42B4   17076     ← 高位字（0x42B43333 = 90.1）
  reg[2..7] = 0

== 线圈（FC01 · PLC 侧 %IX） ==
  ❌ 读不到（该区域数量配成 0）

== 离散输入（FC02 · PLC 侧 %QX） ==
  [0]1                        ← TRUE

【输入寄存器】
  offset 0 + CDAB → 90.10  <== 合理
  offset 1 + ABCD → 90.00  （巧合，见坑 6）
```

### 采集（`run-collect --points codesys.properties`）

```
设备 plc-01@127.0.0.1:1503/unit=1  点位 2 个  周期 1000 ms
点表里的字序：temperature=CDAB  alarm=-
[15:10:33.133] plc-01  GOOD  temperature=90.10°C   alarm=true
[15:10:34.058] plc-01  GOOD  temperature=90.10°C   alarm=true
[15:10:35.065] plc-01  GOOD  temperature=90.10°C   alarm=true
```

---

## 三、⭐ 八个坑（全部实测踩过）

### 坑 1：Modbus TCP 设备**不在 "Modbus" 分组下**

「添加设备」对话框里，`Modbus` 分组只有 **`Modbus 串行端口` / `Modbus COM`**（串口设备）。
**ModbusTCP 系列是上下文相关的** —— 必须先给 PLC 加 **`以太网适配器`（Ethernet）**，
然后在 **Ethernet 节点下**右键添加，才会看到 `Modbus TCP Master` / `Modbus TCP Slave`。

> 对话框底部那行黄字 **「将被选设备作为最后一个子设备附加 / Device」** 就是当前挂载目标 ——
> **挂错了地方，选项就会消失**。

### 坑 2：中文翻译反直觉 —— 主站 = Client，从站 = Server

| 列表里的名字 | 描述 | 实际角色 |
|---|---|---|
| `Modbus TCP 主站` | "works as a Modbus **Client**" | 主动去读别人 |
| `Modbus TCP 从站设备` | "works as a Modbus TCP **Server**" | **被别人读** ★ 我们要这个 |

> **我们的架构里 Java 是主站/Client，所以 PLC 必须是从站/Server。**
> 按**英文和描述**判断，别按中文"主/从"的字面感觉。

### 坑 3：四个区域的**方向是反的**

设备属性页写的是 **PLC 视角**，不是 Modbus 视角：

| 设备页字段 | Modbus 对应 | PLC 侧地址 | 谁写 |
|---|---|---|---|
| 保持寄存器 | Holding Register (FC03/06) | `%IW` | **主站写**（PLC 读） |
| **输入寄存器** | Input Register (FC04) | `%QW` | **PLC 写**（主站只读）★ |
| 线圈 | Coil (FC01/05) | `%IX` | 主站写（PLC 读） |
| **离散输入** | Discrete Input (FC02) | `%QX` | **PLC 写**（主站只读）★ |

**规律：`%Q` 开头 = PLC 产出给外面读；`%I` 开头 = 外面写进 PLC。**
我们暴露 `rTemp` / `xAlarm`（都是 PLC 产出）→ **必须走 `%QW` / `%QX`**，
对应 Modbus 的**输入寄存器**和**离散输入**（不是保持寄存器和线圈！）。

### 坑 4：⭐⭐ **I/O 地址是设备分配的，不是你想当然写的**

**这是本场最难查的一个。**

我一开始让 `xAlarm AT %QX0.0`（第 0 字节）。但 I/O 映射表显示：

```
保持寄存器  %IW0   ARRAY[0..9] OF WORD   → 占字节 0~19
输入寄存器  %QW0   ARRAY[0..9] OF WORD   → 占字节 0~19
离散输入    %QB20  ARRAY[0..0] OF BYTE   → ★ 从第 20 字节开始！
  └ 离散输入[0]  %QB20   BYTE
     └ Bit0      %QX20.0  BOOL          ← 真正的通道地址
```

**因为前面的字区占了字节 0~19，位区就被分配到第 20 字节。**

**⇒ 正确地址是 `%QX20.0`，不是 `%QX0.0`。而且写错时：**
- ❌ **编译不报错**（`%QX0.0` 本身是合法地址）
- ❌ **运行不报错**
- ✅ 只是**永远读到 0**

> 📌 **CODESYS 官方文档明确建议**：
> *"An I/O address can also be linked with a variable via the AT declaration... **we recommend that you make the assignments only in the device editor**"*
> （用 AT 声明也能绑，但**推荐在设备编辑器里绑**，因为设备配置常变，写死的地址不会跟着变）
> 来源：[General information about I/O mapping](https://help.plc.abb.com/_cds_General%20information%20about%20IO%20mapping.html)

### 坑 5：**在线状态下整个工程只读**

现象：I/O 映射的"变量"列点不动、声明表改不了。
原因：**已登录（在线）**。CODESYS 在线时禁止改工程 —— 因为改的是工程，设备里跑的是已下载的程序，两者会不一致。

**解法：`在线` → `注销`（Logout）**，改完再登录下载。

### 坑 6：⭐ 字序歧义 —— **用 90.0 验不出来**

第一次 `rTemp = 90.0` 时，探测结果：

```
offset 0 + CDAB → 90.00   ← 合理
offset 1 + ABCD → 90.00   ← 也合理 ❗
```

两种解释都显示 90.00，**分不出来**。因为 `90.0 = 0x42B40000`，**低位字是 0**，
把它当"下一个偏移的低位字"也成立。

改成 **`90.1`**（= `0x42B43333`，低位字 `0x3333`）后立刻分明：

```
offset 0 + CDAB → 90.10   ← ✅ 正确
offset 1 + ABCD → 90.00   ← ✗ 不再匹配
```

**⇒ CODESYS（x86 小端）的 REAL 在 Modbus 里是 CDAB（低位字在前）。**
**验证字序必须用「带非零小数部分」的值**，否则会被巧合骗过。

### 坑 7：**Modbus 服务端只在"运行"状态监听**

配置完端口 1503 后，端口一直不监听。原因：
- `Application [停止]` → 服务端不启动
- 必须 **`调试` → `启动`**（或 F5）

而且**运行时服务本身是 `StartMode = Manual`**，开机不会自启 —— 需要托盘右键"启动 PLC"，
或管理员执行 `Start-Service "CODESYS Control Win V3 - x64"`（可顺便设成 Automatic）。

### 坑 8：I/O 映射的通道**必须展开到位类型**

`离散输入` 是 `ARRAY[0..0] OF BYTE`，这一层**不能绑 BOOL 变量**；
必须展开到 **`Bit0`（`%QX20.0`，BOOL）** 那一行才能绑。

---

## 四、现场排查顺序（复用价值最高）

```
1. 端口通不通？        Test-NetConnection 127.0.0.1 -Port 1503
2. 运行时不运行？      CODESYS Control Win V3 服务状态 + 11740 端口
3. 应用跑没跑？        设备树 Application [运行] / 状态栏"运行"
4. 四个区域哪个有数据？ run-probe --port 1503（会全探）
5. 字序对不对？        用带小数的值（90.1）看四种解释哪种唯一
6. 位/字区域地址？     看 I/O 映射表的"地址"列，照抄，别猜
7. Java 读不到？       先看 probe 通不通 —— probe 通说明 Java 侧配置问题（点表 area/offset/order）
```

> **规律：从下往上排除（网络 → 运行时 → 应用 → 映射 → 字序 → 点表）。**
> 我们这次就是卡在第 6 步（地址猜错），而前 5 步都是通的。

---

## 五、面试口径

> 「我用 **CODESYS** 的软 PLC 做过 Modbus 从站联调：PLC 里跑 `xAlarm := rTemp > 80.0`，
> 把 `%QW0` 的 REAL 和 `%QX20.0` 的 BOOL 暴露成 Modbus 的**输入寄存器**和**离散输入**，
> 采集端当普通 Modbus 主站读 —— **代码一行没改，只加了一个点表文件**。
>
> 中间踩了两个值得说的坑：
> **一是 I/O 地址不是自己写的，是设备映射表分配的** —— 我以为位区在 `%Q0`，实际在 `%QB20`
> （前面的字区占了 0~19），而且**写错编译不报错、运行时也不报错，只是永远读到 0**；
> **二是 REAL 的字序** —— 用 90.0 去验，CDAB 和 ABCD 两种解释都显示 90.00，分不出来；
> 换成 90.1 才验出是 CDAB。**所以验字序必须用带非零小数的值。**
>
> 这件事让我真正理解了一句话：**工业软件的服务端是旁观者，不是决策者** ——
> `alarm=true` 是 PLC 算出来的，采集端只是把它读回来、存下来、给人看。」
