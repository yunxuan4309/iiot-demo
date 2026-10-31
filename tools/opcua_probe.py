"""
OPC UA 探针 —— 连 CODESYS 的 OPC UA 服务端，把地址空间画成树，并读出变量当前值。

为什么要这个脚本：
  Modbus 只能按"寄存器编号"读，语义全靠点表；OPC UA 是**自描述**的 ——
  客户端连上去可以一路 Browse 下去，看到服务端暴露了哪些变量、什么类型、当前值多少。

用法：
  py -3 tools/opcua_probe.py                       # 默认 opc.tcp://127.0.0.1:4840
  py -3 tools/opcua_probe.py opc.tcp://192.168.1.10:4840

认证：CODESYS 默认拒绝匿名；若未开匿名，用环境变量给账号：
  $env:OPCUA_USER='admin'; $env:OPCUA_PASS='...'; py -3 tools/opcua_probe.py
"""
import asyncio
import os
import sys

from asyncua import Client, ua

# 顶层这些节点是服务器自带的诊断信息，演示时不需要看
SKIP_TOP = {"Server", "NetworkSet", "DeviceTopology"}

TARGETS = {"rTemp", "rHumidity", "xAlarm", "wTempRaw", "wHumidityRaw"}


async def render_tree(node, prefix="", depth=0, max_depth=6):
    """把节点树画成 └── 缩进的样子；变量附带类型和当前值。"""
    try:
        raw_children = await node.get_children()
    except Exception:  # noqa: BLE001
        return

    kids = []
    for child in raw_children:
        try:
            browse = await child.read_browse_name()
        except Exception:  # noqa: BLE001
            continue
        if depth == 0 and browse.Name in SKIP_TOP:
            continue
        kids.append((child, browse.Name))

    for index, (child, name) in enumerate(kids):
        is_last = index == len(kids) - 1
        connector = "└── " if is_last else "├── "
        try:
            node_class = await child.read_node_class()
        except Exception:  # noqa: BLE001
            continue

        if node_class == ua.NodeClass.Variable:
            try:
                data_value = await child.read_data_value()
                value = data_value.Value.Value
                variant = data_value.Value.VariantType
                type_name = variant.name if variant else "?"
            except Exception:  # noqa: BLE001
                value, type_name = "?", "?"
            print(f"{prefix}{connector}{name:<18} {type_name:<10} = {value}")
        else:
            print(f"{prefix}{connector}{name}")

        if depth < max_depth:
            inner = prefix + ("    " if is_last else "│   ")
            await render_tree(child, inner, depth + 1, max_depth)


async def collect_named(node, found, depth=0, max_depth=8):
    """顺手把关注的那几个变量收集起来，用于最后对账。"""
    if depth > max_depth:
        return
    try:
        children = await node.get_children()
    except Exception:  # noqa: BLE001
        return
    for child in children:
        try:
            browse = await child.read_browse_name()
        except Exception:  # noqa: BLE001
            continue
        if browse.Name in TARGETS:
            try:
                value = await child.read_value()
            except Exception as exc:  # noqa: BLE001
                value = f"<读值失败: {exc}>"
            found[browse.Name] = (child.nodeid, value)
        await collect_named(child, found, depth + 1, max_depth)


async def main():
    url = sys.argv[1] if len(sys.argv) > 1 else "opc.tcp://127.0.0.1:4840"
    print(f"连接 {url} ...")
    client = Client(url=url, timeout=10)
    user = os.environ.get("OPCUA_USER")
    if user:
        client.set_user(user)
        client.set_password(os.environ.get("OPCUA_PASS", ""))
    async with client:
        print("已连接\n")

        namespaces = await client.get_namespace_array()
        print("== 命名空间 ==")
        for index, uri in enumerate(namespaces):
            print(f"  ns={index}  {uri}")

        print("\n== 地址空间（Browse 出来的树）==")
        await render_tree(client.nodes.objects)

        print("\n== 关键变量对账 ==")
        found = {}
        await collect_named(client.nodes.objects, found)
        for name in sorted(found):
            node_id, value = found[name]
            print(f"  {name:<14} = {value}")
        print(f"  （共 {len(found)} 个）")


if __name__ == "__main__":
    # 控制台可能是 GBK，强制 UTF-8，否则框线字符和中文会炸
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    asyncio.run(main())
