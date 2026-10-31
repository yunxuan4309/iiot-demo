# OPC UA 配置与实测（CODESYS 软 PLC ↔ 客户端）

> 写于 2026-10-30。补 JD 资格 4 里 **OPC UA** 这一项（此前只有文档、零代码）。
> 结论：**CODESYS Control Win 的 OPC UA 服务端（4840）已跑通匿名读取**，客户端读到 PLC 里的实时变量。

---

## 一、一句话结论

**OPC UA 不是"又一个 Modbus"**：Modbus 只传数值（没有语义），OPC UA 传的是**带名字、类型、结构的信息模型** —— 客户端能"浏览"出 PLC 里有哪些变量，而不是靠人预先配好偏移地址。

实测读到的节点形如：

```
ns=4; |var|CODESYS Control Win V3 x64.Application.PLC_PRG.rTemp
```

---

## 二、实测输出（2026-10-30）

客户端：`tools/opcua_probe.py`（Python 3.14 + `asyncua` 2.1.0）

```
$ py -3 tools\opcua_probe.py
连接 opc.tcp://127.0.0.1:4840 ...
已连接

== 按名字找关键变量 ==
  rHumidity     ...PLC_PRG.rHumidity     = 61.20000076293945
  rTemp         ...PLC_PRG.rTemp         = 35.599998474121094
  wHumidityRaw  ...PLC_PRG.wHumidityRaw  = 612
  wTempRaw      ...PLC_PRG.wTempRaw      = 356
  xAlarm        ...PLC_PRG.xAlarm        = True
```

对照 CODESYS 在线视图：`rTemp=35.6`、`xAlarm=TRUE`、`wTempRaw=356` —— **完全一致**。
`Real` 是 32 位浮点，所以 `35.6` 读出来是 `35.599998474121094`，这是 Float32 的正常精度，不是错。

> 这条链路的意义：**同一份数据，既能用 Modbus TCP（Java 采集）读，也能用 OPC UA（任意客户端）读。** 工业现场选哪个，取决于对方系统说什么协议。

---

## 三、配置四步（实测有效的顺序）

| # | 在哪 | 做什么 |
|---|---|---|
| 1 | `Device` → 通信设置 → 顶部下拉选「设备」→ **改变Runtime安全策略** | 勾 **「允许匿名登录」** → 确定（写到 PLC；必要时重启 PLC） |
| 2 | `Application` 右键 → 添加对象 → **符号配置** | 勾 **「支持 OPC UA 特征」** → 添加 |
| 3 | 符号配置编辑器 | **勾选要暴露的变量**（我们勾了 `rTemp`/`xAlarm`/`rHumidity`/`wTempRaw`/`wHumidityRaw`）→ **编译** |
| 4 | `在线 → 登录`（下载） | 符号配置**随下载一起传送**；顶部灰字已提示："变更的符号配置将同下一个下载或在线修改传送" |

---

## 四、踩到的 5 个坑

### 坑 1 · 匿名被拒：`BadIdentityTokenInvalid`

- **现象**：客户端连上服务端，但 `ActivateSession` 被拒。
- **真因**：CODESYS 默认**不允许匿名** OPC UA 连接（设备用户管理是开着的）。
- **以后**：`通信设置 → 设备 → 改变Runtime安全策略 → 允许匿名登录`。

### 坑 2 · 带账号密码也不行：明文密码被拒

- **现象**：填了 `admin` + 密码，asyncua 打印 `Sending plain-text password`，服务端仍回 `BadIdentityTokenInvalid`。
- **真因**：客户端走的是 **SecurityPolicy = None** 通道，而 OPC UA 规范**禁止**在无加密通道上传用户名明文密码。
- **以后**：要么走匿名（演示），要么配 `Basic256Sha256` + 客户端证书并被服务端信任（生产）。

### 坑 3 · 勾了变量，服务器里还是什么都没有

- **现象**：符号配置里打勾、编译了，OPC UA 浏览不到任何 `PLC_PRG` 变量。
- **真因**：**符号配置没有下载到 PLC**。符号配置不是普通源码，改完必须**下载或在线上修改**才生效。
- **判据**：下载后 `Objects → DeviceSet → <plc> → Resources → Application → ...` 下才会长出变量节点。

### 坑 4 · Device 属性里找不到「OPC UA」页

- **现象**：找遍了 `Device` 的标签页，没有 OPC UA 设置。
- **真因**：SP22 把入口挪到了 **通信设置页顶部的「设备」下拉菜单** 里，叫「改变Runtime安全策略」。
- **以后**：别在标签页里找，去下拉菜单找。

### 坑 5 · 变量节点 ID 的规律

- **现象**：不知道节点 ID 怎么写。
- **规律**：`ns=4; |var|<PLC名>.Application.<POU>.<变量名>`（字符串型 NodeId）。
- **以后**：客户端用 `browse` 找名字即可，不必硬编码 ID；要硬编码就用上面这个格式。

---

## 五、安全边界（面试会被问）

| 项 | 我们怎么做 | 生产应该怎么做 |
|---|---|---|
| 认证 | **匿名**（`Anonymous_OPCUAServer`） | 关匿名，用 **用户名/密码 + 证书** |
| 通道 | SecurityPolicy **None** | **Basic256Sha256**（签名+加密） |
| 证书 | 未做 | 客户端证书导入 PLC 信任列表 |
| 暴露范围 | 只勾 5 个变量 | 按最小权限勾选，并配 **符号权限**（`Anonymous_OPCUAServer` 只给 view） |

> 一句话：**"能读通"和"能上线"是两件事**。匿名只是把链路验证出来，生产必须关掉。

---

## 六、面试口径

**Q：你 OPC UA 是怎么做的？**
> 「CODESYS 的运行时自带 OPC UA 服务端（4840）。我把 PLC 变量勾进**符号配置**并下载，客户端就能**浏览**到 `PLC_PRG.rTemp` 这类节点，读到 35.6℃、`xAlarm=True`。客户端我用 Python 的 asyncua 验证通道；节点 ID 是 `ns=4;|var|设备.Application.程序.变量`。」

**Q：Modbus 和 OPC UA 有什么区别？**
> 「**Modbus 没有语义**：只知道"第 3 个保持寄存器是 2 字节"，含义靠人配点表。**OPC UA 有信息模型**：变量带名字、类型、甚至结构，客户端可以自己浏览发现。类比的话，Modbus 是"快递柜编号"，OPC UA 是"带标签的快递"。」
> 「所以现场选型看对方：老设备/仪表基本只有 Modbus；MES、SCADA、云平台对接更偏 OPC UA。」

**Q：为什么还用 Modbus？**
> 「现场存量设备绝大多数只有 Modbus，而且它简单、开销小、跑在 485 上能拉几百米。两者不冲突：**南向用 Modbus 采设备，北向用 OPC UA / MQTT 给平台**。」

---

## 六点五、"服务端里到底有什么"——怎么"看"

一条命令就能把地址空间 Browse 成树（`tools/opcua_probe.py`）：

```
$ py -3 tools\opcua_probe.py

== 地址空间（Browse 出来的树）==
└── DeviceSet
    └── CODESYS Control Win V3 x64
        └── Resources
            └── Application
                ├── Programs
                │   └── PLC_PRG
                │       ├── rHumidity     Float    = 75.8
                │       ├── rTemp         Float    = 22.5    ← 真传感器温度
                │       ├── wHumidityRaw  UInt16   = 758     ← 原始寄存器
                │       ├── wTempRaw      UInt16   = 225     ← 原始寄存器
                │       └── xAlarm        Boolean  = False   ← PLC 判断结果
                └── Tasks
```

**这棵树证明了三件事：**

1. 层级 = 设备 → 资源 → 应用 → 程序 → 变量；
2. **只有勾进「符号配置」的变量才会出现**（没勾的不暴露，这本身就是最朴素的安全边界）；
3. 每个变量自带**名字 + 类型 + 当前值** —— 客户端不需要点表，这是 Modbus 做不到的。

> 想用 GUI 看，可以装 **UaExpert**：Add Server → `opc.tcp://127.0.0.1:4840` → Anonymous → 展开 `Objects`。
> 录屏用**终端树**更好看：画面干净、一眼看到"浏览"这个动作本身。

---

## 七、相关文件

| 文件 | 作用 |
|---|---|
| `tools/opcua_probe.py` | 客户端探针：连接、列命名空间、dump 节点树、按名字找变量并读值 |
| `codesys/iiot-demo-codesys.project` | PLC 工程（含符号配置） |
| `docs/PLC读设备-三种接入方式与取舍.md` | 为什么用 CODESYS 而不是三菱仿真器 |
