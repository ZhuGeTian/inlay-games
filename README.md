# Inlay Games

在 **Android Studio / IntelliJ IDEA 的代码行间**玩联机五子棋、斗地主和德州。通过 Block Inlay 绘制紧凑的代码数组，游戏内容不写入源文件；Ctrl+G 手动显隐，字号可调。

[下载安装包](https://github.com/ZhuGeTian/inlay-games/releases/latest) · [安装步骤](docs/installation.md) · [界面数组说明](docs/array-guide.md) · [每个英文词的含义](docs/glossary.md)

## 支持的游戏

| 游戏 | 人数 | 功能 | 详细规则 |
| --- | --- | --- | --- |
| 五子棋 | 2名真人 | 15×15、黑先、无禁手、五连或更多获胜 | [五子棋规则](docs/gomoku.md) |
| 斗地主 | 1～3名真人 | 房主手动开始并用机器人补位；叫分、完整牌型、虚拟积分、下一局 | [斗地主规则](docs/doudizhu.md) |
| 德州 | 3名真人开局 | 无限注、固定5/10盲注、1000初始虚拟积分、边池、平分、淘汰后双人 | [德州规则](docs/texas.md) |

这些游戏仅使用房间内虚拟积分，没有充值、提现或现金交易。

## 安装与开局

1. 从 [Releases](https://github.com/ZhuGeTian/inlay-games/releases/latest) 下载 `inlay-gomoku-0.3.1.zip`。不要用 GitHub 自动提供的 Source code ZIP 安装插件。
2. IDE 内打开 **Settings → Plugins → 齿轮 → Install Plugin from Disk**，选择完整 ZIP，按提示重启。Android Studio 与 IDEA 使用同一安装包。
3. 打开文本代码文件，右键 **联机小游戏 → 对应游戏 → 创建房间**。
4. 房主通过 **复制房间码** 邀请朋友；朋友在相同游戏中 **加入房间**。
5. 斗地主一人／两人时，房主点 **开始（机器人补位）** 或 `init()`；三人到齐自动开始。

默认联机地址 `wss://zhugetian.com/gomoku/ws` 为项目提供的公共服务，也可填写自己的服务器地址。公共服务不提供可用性保证；它接收房间、游戏动作与连接IP，不接收项目源码。自建见 [服务端部署](deploy/README.md)。

0.3.1 斗地主使用协议2，同房真人需要0.3.1或更新版本。旧版斗地主房间位于独立服务，房间码不能跨新旧服务使用。五子棋和德州仍使用各自协议1。

## 怎么读懂界面

```kotlin
/* cache: 2/3; phase=2 */
val owner = 1; val round = 2
val cache = arrayOf(3, 3, J, A, Xiao, Da)
val sizes = intArrayOf(12, 6, 9)
val live = intArrayOf(1, 1, 1)
val auto = intArrayOf(0, 0, 1)
```

这是示意显示：你是2号，轮到3号，正在出牌；1号是地主。你的手牌有6张，3号是机器人，三个座位均在线。这些名称用于紧凑呈现游戏状态，**不要把示例粘进代码执行**。

- 数组按1、2、3号座位排列，不是永远把自己放在第一个。
- 顶部`cache: a/b`表示本人／行动座位；`val cache`才是斗地主手牌。
- `bids`是叫分记录，`bid(2)`是叫2分按钮；`skip`是连续不出次数，`skip()`是不出按钮。
- 斗地主：J/Q/K/A用字母，小王`Xiao`、大王`Da`；橙色表示已选牌，黄色是当前出牌。
- 德州：蓝/红/绿/橙表示黑桃/红桃/梅花/方块，J/Q/K/A用字母。

**所有字段、取值、颜色与按钮的逐项示例**见 [数组说明](docs/array-guide.md)；**英文原义及游戏中的含义**见 [英文对照表](docs/glossary.md)。

## 快捷操作

| 操作 | 效果 |
| --- | --- |
| Ctrl+G | 显示／隐藏；macOS也是Control+G |
| 点击代码区、切换窗口 | 不收起；点击代码区后键盘恢复编辑，再点数组恢复游戏键盘操作 |
| Esc | 数组处于游戏键盘操作状态时收起 |
| 调节字号 | 8～32，当前项目保存 |
| 斗地主方向键／空格／Enter | 移动光标／选牌或取消／出牌 |
| 德州Enter | 优先过牌，否则尝试跟注，不自动加注或全下 |
| 五子棋方向键／Enter | 移动落点／落子 |

隐藏后仍保持连接，机器人和其他玩家可以继续行动。每个项目同时一个房间。

## 构建与验证

需要JDK 21；真实WebSocket冒烟脚本使用Node.js 22或更新版本（内置WebSocket）。Gradle Wrapper已包含。

```bash
./gradlew :core:test :server:test :plugin:test :plugin:buildPlugin :server:installDist
```

Linux无图形桌面时，插件编辑器测试使用`xvfb-run -a ./gradlew ...`。默认下载最低SDK IDEA Community 2023.2.3，也可添加 `-PlocalIdePath="/absolute/path/to/IntelliJ IDEA CE.app"` 使用已安装的同版本SDK。

- 插件：`plugin/build/distributions/inlay-gomoku-0.3.1.zip`
- 服务端：`server/build/install/server/`
- 开发沙箱：`./gradlew :plugin:runIde`
- 本地服务：`./gradlew :server:run`

单个服务进程提供全部游戏路由。本地联机检查：

```bash
node scripts/smoke.mjs ws://127.0.0.1:8091/gomoku/ws
node scripts/ddz-smoke.mjs ws://127.0.0.1:8091/gomoku/ddz/v2/ws
node scripts/ddz-bots-smoke.mjs ws://127.0.0.1:8091/gomoku/ddz/v2/ws
node scripts/texas-smoke.mjs ws://127.0.0.1:8091/gomoku/texas/ws
```

兼容声明为IntelliJ Build **232.10072.27～262.***，以About里的Build为准。插件/核心字节码Java17、KotlinAPI1.8，服务端Java21。声明范围不等于每个IDE版本均实测。更多证据与边界见 [验证记录](docs/verification.md)。

```bash
./gradlew :plugin:verifyPlugin \
  -PverificationIdePath=/absolute/path/to/target-ide \
  -PverificationPluginZip=/absolute/path/to/inlay-gomoku-0.3.1.zip
```

仓库保留 [Android Studio正式版目标清单](docs/compatibility-targets.json) 和下载工具；清单是固定快照，不自动跟踪未来版本。未启用GitHub自动构建或全矩阵任务，以上命令可手动执行。

## 代码结构

| 目录 | 职责 |
| --- | --- |
| `core` | 五子棋/斗地主/德州规则、私有视图、协议、斗地主机器人 |
| `server` | 权威房间、身份恢复、轮次/版本校验、机器人调度、WebSocket传输 |
| `plugin` | Inlay渲染、菜单、键盘鼠标、WebSocket会话 |
| `scripts` | 真实网络冒烟、IDE下载辅助 |
| `docs` | 规则、字段、术语、安装、验证 |
| `deploy` | 通用自建示例；不含公共服务器的私有运维配置 |

## 已知边界

房间存于内存，服务重启会丢失；30分钟无活动会过期。恢复令牌只在插件内存里，重启IDE、关闭项目或主动退出后不能恢复原身份。真人掉线暂停操作，机器人不接管离线真人。斗地主/德州主动退出会关闭整个房间。

斗地主机器人是基础公开信息策略，无难度分级，开局后不能换成真人。德州没有机器人、重买或行动倒计时。当前无账号、观战、悔棋、排行榜、持久化棋谱。

## 许可证与贡献

项目原创代码采用 [MIT License](LICENSE)。第三方依赖及Gradle Wrapper保留各自许可证，见 [第三方说明](THIRD_PARTY_NOTICES.md)。

欢迎提Issue或PR。说明所用IDE的Build、插件版本、游戏种类和复现步骤；不要上传令牌、凭据、私人源码或含敏感信息的IDE日志。详见 [贡献说明](CONTRIBUTING.md)。
