# 安装与更新

## 下载正确的安装包

打开 [GitHub Releases](https://github.com/ZhuGeTian/inlay-games/releases/latest)，在Assets下载`inlay-gomoku-0.5.1.zip`及同名`.sha256`文件。**不要下载Source code ZIP来安装插件，也不要解压后只安装一个JAR。** 同一个插件ZIP适用于Android Studio与IntelliJ IDEA。

0.5.1发行包大小967618字节，SHA-256：

```text
3c4bf653d588c2bb8af7e1914a650476d3071450c14aa9faa60296e20c1c7cf2
```

macOS运行`shasum -a 256 inlay-gomoku-0.5.1.zip`；Linux运行`sha256sum inlay-gomoku-0.5.1.zip`；Windows PowerShell运行`Get-FileHash inlay-gomoku-0.5.1.zip -Algorithm SHA256`。与发行页校验值不同就不要安装。

## 在IDE安装

1. 在About查看Build号，去掉AI-/IC-/IU-前缀后，应在232.10072.27到262.*范围内。声明范围不代表每个版本均实测；不要修改ZIP里的范围来绕过兼容检查。
2. 打开Settings（设置）→ Plugins（插件）→ 齿轮 → Install Plugin from Disk（从磁盘安装），选择完整ZIP。
3. 保存工作，按IDE提示重启；在插件列表确认Moyu Collection（摸鱼合集）0.5.1已启用。旧版Inlay Gomoku使用相同插件ID `dev.gomoku.inlay`，可覆盖升级。
4. 打开文本代码文件，在光标处右键“摸鱼合集”。创建或加入指定游戏房间；房间码从公共菜单复制。

如果快捷键被其他插件占用，可在IDE Keymap中查找“显示／隐藏内容”并调整绑定。macOS默认Control+G。游戏字号从“调节字号”设置，范围8～32；小说字号跟随编辑器。

斗地主、德州1人／2人时由房主点击“开始（机器人补位）”，三人自动开局；五子棋需要两名真人。不同游戏房间互不通用。新版德州同房真人需0.4.0或更新版本；斗地主协议2仍与0.3.1兼容。

## 首次启用更新提醒（0.5.2 起）

安装新包并打开项目后，IDE 会显示一次“启用摸鱼合集更新提醒？”通知。点击“启用更新提醒”，自动添加 `https://zhugetian.com/gomoku/updatePlugins.xml`，不需要手动复制地址。此操作不修改其他仓库或 IDE 的全局更新开关；如果已关闭插件更新检查，成功提示会提醒你去设置中开启。

选择“暂不启用”或关闭通知后不会重复提示；以后仍可在“设置 → 插件 → 齿轮 → 管理插件仓库”手动添加。已有更新源时不重复提示或添加，之后手动删除也不会被强行恢复。更新需服务器发布更高且兼容的版本，安装后可能需要重启。0.5.1 及更早版本需先手动升级一次或添加仓库。

## 阅读小说

右键“摸鱼合集 → 小说”导入本地TXT、打开书架或主动搜索下载。Ctrl+G控制最近使用的小说／游戏；阅读不需要创建房间。已安装独立Novel Inlay时，两者数据分开，合集不自动迁移原进度；可禁用旧插件避免两份小说同时显示。详见[阅读说明](novel.md)。

## 联机服务

默认地址为`wss://zhugetian.com/gomoku/ws`，插件自动选择对应游戏接口。也可在创建／加入对话框填写自己的基础地址，例如`wss://games.example.com/gomoku/ws`，见 [自建说明](../deploy/README.md)。公共服务无可用性承诺。

源码不上传：网络传输包含房间、游戏动作和恢复身份等游戏信息，服务端可获得连接IP。房间与身份只在内存中保留。关闭项目／重启IDE会丢失原身份；隐藏数组则保持连接。

## 交给有本机操作权限的AI

把 [AI安装说明](../distribution/install.md) 交给AI读取。在线固定地址为 [install.md](https://zhugetian.com/gomoku/install.md)。只有网页访问能力的AI不能安装本机插件；重启前应保存工作。GitHub发行包与该说明中的0.5.1包使用相同校验值。

安装后查阅：[详细规则](../README.md#支持的游戏) · [数组说明](array-guide.md) · [英文词典](glossary.md)。
