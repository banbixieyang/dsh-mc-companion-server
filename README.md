# dsh-mc-companion-server

**服务端速通插件**：让 DSH 的 Minecraft 同伴在 26.3 及更新版本上做**真实操作**。

## 为什么需要它

mineflayer 是"无头客户端"——它模拟协议连进服务器。对 26.3 这种 PrismarineJS 尚未支持的版本，
协议数据是手工补的，于是会出现"Bot 看起来在挖、服务端根本不认"的错位（客户端挖完会自己把
方块标记成空气，服务端却忽略了这个包）。

这个模组把操作搬到**服务器内部**：挖掘用 `ServerPlayerGameMode#destroyBlock`，放置用
`Level#setBlockAndUpdate`，行走逐格推进。服务器说破了就是破了，不存在 ghost block。

## 命令（管理员权限，可通过 RCON 调用）

```
/dshmc dig     <player> <x> <y> <z>          真实破坏方块（走服务端掉落/工具判定）
/dshmc place   <player> <x> <y> <z> <block>  真实放置（生存模式消耗手持物品）
/dshmc walk    <player> <x> <y> <z>          服务端逐格行走（会跳过一格障碍）
/dshmc tp      <player> <x> <y> <z>          传送
/dshmc look    <player> <x> <y> <z>          转向目标（瞄准用）
/dshmc inv     <player>                      背包摘要
/dshmc count   <player> <item>               某物品数量
/dshmc state   <player>                      位置/血量/饥饿/模式/维度
/dshmc players                               在线玩家与假玩家
```

## 假玩家由 Carpet 提供

Carpet 的 `/player Bot spawn` 会生成一个**服务端真实玩家**（有血量、饥饿、背包、掉落），
本模组再对这个玩家执行上面的真实操作。两者配合，就是"服务端自己玩"。

## 一键部署

```bash
node scripts/setup-fabric-server.mjs <目标目录> --port 20000 --rcon-port 25575 --rcon-password 你的密码
```

脚本会下载：Fabric 26.3 服务端启动器、Fabric API `0.161.0+26.3`、Carpet `26.3`，并把本模组
（`build/libs/dsh-mc-companion-server-0.1.0.jar`）放进 `mods/`，同时生成开启 RCON 的
`server.properties`。

之后：

1. 把你现有服务器的 `world/` 复制进目标目录（脚本不会碰你的存档）；
2. `eula.txt` 改成 `eula=true`；
3. `java -Xmx4G -jar fabric-server-launch.jar nogui` 启动；
4. DSH 里把同伴切到服务端通道（`server.enabled=true, host=127.0.0.1, port=25575, password=…`），
   然后 `mc_server {action:"spawn"}` 生成假玩家。

## 构建

```bash
gradlew.bat build      # Linux/macOS: ./gradlew build
# 产物: build/libs/dsh-mc-companion-server-0.1.0.jar
```

- 26.3 / Java 25 / Fabric Loader 0.19.5 / Loom 1.17.21 / Fabric API 0.161.0+26.3
- 26.x 不混淆，本项目直接用官方 mojmap 名；构建插件用原生 `net.fabricmc.fabric-loom`。

## 已验证

- 编译通过，jar 可加载（`build/libs/dsh-mc-companion-server-0.1.0.jar`）；
- 命令参数与 DSH 侧 `mc_server` 工具一一对应（DSH 侧 6 项单元测试全绿）。
