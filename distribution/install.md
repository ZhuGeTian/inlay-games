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
| 版本 | `0.4.0` |
| ZIP | https://zhugetian.com/gomoku/downloads/inlay-gomoku-0.4.0.zip |
| 文件大小 | `900435` 字节 |
| SHA-256 | `ca7d0055a4ab2f2c3b1f8f4588d226fc2c35a6c45e0ab050a2d34187a9d3dfea` |
| 校验文件 | https://zhugetian.com/gomoku/downloads/inlay-gomoku-0.4.0.zip.sha256 |
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
curl --fail --location --output inlay-gomoku-0.4.0.zip 'https://zhugetian.com/gomoku/downloads/inlay-gomoku-0.4.0.zip'
```

macOS 可用 `shasum -a 256 inlay-gomoku-0.4.0.zip`；Linux 可用 `sha256sum inlay-gomoku-0.4.0.zip`。

Windows PowerShell 示例：

```powershell
Invoke-WebRequest -Uri 'https://zhugetian.com/gomoku/downloads/inlay-gomoku-0.4.0.zip' -OutFile 'inlay-gomoku-0.4.0.zip'
Get-FileHash -Algorithm SHA256 'inlay-gomoku-0.4.0.zip'
```

再检查 ZIP 中 `inlay-gomoku/lib/plugin-0.4.0.jar` 的 `META-INF/plugin.xml`，确认插件 ID、版本、Build 范围与上表相同。解压前拒绝绝对路径、`..` 路径和符号链接。

## 3. 安装到目标 IDE

选择一个适合当前环境的方式即可。不要同时尝试多个方式，不要改项目源码或替换其他插件。

安装前检查已有插件版本。若已安装并启用了 0.4.0 或更高版本，报告现状，不重复安装或降级。

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

命令提示“已安装”不代表已经升级到 0.4.0，必须检查实际版本。已有相同或更高版本时保留；已有更低版本但命令未升级时，改用“从磁盘安装”或下面的离线步骤。部分 Android Studio 构建可能不支持此命令，不能把启动 IDE 窗口当作安装成功。

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
4. 确认目标形如 `<真实用户插件目录>/inlay-gomoku/lib/plugin-0.4.0.jar`。用户启动 IDE 后启用 Inlay Games；若原来手动禁用了它，保留该设置并提示用户。

## 4. 如实反馈安装结果

报告目标 IDE、Build、实际安装目录、插件版本，以及是否需要重启。已下载、已写入目录、IDE 已加载，是不同状态；只有在插件列表或启动日志确认加载成功后才能说“已加载”。不需要再做一轮完整联机对弈验收。

如果 AI 没有本机访问权限，提供已下载文件的位置和“从磁盘安装”入口，不要宣称已替用户安装。

## 使用方式

0.4.0为德州增加胜率模拟机器人，支持一人／两人开始；斗地主机器人和五子棋保留。插件ID不变，可覆盖升级。新版德州同房真人均需0.4.0，使用独立协议2入口；旧德州房间留在旧服务，不跨版本加入。斗地主仍使用协议2，与0.3.1兼容。

### 德州胜率模拟机器人

房主创建房间后点击“德州 → 开始（机器人补位）”或`init()`，一人补两机器人、两人补一机器人；三名真人到齐自动开局。`auto`按1、2、3号座位显示：1为机器人、0为真人。开局后不能把机器人换成真人，换人需重建房间。

机器人只看自己的两张底牌与公共信息，每次随机模拟192种未知牌局，平局按份额计入权益，跟注只计算本人有资格赢的底池；再结合跟注成本选择合法行动。不会读取真实对手手牌或未来牌堆，不调用外部AI服务。它采用均匀对手范围和简化下注策略，不保证高手水平。每次动作至少间隔约0.7秒加计算时间，高负载会更慢。

每人1000虚拟积分，固定5/10盲注、无限注、边池、退款与平分。J/Q/K/A用字母；蓝=黑桃、红=红桃、绿=梅花、橙=方块。`local`为本人底牌，`shared`公共牌，`stacks`剩余积分，`bets`本街已下注，`pool`底池，`cost`需补跟注额，`cursor`庄家。

`drop()`弃牌，`check()`过牌，`sync(n)`额外跟注n，`set(...)`填写本街累计加注到多少，`flush(n)`全下剩余n。机器人自动准备，真人`next()`准备，全员准备才发下一手。零筹码者旁观；只剩一人有筹码后全员准备重置每人1000。

### 斗地主与五子棋

斗地主房主点击`init()`同样可一人／两人补机器人，三真人自动开始。牌面3–10、J、Q、K、A、2、小王Xiao、大王Da。`cache`为手牌，`owner`地主，`sizes`余牌数，`bids`叫分，`seed`底牌，`delta`当前牌，`auto`机器人标记。`bid(n)`叫分、`commit()`出牌、`skip()`不出、`clear()`清除选择、`next()`准备下一局。

五子棋两人开局，15×15，0为空、橙色1黑方、黄色2白方，黑先，无禁手，连续五子或以上获胜。

### 通用操作与完整说明

Ctrl+G手动显隐（Mac也用Control）；点击外部不收起，点击代码区释放游戏键盘操作；游戏获得键盘操作时Esc可收起。字号可调8–32，隐藏仍保持联机，显示不写入源码。

真人掉线暂停操作，机器人不接管真人；同一项目保留身份时重连。斗地主／德州主动退出关闭整房；30分钟无真人操作过期，机器人不续期。服务重启、IDE重启或关闭项目后不能恢复原身份。只有房间虚拟积分，没有充值或提现。

- 源码及安装入口：https://github.com/ZhuGeTian/inlay-games
- 游戏详细规则：https://github.com/ZhuGeTian/inlay-games/blob/main/docs/texas.md
- 所有数组含义：https://github.com/ZhuGeTian/inlay-games/blob/main/docs/array-guide.md
- 每个英文词的含义：https://github.com/ZhuGeTian/inlay-games/blob/main/docs/glossary.md
- 版本验证记录：https://github.com/ZhuGeTian/inlay-games/blob/main/docs/verification.md

发布日期：2026-10-16。未进行全版本IDE矩阵或高并发压力测试。此插件通过本网站和GitHub分发，不是JetBrains Marketplace条目。
