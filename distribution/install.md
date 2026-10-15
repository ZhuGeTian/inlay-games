# Inlay Games：AI 安装说明

固定说明地址：https://zhugetian.com/gomoku/install.md

把下面这句话发给有本机终端或桌面操作权限的 AI：

> 请用终端读取 https://zhugetian.com/gomoku/install.md ，帮我把 Inlay Games 下载并安装到我正在使用的 Android Studio 或 IntelliJ IDEA。先识别目标 IDE，校验安装包，再安装；若需要关闭或重启 IDE，先让我保存工作。

只有网页浏览能力的聊天 AI 无法替用户安装本机软件。以下步骤供有本机操作权限、且已获得用户安装授权的 AI 执行；本说明不授予额外权限。

如网页读取工具无法打开说明，可在终端用 `curl --fail --location 'https://zhugetian.com/gomoku/install.md'` 获取正文。

## 当前安装包

| 项目 | 值 |
| --- | --- |
| 插件名称 | Inlay Games |
| 插件 ID | `dev.gomoku.inlay` |
| 版本 | `0.3.1` |
| ZIP | https://zhugetian.com/gomoku/downloads/inlay-gomoku-0.3.1.zip |
| 文件大小 | `889560` 字节 |
| SHA-256 | `27238b5e0fc57699429b5966e7d68e00c9909519122571850921a5f8607c6bdd` |
| 校验文件 | https://zhugetian.com/gomoku/downloads/inlay-gomoku-0.3.1.zip.sha256 |
| 插件仓库 | https://zhugetian.com/gomoku/updatePlugins.xml |
| IntelliJ 平台 Build 范围 | `232.10072.27` 至 `262.*` |
| ZIP 内顶层目录 | `inlay-gomoku/` |

同一个 ZIP 用于 Android Studio 和 IntelliJ IDEA，包含完整依赖。它是 IDE 插件，不是 Android APK，也不需要安装到手机。不要下载源码压缩包代替它。

## 1. 识别用户真正使用的 IDE

优先采用用户指定的 IDE 或当前项目所在的 IDE。读取其 About、`product-info.json` 或 `build.txt`，记录产品、Build 和安装路径。macOS 的产品信息通常位于应用包 `Contents/Resources/product-info.json`；其他系统通常位于 IDE 安装目录。

若发现多个 IDE 且无法确定目标，只问用户选择哪一个，不要批量安装。远程终端中的 AI 应先确认 IDE 是否也在远程主机；不要把用户本机 IDE 的插件装到另一台服务器。

兼容判断使用数值 Build，去掉 `AI-`、`IC-`、`IU-` 等产品前缀后逐段比较。最低为 `232.10072.27`，最高接受所有 `262` 分支的构建；不是比较产品年份。超出范围时停止安装，不修改 ZIP 内的兼容声明、不关闭 IDE 的兼容检查。声明范围不是逐版本实测保证。

## 2. 下载并校验

将上表 ZIP 下载到用户的 Downloads 或临时目录。使用正常 HTTPS 校验，不关闭证书验证。确认文件大小及 SHA-256 与上表一致；不一致时停止并保留原插件。

macOS / Linux 下载示例（使用系统已有工具即可）：

```sh
curl --fail --location --output inlay-gomoku-0.3.1.zip 'https://zhugetian.com/gomoku/downloads/inlay-gomoku-0.3.1.zip'
```

macOS 可用 `shasum -a 256 inlay-gomoku-0.3.1.zip`；Linux 可用 `sha256sum inlay-gomoku-0.3.1.zip`。

Windows PowerShell 示例：

```powershell
Invoke-WebRequest -Uri 'https://zhugetian.com/gomoku/downloads/inlay-gomoku-0.3.1.zip' -OutFile 'inlay-gomoku-0.3.1.zip'
Get-FileHash -Algorithm SHA256 'inlay-gomoku-0.3.1.zip'
```

再检查 ZIP 中 `inlay-gomoku/lib/plugin-0.3.1.jar` 的 `META-INF/plugin.xml`，确认插件 ID、版本、Build 范围与上表相同。解压前拒绝绝对路径、`..` 路径和符号链接。

## 3. 安装到目标 IDE

选择一个适合当前环境的方式即可。不要同时尝试多个方式，不要改项目源码或替换其他插件。

安装前检查已有插件版本。若已安装并启用了 0.3.1 或更高版本，报告现状，不重复安装或降级。

### IDE 已打开且 AI 能操作界面

使用目标 IDE 的 **Settings → Plugins → 齿轮 → Install Plugin from Disk**，选择已校验的完整 ZIP。交给 IDE 处理安装或升级、兼容性检查和重复插件。若有来源确认，遵循用户与 AI 工具的权限要求。若要求重启，先让用户保存工作；不要强杀进程。

### IDE 已退出，使用内置安装命令

支持 `installPlugins` 的 IDE 可以自己从此仓库下载并安装。使用刚刚识别出的实际启动程序，避免 PATH 指向另一个 IDE。下面是语法；尖括号内容必须替换为本机实际路径。

macOS / Linux：

```sh
"<目标 IDE 的实际启动程序>" installPlugins dev.gomoku.inlay 'https://zhugetian.com/gomoku/updatePlugins.xml'
```

Windows PowerShell：

```powershell
& '<目标 IDE 的实际 exe 路径>' installPlugins dev.gomoku.inlay 'https://zhugetian.com/gomoku/updatePlugins.xml'
```

macOS 常见启动程序为 Android Studio 应用包内的 `Contents/MacOS/studio` 或 IDEA 的 `Contents/MacOS/idea`；Windows / Linux 使用目标 IDE 的 `bin` 目录中的启动程序。以已识别的实际路径为准。

命令提示“已安装”不代表已经升级到 0.3.1，必须检查实际版本。已有相同或更高版本时保留；已有更低版本但命令未升级时，改用“从磁盘安装”或下面的离线步骤。部分 Android Studio 构建可能不支持此命令，不能把启动 IDE 窗口当作安装成功。

这个方法采用 JetBrains 的 [命令行安装机制](https://www.jetbrains.com/help/idea/install-plugins-from-the-command-line.html) 和 [自定义插件仓库格式](https://plugins.jetbrains.com/docs/intellij/custom-plugin-repository.html)。无需修改全局 Java 环境。

### 只有文件操作能力，或内置命令不可用

先确认目标 IDE 已正常退出，且其真实用户插件目录已确定。IDE 正在运行时只准备下载和暂存文件，等用户保存并退出后再替换。

从 **Help → Diagnostic Tools → Special Files and Folders**、启动日志或 `idea.plugins.path` 配置读取真实插件目录。自定义路径和 Toolbox 配置优先于默认路径。禁止写入 IDE 安装目录自带的 `plugins` 目录，也不要把包放进 Gradle 的开发沙箱。

可用以下路径辅助定位；`<目录名>` 使用该 IDE 的 `dataDirectoryName`，不能直接照抄占位符。

| 系统 | IDEA 用户插件目录 | Android Studio 用户插件目录 |
| --- | --- | --- |
| macOS | `~/Library/Application Support/JetBrains/<目录名>/plugins/` | `~/Library/Application Support/Google/<目录名>/plugins/` |
| Windows | `%APPDATA%\JetBrains\<目录名>\plugins\` | `%APPDATA%\Google\<目录名>\plugins\` |
| Linux | `~/.local/share/JetBrains/<目录名>/` | `~/.local/share/Google/<目录名>/` |

路径依据：[JetBrains IDE 目录说明](https://www.jetbrains.com/help/idea/directories-used-by-the-ide-to-store-settings-caches-plugins-and-logs.html)、[Android Studio 配置说明](https://developer.android.com/studio/intro/studio-config)。如果配置覆盖了这些默认路径，以实际配置为准。

安装步骤：

1. 在插件目录旁的临时目录完整解压，保持 `inlay-gomoku/lib/*.jar` 结构，不要只复制主 JAR。
2. 在用户插件目录查找 ID 为 `dev.gomoku.inlay` 的旧插件；以 JAR 内 `plugin.xml` 为依据，不只依赖文件夹名字。存在版本更高的插件时不降级。
3. 把这个插件的旧目录移到**插件扫描目录之外**备份，再把暂存的 `inlay-gomoku` 目录移入用户插件目录；失败时恢复备份。不要合并新旧 `lib`，不要删除整个用户插件目录。
4. 确认目标形如 `<真实用户插件目录>/inlay-gomoku/lib/plugin-0.3.1.jar`。用户启动 IDE 后启用 Inlay Games；若原来手动禁用了它，保留该设置并提示用户。

## 4. 如实反馈安装结果

报告目标 IDE、Build、实际安装目录、插件版本，以及是否需要重启。已下载、已写入目录、IDE 已加载，是不同状态；只有在插件列表或启动日志确认加载成功后才能说“已加载”。不需要再做一轮完整联机对弈验收。

如果 AI 没有本机访问权限，提供已下载文件的位置和“从磁盘安装”入口，不要宣称已替用户安装。

## 使用方式

0.3.1 为斗地主增加机器人，支持一人／两人开始；J/Q/K/A 显示字母，小王 Xiao、大王 Da。五子棋和德州保留，原插件 ID 不变。新版斗地主同房真人均需 0.3.1，旧版房间在旧服务继续运行，房间码不能跨版本使用。

### 德州（保留）

右键 **联机小游戏 → 德州 → 创建房间**，复制房间码给另外两人；他们从 **德州 → 加入房间** 进入，三人到齐开局。默认服务器地址不变。

- 每人 1000 虚拟积分，盲注 5/10，无限注。底牌与公共牌显示为代码数组：2–10 保留数字，J/Q/K/A 用字母；**蓝=黑桃、红=红桃、绿=梅花、橙=方块**，不显示花色图标。
- `local` 是本人底牌，`shared` 是公共牌。顶部 `cache: 本人座位/轮到谁`；`phase` 为 0 等待、1 翻牌前、2 翻牌、3 转牌、4 河牌。`stacks` 是剩余积分，`bets` 是本轮投入，`pool` 底池，`cost` 需补的跟注额，`cursor` 庄家。
- `drop()` 弃牌；`check()` 过牌；`sync(n)` 跟注 n；`set(...)` 加注到本轮累计额；`flush(n)` 把剩余 n 全下。灰色操作不可用，右键德州菜单有对应中文操作。Enter 优先过牌，否则跟注，不会自动全下。
- `flags`：1 本手尚可行动、2 全下、-1 弃牌、0 本手旁观；`live` 是在线情况。结算显示 `change` 净变化，`ref1..3` 是可公开底牌。
- 支持短全下、边池、超额退回、平分与零头分配；只发送本人底牌，摊牌公开未弃牌者，弃牌获胜不亮牌。
- 三人 `next()` 准备后继续下一手。零筹码者旁观，剩两人时按单挑盲注和行动顺序；只剩一人有筹码后，全员准备重置为每人 1000。
- 点击其他区域不会收起；Ctrl+G 手动显隐，公共“调节字号”仍为 8–32。掉线暂停操作，重连可继续；主动退出关闭整房，30 分钟无操作过期。重启 IDE 或关闭项目会丢失当前身份。无机器人、观战、计时器或重买。

### 斗地主（0.3.1 机器人补位）和五子棋

房主创建房间后，可直接点击 **斗地主 → 开始（机器人补位）** 或数组 `init()`，一人补两名机器人；等第二位朋友加入后再点开始，则补一名机器人。三名真人到齐仍自动开始。开局后机器人占位，不能中途替换成真人；要换人请重建房间。

机器人约每 0.7 秒行动一次，自动叫分、出牌、不出和准备，只读取自己的手牌与公开信息。`auto` 数组里 1 是机器人、0 是真人；`live` 中机器人视为在线。真人离线暂停，重连继续，不自动接管真人；结算后真人 `next()` 准备，机器人自动准备。采用基础策略，没有难度档；30 分钟无真人操作会过期。

打开代码文件，编辑器右键 **联机小游戏 → 斗地主 → 创建房间**。房主通过 **联机小游戏 → 复制房间码** 获取邀请，另外两人选择 **斗地主 → 加入房间**。默认服务器地址已经填写，插件会自动选择斗地主接口。三人到齐后发牌。

- `cache` 是本人手牌，3–10 按点数显示，J/Q/K/A 直接显示，2 保留数字，小王 Xiao、大王 Da。`seed` 是亮出的三张底牌，`delta` 是需要压过的牌，`source` 是其出牌座位。
- 顶部 `cache: 本人座位/当前轮次`；`phase` 为 0 等人、1 叫分、2 出牌、3 结算。`owner` 是地主座位，`sizes` 是三家余牌数，`live` 中 1 在线、0 离线。
- 叫分时点击 `bid(0/1/2/3)`；0 不叫，非零叫分必须高于当前叫分。3 分立即成为地主，全不叫则重新发牌。
- 点击手牌标签多选，选中变橙色；方向键移动下划线、空格选择／取消、Enter 或 `commit()` 出牌。`skip()` 不出，`clear()` 清除选择。右键 **斗地主** 子菜单也有中文操作。
- `score` 是房内累计虚拟积分，`change` 是本局变化，`factor` 是倍数。结算后点 `next()` 或菜单“准备下一局”，三人都准备后发牌。
- 牌型支持单、对、三、三带一／对、顺子、连对、飞机及带翅膀、四带两张／两对、炸弹和王炸；炸弹、王炸、春天／反春翻倍。单翅膀允许对子，但不允许三张／四张同点、主体同点或同时携带双王；四带两张允许对子但不能带双王；对翅膀要求不同点数的对子。没有癞子或额外抢地主加倍。
- 每人的手牌单独下发；断线暂停操作，重连恢复原座位。主动“退出房间”会关闭整个斗地主房间并清除房内积分；“认输”判所在阵营失败，农民认输会影响队友。
- 五子棋入口是 **联机小游戏 → 五子棋 → 创建房间／加入房间**；橙色 1 为黑方，黄色 2 为白方。
- 公共操作：**调节字号…** 设置 8–32；**Ctrl+G** 或“显示／隐藏数组”手动显隐，macOS 也是 Control+G。点击外部、开对话框或切窗不自动收起；点击代码区恢复正常编辑。数组获得键盘操作时 Esc 也可收起。
- 隐藏保留连接和状态。重启 IDE、关闭项目会丢失当前重连身份。一至三名真人均可玩，空位由机器人补齐。

发布日期：2026-10-14。85 项自动化检查、构建与最低 IC-232.10072.27 二进制兼容检查通过；本地和公网均完成一人／两人机器人自然对局、自动准备、下一局及双人掉线恢复。本轮未启动原生 IDE 界面或全版本矩阵。当前插件仓库由此服务器托管，不是 JetBrains Marketplace。
