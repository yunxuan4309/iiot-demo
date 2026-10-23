# 工业设备数据采集与在线监测 Demo

1 台真实 RS485 温湿度传感器 + N 台 Modbus 仿真设备 → Java 采集 → 时序库 → 在线监测页面。

> ⚠️ **个人学习 / 面试演示用，不是生产系统。** 这是实验室条件下的最小闭环，目的是验证**协议链路、数据模型与平台形态**；联锁、硬实时、停机变更审批这些真实产线的部分没有做，也不假装做过。

## 当前状态

| 部分 | 状态 |
|---|---|
| 仿真 Modbus TCP 从站（4 点位、可多设备、可随时停掉） | ✅ 可用 |
| 采集端探测工具（读寄存器 / 猜字序 / 读开关量 / 写线圈） | ✅ 可用 |
| OpenPLC 梯形图工程（LD 跑通） | ✅ 见 `pous/programs/main.ld` |
| 采集调度（`DeviceDriver` + 虚拟线程 + 质量码） | 🚧 进行中 |
| 时序库（TDengine / QuestDB） | ⬜ 未开始 |
| Spring Boot 接口 + ECharts 页面 | ⬜ 未开始 |
| 真实 RS485 传感器接入 | ⬜ 等硬件到货 |

## 点位表

| 点位 | 类型 | Modbus 区 / 偏移 | 惯用编号 | 功能码 | 读/写 |
|---|---|---|---|---|---|
| `temperature` | Float32（2 寄存器） | holding 0–1 | 40001–40002 | FC03 | 只读 |
| `runtime_hours` | UInt32（2 寄存器） | holding 2–3 | 40003–40004 | FC03 | 只读 |
| `motor_running` | BOOL（线圈） | coil 0 | 00001 | FC01 / **FC05 写** | 读写 |
| `alarm` | BOOL（离散输入） | input 0 | 10001 | FC02 | 只读 |

- `temperature` 故意用 **CDAB** 字序、`runtime_hours` 用 **ABCD** —— 现实里同一台设备混用两种字序很常见，采集端必须按点位配置，不能全局假设。
- 仿真从站监听 **1502**（不是 502）：<1024 端口在 Linux 要 root，Windows 上也容易被占。1502 是本项目的约定。
- 温度不是 `random()`：一阶低通 + 3 分钟周期漂移 + 0.5% 尖峰 + 每周期 ±20 ms 采样抖动。详见 [坑 0](<docs/pitfalls.md>)。

## 快速开始

需要 **JDK 21**。构建用 Maven Wrapper，**不需要本机装 Maven**：

```powershell
.\mvnw.cmd -DskipTests package
```

**1) 起仿真从站**（独立进程，Ctrl+C 即"设备离线"，用来演练断线）：

```powershell
collector\run-slave.cmd --port 1502
# 多台（同一端口、不同从站号 = RS485 网关挂多台表）
collector\run-slave.cmd --port 1502 --devices 10
```

**2) 读一遍，顺便看字序对不对**：

```powershell
collector\run-probe.cmd --port 1502
collector\run-probe.cmd --port 1502 --write-motor true   # 写线圈 FC05 并读回确认
```

探测工具会把同一帧寄存器按 **ABCD / CDAB / BADC / DCBA** 四种字序各解一遍，只有一种落在 10~100℃：

```
== temperature（Float32 = reg[0] + reg[1]）按四种字序解析 ==
  ABCD             3.5435e+12  （不像温度）
  CDAB                  62.08  <== 落在 10~100℃ 区间
  BADC             8.9116e+08  （不像温度）
  DCBA             1.5764e+34  （不像温度）
```

> 控制台显示中文需要 UTF-8：`run-slave.cmd` / `run-probe.cmd` 里已经 `chcp 65001` 并给 JVM 加了 `-Dstdout.encoding=UTF-8`。

**3) 演练设备离线**：在那个窗口按 Ctrl+C，再跑一次 `run-probe.cmd` —— 应当看到"连接被拒绝 + 该打 BAD/STALE 而不是补 0"的提示（exit 3）。

## 目录

```
├── project.json / pous\ / devices\ / datatypes\   OpenPLC 工程（IEC 61131-3，LD 已跑通）
├── collector\                                     采集模块（含仿真从站，可独立 main 运行）
├── platform\                                      Spring Boot + 页面（未开始）
├── docs\                                          架构图 / 抓包图 / 坑记录
├── build\                                         编译产物（不入库）
└── pom.xml                                        父 POM
```

## 技术栈

- 设备侧：OpenPLC（IEC 61131-3：LD / ST / FBD）
- 协议：Modbus TCP / Modbus RTU（RS485）
- 采集与平台：Java 21 · j2mod 3.4.0 · Spring Boot（后续）
- 存储：时序库（TDengine / QuestDB，可替换）

## 已经撞过的坑

见 [docs/pitfalls.md](<docs/pitfalls.md>)：假数据一眼假、寄存器字序、40001 vs offset 0、断线不是 0 而是"过期"、写操作不幂等……每条都留了实测输出和一句「我原以为是 X，其实是 Y」。
