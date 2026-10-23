# 工业设备数据采集与在线监测 Demo

1 台真实 RS485 温湿度传感器 + N 台 Modbus 仿真设备 → Java 采集 → 时序库 → 在线监测页面。

## 状态

施工中（2026-10-23 起）

## 技术栈

- 设备侧：OpenPLC（IEC 61131-3，LD/ST/FBD）
- 协议：Modbus TCP / Modbus RTU（RS485）
- 采集与平台：Java 21 · Spring Boot · j2mod
- 存储：时序库（TDengine / QuestDB，可替换）

## 快速开始

（待补）

```powershell
.\mvnw.cmd -v
```
