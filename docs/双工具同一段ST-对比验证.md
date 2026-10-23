# 同一段 IEC 61131-3 ST，在两个工具里都跑通了

> 实测：**2026-10-23**
> 目的：证明一句话 —— **「语言是国际标准，工具只是外壳」**。
> 相关：`docs/CODESYS/环境搭建与踩坑.md`（CODESYS 环境那一路的坑）

---

## 一、结论（一句话）

同一段 ST 代码

```iecst
xAlarm := rTemp > 80.0;
```

在 **OpenPLC Editor 4.3.2** 和 **CODESYS 3.5 SP22 Patch 4** 上，**都编译通过、都下载运行、都验证出 `rTemp = 90 → xAlarm = TRUE`**。

| | OpenPLC Editor 4.3.2 | CODESYS 3.5 SP22 Patch 4 |
|---|---|---|
| 语言 | **ST** | **ST** |
| 代码 | `xAlarm := rTemp > 80.0;` | 同（一字不差） |
| 变量 | `rTemp : REAL := 25.0` · `xAlarm : BOOL` | 同 |
| 编译结果 | **0 错误** | **0 错误 0 警告** |
| 运行目标 | 编译成 **ATmega2560 固件**，加载进内置模拟器 | 下载到 **CODESYS Control Win V3 x64**（Windows 软 PLC） |
| 动态验证 | 强制 `temp_check.rTemp = 90` → `xALARM = TRUE` | 写入 `rTemp = 90` → `xAlarm = TRUE` |
| 状态截图依据 | 代码行实时值 `xALARM TRUE := rTemp 90.000000 > 80.0` | 声明表 `rTemp 90` / `xAlarm TRUE` |

---

## 二、真实数字（可复算，不是形容词）

### OpenPLC 侧：加一个 ST 程序前后

| 指标 | 只有 `main`（LD） | 加 `temp_check`（ST） | 增量 |
|---|---|---|---|
| 固件大小 | 16206 B（占 6%） | **16526 B**（占 6%） | **+320 B** |
| 全局变量 | 1929 B（占 2%） | **1968 B**（占 3%） | +39 B |
| debug map | 3 leaves / **1** array | **5 leaves / 2** arrays | 多一个程序 |
| 调试变量数 | 6 | **10** | +4 |

> 📌 **+320 字节** = 一行 ST 编译出来的机器码量级。这就是"PLC 程序极小"的量化证据。

### CODESYS 侧

编译日志：**数据区最高用到 27656 B、代码区 79864 B**（各按 1 MB 预算）。

> 与 OpenPLC 的 16 KB 固件互相印证：**PLC 端只跑控制逻辑，存不下也不该存历史数据** —— 这正是"必须有上位机/时序库"的理由。

---

## 三、两个工具都踩到的**同一个概念坑**：程序不挂任务 = 静默不执行

| 工具 | 现象 | 为什么 | 怎么解决 |
|---|---|---|---|
| **CODESYS** | 默认就好 | 设备树里**可视化**地写着 `MainTask → PLC_PRG` | 无需操作 |
| **OpenPLC** | 编译通过、但 Console 报 `No instance found for program 'temp_check', skipping debug variable parsing.` | `Programs` 下新建的 POU **不会自动挂到 Task** | 打开 `Resource` → 在 **Instances** 表里加一行：`instance1 / temp_check / task0` |

**⭐ 这条很值钱**：它是"**编译通过 ≠ 会执行**"的实证。
而且两个工具的**表达方式不同、概念完全相同**：
- CODESYS：任务树 `MainTask` 下面挂 `PLC_PRG`
- OpenPLC：`Resource` 页面 `Instances` 表把 `Program` 绑到 `Task`

---

## 四、其它差异（顺手记下来）

| 维度 | OpenPLC Editor | CODESYS |
|---|---|---|
| 工程格式 | **纯文本**（`project.json` + `.ld` / `.st`），**git 友好、可手改** | **二进制 `.project`**，只能靠 IDE |
| 编译目标 | 真固件（ATmega2560 `.hex`，45.4 KB） | Windows 软 PLC 的机器码 |
| 运行时 | **内置模拟器**（加载固件直接跑） | `CODESYS Control Win`（**托盘手动启动**） |
| 支持语言 | LD / ST / FBD | LD / ST / FBD / **SFC / CFC** |
| 网络服务 | 固件内置 `ModbusSlave` / `opcua_server` / `s7comm_server`（**默认未监听 502**） | `CODESYS Modbus` 库（需组态主/从站） |
| 认证 | 无 | **强制设备用户管理**（密码策略：含特殊字符、不含用户名） |

> 📌 **纯文本工程的好处**：这次 OpenPLC 界面出 bug（删掉最后一行后 `+` 点不了），直接改 `project.json` 加回 `instance1` 就解决了 —— **GUI 卡住时文件还能救**。

---

## 五、面试口径（可直接背）

> 「我用**同一段 IEC 61131-3 的结构化文本**，在开源的 OpenPLC 和工业界主流的 CODESYS 上**都编译、下载、在线运行**成功了，两边都验证了『温度 > 80 → 报警位 TRUE』。
> 所以我会的不是某个厂商的软件，是**这套标准本身**。
>
> 顺带我发现一个共同点：**程序编译通过不等于会执行**，两个工具都要求把程序**挂到任务（Task）上**才会进扫描周期 —— CODESYS 是可视化的任务树，OpenPLC 要在 Resource 里配 Instances，不配就**静默不跑**，很容易误判成'代码写错了'。」

**为什么这句话有分量**：它回答的是面试官没明说但一直在测的那个问题 ——
**「你会的是工具，还是这套东西的本质？」**

---

## 六、证据文件在哪

| 内容 | 路径 |
|---|---|
| OpenPLC 工程配置 | `project.json`（`tasks` + `instances`） |
| LD 程序（梯形图） | `pous/programs/main.ld` |
| ST 程序（结构化文本） | `pous/programs/temp_check.st` |
| OpenPLC 固件产物 | `build/OpenPLC Simulator/` 下 `Baremetal.ino.hex`（45.4 KB，**已 gitignore**） |
| CODESYS 工程 | `codesys/iiot-demo-codesys.project`（215 KB，**已入库**） |
| CODESYS 环境踩坑 | `docs/CODESYS/环境搭建与踩坑.md` |
