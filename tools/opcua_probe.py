"""
OPC UA 探针 —— 连 CODESYS 的 OPC UA 服务端，把变量节点和当前值打出来。

用途：
  1. 验证 PLC 的 OPC UA 服务端在跑（默认 opc.tcp://127.0.0.1:4840）
  2. 确认「符号配置」里勾选的变量真的暴露出来了
  3. 读出 rTemp / xAlarm 的当前值，和 CODESYS 在线视图对账

用法：
  py -3 tools/opcua_probe.py                       # 默认 opc.tcp://127.0.0.1:4840
  py -3 tools/opcua_probe.py opc.tcp://192.168.1.10:4840

注意：CODESYS 默认允许匿名登录；如果 PLC 开了认证，连接会报 BadSecurityChecksFailed。
"""
import asyncio
import os
import sys

# 控制台可能是 GBK，强制 UTF-8 输出，避免中文/箭头字符炸掉
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')

from asyncua import Client, ua


async def walk(node, depth=0, max_depth=8):
    """递归 dump 节点树；Server / 标准诊断节点跳过，只看应用变量。"""
    try:
        children = await node.get_children()
    except Exception as exc:  # noqa: BLE001
        print(f"{'  ' * depth}! 读取子节点失败: {exc}")
        return
    for child in children:
        try:
            browse = await child.read_browse_name()
            node_class = await child.read_node_class()
        except Exception:  # noqa: BLE001
            continue
        name = browse.Name
        kind = node_class.name
        if node_class == ua.NodeClass.Variable:
            try:
                value = await child.read_value()
            except Exception as exc:  # noqa: BLE001
                value = f"<读值失败: {exc}>"
            print(f"{'  ' * depth}- {name:<28} {kind:<8} {child.nodeid}  = {value}")
        else:
            print(f"{'  ' * depth}- {name:<28} {kind:<8} {child.nodeid}")
        if depth < max_depth:
            await walk(child, depth + 1, max_depth)


async def main():
    url = sys.argv[1] if len(sys.argv) > 1 else "opc.tcp://127.0.0.1:4840"
    print(f"连接 {url} ...")
    client = Client(url=url, timeout=10)
    # CODESYS 默认拒绝匿名（BadIdentityTokenInvalid），账号走环境变量，别写进脚本
    user = os.environ.get("OPCUA_USER")
    password = os.environ.get("OPCUA_PASS")
    if user:
        client.set_user(user)
        client.set_password(password or "")
        print(f"使用账号 {user} 登录（密码取自环境变量，不打印）")
    async with client:
        print("已连接")

        namespaces = await client.get_namespace_array()
        print("\n== 命名空间 ==")
        for index, uri in enumerate(namespaces):
            print(f"  ns={index}  {uri}")

        objects = client.nodes.objects
        print("\n== Objects 下的节点树（只看变量与对象）==")
        await walk(objects, depth=0)

        print("\n== 按名字找关键变量 ==")
        nodes = await objects.get_children()
        stack = list(nodes)
        found = []
        while stack:
            node = stack.pop()
            try:
                browse = await node.read_browse_name()
            except Exception:  # noqa: BLE001
                continue
            if browse.Name in {"rTemp", "rHumidity", "xAlarm", "wTempRaw", "wHumidityRaw"}:
                try:
                    value = await node.read_value()
                except Exception as exc:  # noqa: BLE001
                    value = f"<读值失败: {exc}>"
                found.append((browse.Name, node.nodeid, value))
            try:
                stack.extend(await node.get_children())
            except Exception:  # noqa: BLE001
                pass

        if found:
            # 同一个变量可能从 DeviceSet 和 Server 两条路径都能走到，按 NodeId 去重
            unique = {(name, str(node_id)): value for name, node_id, value in found}
            for (name, node_id), value in sorted(unique.items()):
                print(f"  {name:<16} {node_id}  = {value}")
        else:
            print("  （没找到 rTemp/xAlarm 等变量）")
            print("  -> 大概率是还没加「符号配置」，或者加了但没下载到 PLC")


if __name__ == "__main__":
    asyncio.run(main())
