# TDengine 部署与踩坑（WSL2 免 root 方案）

> 实测：**2026-10-24** · TDengine **3.3.6.13** Community · Ubuntu 22.04（WSL2）
> 相关：`docs/pitfalls.md`（协议层的坑）· `docs/OpenPLC/三语言进度与踩坑.md`（PLC 侧）

---

## 〇、结论先行

| 项 | 结果 |
|---|---|
| Windows 能不能原生装 TDengine **服务端** | ❌ **不能** —— 官方 CDN 上只有 `TDengine-client-*-Windows-x64.exe`，**`server` 的 Windows 包全 404** |
| 最终方案 | **WSL2 Ubuntu-22.04 + TDengine 免 root 部署** |
| 需要 sudo 吗 | ❌ **不需要**（端口 6030/6041 都 >1024，普通用户可监听） |
| 客户端怎么连 | **REST 接口 `http://localhost:6041`**（taosAdapter）→ Java 零依赖直连 |
| 实测吞吐 | 10 台 × 4 点 × 1 Hz，写入 **400 点 / 20 批 · 0 失败 · 0 丢弃** |

---

## 一、Windows 侧为什么装不了（取证过程）

```powershell
# 反复 404 的地址
https://www.taosdata.com/assets-download/3.0/TDengine-server-3.3.6.13-Windows-x64.exe   → 404
https://www.taosdata.com/assets-download/3.0/TDengine-server-3.3.0.0-Windows-x64.exe    → 404
# 同版本 Linux 包却是 200
https://www.taosdata.com/assets-download/3.0/TDengine-server-3.3.6.13-Linux-x64.tar.gz  → 200（178.5 MB）
# 只有客户端有 Windows 包
https://www.taosdata.com/assets-download/3.0/TDengine-client-3.3.6.13-Windows-x64.exe   → 200
```

而且官方下载页 HTML 里**连 "Windows" 字样都没有**。

> 📌 **教训**：先探 URL 再动手，别凭"印象中 TDengine 支持 Windows"就安排计划。

---

## 二、启用 WSL2（**这一步要重启，且容易漏**）

### 现状诊断（关键：分清 BIOS 和 Windows 功能）

```powershell
Get-CimInstance Win32_ComputerSystem | Select HypervisorPresent
Get-CimInstance Win32_Processor    | Select VirtualizationFirmwareEnabled
```

| 字段 | 含义 | 本例 |
|---|---|---|
| `VirtualizationFirmwareEnabled` | **BIOS** 里虚拟化开没开 | `True`（已开） |
| `HypervisorPresent` | **Windows hypervisor 是否真的在跑** | `False`（这就是 WSL2 起不来的原因） |

报错长相：`Wsl/Service/CreateInstance/CreateVm/HCS/HCS_E_HYPERV_NOT_INSTALLED`

### 三步修复（**缺一不可**）

```powershell
# ① 启用「虚拟机平台」和「Windows 虚拟机监控程序平台」（管理员）
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
dism.exe /online /enable-feature /featurename:HypervisorPlatform   /all /norestart

# ② 确保 hypervisor 随开机启动（VMware/VirtualBox 可能把它设成 off）
bcdedit /set hypervisorlaunchtype auto

# ③ 重启（bcdedit 的改动只在下次开机生效）
```

### 踩坑记录

| # | 坑 | 说明 |
|---|---|---|
| 1 | **只启用 `VirtualMachinePlatform` 不够** | 第一轮只跑了这条 + 重启，`HypervisorPresent` 仍是 `False` |
| 2 | **`HypervisorPlatform` 常被漏掉** | 很多 WSL2 教程只提 VirtualMachinePlatform |
| 3 | **`hypervisorlaunchtype` 可能是 `off`** | 装了 VMware / VirtualBox 的机器上很常见（它们要独占 VT-x）。**这条不改，功能全启用也起不来** |
| 4 | `bcdedit` 需要管理员 | 非管理员读都读不到（报 "The specified entry type is invalid"） |
| 5 | **判断"功能是否启用"不能只看 dism 的"操作成功完成"** | 要看 `Win32_OptionalFeature.InstallState` + `HypervisorPresent` |

**验证功能状态的免管理员办法**：

```powershell
Get-CimInstance Win32_OptionalFeature |
  Where-Object { $_.Name -in @('VirtualMachinePlatform','HypervisorPlatform','Microsoft-Windows-Subsystem-Linux') } |
  Select-Object Name, InstallState    # 1=Enabled 2=Disabled 3=Absent
```

---

## 三、TDengine 免 root 部署（不跑 install.sh）

### 为什么不用 `install.sh`

它往 `/usr/local/taos`、`/etc/taos`、`/var/lib/taos` 写东西，还注册 systemd 服务 —— **需要 root**。
而我们要的东西（`taosd` + `taosadapter` + 配置）全在包里，**解到 `$HOME` 直接跑**即可。

### 关键发现：包是**两层**的

```
TDengine-server-3.3.6.13-Linux-x64.tar.gz
├── install.sh / start-all.sh / stop-all.sh
├── README.md / connector/ / driver/ / examples/
└── package.tar.gz          ← ★ 真正的二进制在这里面
        ├── bin/  (taosd 96M, taosadapter 29M, taos 5.5M, ...)
        ├── cfg/  (taos.cfg, taosadapter.toml)
        └── inc/
```

```bash
mkdir -p ~/tdengine && tar -xzf TDengine-server-3.3.6.13/package.tar.gz -C ~/tdengine
```

### 缺的 `.so` 要自己建软链

`taos` 和 `taosadapter` 都不带 `libtaos.so`，库在 `driver/` 下且**只有带版本号的全名**：

```bash
D=~/TDengine-server-3.3.6.13/driver
mkdir -p ~/tdengine/lib
for n in taos taosnative taosws; do
  ln -sf "$D/lib${n}.so.3.3.6.13" ~/tdengine/lib/lib${n}.so
  ln -sf "$D/lib${n}.so.3.3.6.13" ~/tdengine/lib/lib${n}.so.3
done
export LD_LIBRARY_PATH=~/tdengine/lib
```

| 程序 | 缺的库 | 症状 |
|---|---|---|
| `taos`（CLI） | `libtaos.so` | `error while loading shared libraries` |
| `taosadapter` | `libtaosnative.so` | `not found`（启动后秒退） |

### taosd 配置（`~/tdengine/cfg/taos.cfg`）

```ini
fqdn                localhost
firstEp             localhost:6030
serverPort          6030
dataDir             /home/<user>/tdengine/data
logDir              /home/<user>/tdengine/log
tempDir             /home/<user>/tdengine/tmp
supportVnodes       2          # 单机 demo 用不到更多 vnode，省内存
numOfLogLines       200000
logKeepDays         1
```

启动（脱离终端，不占着 shell）：

```bash
setsid nohup ./bin/taosd -c ~/tdengine/cfg > ~/tdengine/log/taosd.stdout 2>&1 &
```

### taosAdapter 配置（`~/tdengine/cfg/taosadapter.toml`）—— **三个键最容易错**

```toml
debug = false
taosConfigDir = "/home/<user>/tdengine/cfg"   # ★ 让它从这读 taos.cfg
port = 6041

[log]
path = "/home/<user>/tdengine/log"            # ★ 键名是 path，不是 logPath
level = "info"
rotationCount = 3
keepDays = 2

[uploadKeeper]
enable = false                                # keeper 没跑，别上报

[monitor]
disable = true
```

启动：

```bash
setsid nohup env LD_LIBRARY_PATH=~/tdengine/lib \
  ./bin/taosadapter -c ~/tdengine/cfg/taosadapter.toml \
  > ~/tdengine/log/adapter.stdout 2>&1 &
```

**taosAdapter 的 4 个坑**：

| # | 现象 | 根因 |
|---|---|---|
| 1 | `panic: read /home/.../cfg: is a directory` | **`-c` 要文件路径**（`.toml`），不是目录 |
| 2 | `panic: make dir for /var/log/taos fail: permission denied` | 日志路径键写成了 `logPath` → 被静默忽略，回落到默认 `/var/log/taos` |
| 3 | 同上（改了 `logPath` 仍报） | 正确键是 **`[log] path`**，且**它先初始化日志、后读 taos.cfg** —— `taosConfigDir` 救不了日志路径 |
| 4 | 秒退且无有用日志 | 缺 `libtaosnative.so`（见上一节软链） |

---

## 四、验证（跨 Windows ↔ WSL 的真实链路）

```powershell
# 版本
curl -sS -u root:taosdata -d "select server_version()" http://[::1]:6041/rest/sql
# {"code":0,...,"data":[["3.3.6.13"]]}
```

> ⚠️ 注意 Windows 侧要用 **`[::1]`** 或 `localhost` + JVM 的 IPv6 参数，原因见下一节。

建库 + 超级表（**注意 `value` 是保留字**）：

```sql
CREATE DATABASE IF NOT EXISTS iiot;
CREATE STABLE IF NOT EXISTS iiot.telemetry
  (ts TIMESTAMP, val DOUBLE, quality NCHAR(8))
  TAGS (device NCHAR(32), point NCHAR(32), unit NCHAR(16));
```

写入（子表**首次写入自动创建**）：

```sql
INSERT INTO iiot.d_sim01_temperature USING iiot.telemetry
  TAGS ('sim-01','temperature','°C')
  VALUES (1729745611105, 25.63, 'GOOD');
```

**实测结果**（10 台 × 4 点 × 10 轮）：

```
TDengine 写入: 400 点 / 20 批 · 失败 0 批 · 队列丢弃 0 点
select count(*) from iiot.telemetry        → 400
show iiot.tables                           → 40 张子表（10 台 × 4 点）
```

---

## 五、三个"数据层"的坑（**JD 面试可直接讲**）

### 坑 A：`value` 是 TDengine 保留字

```sql
-- ❌ syntax error near "value double, ..."
CREATE STABLE t (ts TIMESTAMP, value DOUBLE) ...
-- ✅
CREATE STABLE t (ts TIMESTAMP, val DOUBLE) ...
```

### 坑 B：⭐ **TDengine 存的是 UTC，查询返回带 `Z` 的 ISO**

写入本地时间 11:23 的数据，查出来是：

```json
"ts": "2026-10-24T03:23:19.306Z"     ← UTC！本地是 UTC+8
```

⇒ **前端画图必须做时区转换**，否则整张图偏 8 小时。
⇒ 采集端写入时**用 epoch 毫秒（数字）**，不要用字符串时间，避免"服务器时区 vs 客户端时区"的歧义。

> 这正是计划书里「坑 6 · 设备时钟漂移/时间基准」的现实版本。

### 坑 C：⭐ **WSL2 的端口转发只监听 `[::1]`（IPv6 回环）**

```
Windows 侧 netstat:   [::1]:6041  LISTENING
localhost 解析顺序:   ::1  →  127.0.0.1
http://127.0.0.1:6041 → 连不上
http://[::1]:6041     → 200 ✅
```

**Java 的 HttpClient 默认偏好 IPv4** ⇒ 连 `127.0.0.1` ⇒ `ConnectException`。
（`curl.exe` 会自动回退到 IPv6，所以**curl 能通不代表 Java 能通**。）

**两个修法（任选）**：

| 修法 | 命令 |
|---|---|
| ✅ 给 JVM 加参数（推荐，URL 保持可读） | `-Djava.net.preferIPv6Addresses=true` |
| 或 URL 直接写 IPv6 字面量 | `--td-url http://[::1]:6041` |

> 📌 **排查方法论**：**"客户端 A 能连、客户端 B 连不上" 第一时间查地址族（IPv4/IPv6）和监听地址**，不要怀疑服务端。

---

## 六、运维（日常怎么起停）

| 事项 | 做法 |
|---|---|
| **启动** | `device\tdengine\start-tdengine.cmd`（双击，幂等，会等到 6041 可用再报 OK） |
| **停止** | `device\tdengine\stop-tdengine.cmd` |
| **会不会开机自启** | ❌ 不会（WSL 不自启，TDengine 也不是系统服务） |
| **数据在哪** | WSL 里 `~/tdengine/data`（ext4，**重启不丢**） |
| **日志在哪** | `~/tdengine/log/`（`taosdlog.0` / `adapter.stdout`） |
| **内存上限** | `.wslconfig` 里 `memory=4GB`（保护 Windows，避免跟 IDEA/浏览器抢） |

**`.wslconfig`（`C:\Users\<你>\.wslconfig`）**：

```ini
[wsl2]
memory=4GB
processors=8
swap=2GB
```

---

## 七、面试口径（可直接背）

> 「TDengine **没有 Windows 服务端包**，所以我把它装在 **WSL2 的 Ubuntu** 里，**不用 root**：解压到 home、自己建 `.so` 软链、写好 `taos.cfg`/`taosadapter.toml`，`taosd` 和 `taosAdapter` 以普通用户跑在 6030/6041。
> 采集端（Windows 上的 Java）通过 **REST 6041** 写入，**零第三方依赖**，批量攒 500 点或 500ms 发一次 —— 实测 100 台规模 400 点/秒 全量写入、0 失败。
> 中间踩了两个值得说的坑：**一是 `value` 是保留字**；**二是 WSL2 的端口转发只监听 `[::1]`**，JVM 默认偏好 IPv4 会连不上，得加 `-Djava.net.preferIPv6Addresses=true` —— 这种"curl 能通、Java 不通"的问题，第一时间要看地址族，而不是怀疑服务端。
> 另外 TDengine 按 **UTC** 存时间戳，查询返回带 `Z` 的 ISO 串，**前端必须做时区转换** —— 这就是工业时序数据的"时间基准"问题。」
