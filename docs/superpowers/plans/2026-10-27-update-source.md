# 首次启动配置更新源

用户已确认：首次启动点一次，自动配置更新源，并交付新安装包。

**设计：** 独立的项目启动入口，在 IDE 主线程使用应用级 PropertiesComponent 记录已提示状态。未配置时只提示一次，点击“启用更新提醒”才将固定 HTTPS 地址加入 IDE 已保存仓库。已有仓库不重复添加，拒绝或关闭后不再提示，日后删除也不恢复。保留其他仓库和全局更新开关；关闭更新检查时给出设置提示。后续升级由 IDE 自带机制完成。

**范围：** 新增 UpdateRepositoryStartup、通知组和启动注册、平台集成测试；版本升至 0.5.2；补充安装说明；构建 ZIP 与校验文件。不更改游戏、小说、服务端或 Marketplace 审核中的版本。

- [x] 实现启动提示和两个通知动作（启用、暂不启用）。
- [x] 使用真实平台设置验证：点击前不写入；重复提示/重复地址；拒绝；删除后不恢复；保留其他仓库与关闭的更新开关；XML 持久化。
- [x] 运行插件测试、buildPlugin 与最低支持 IDE 的 Plugin Verifier。
- [x] 检查 ZIP 中插件 ID、版本、通知组及新增类，复制到桌面并生成 SHA-256。

验收命令：`JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :plugin:test :plugin:buildPlugin :plugin:verifyPlugin '-PlocalIdePath=/Applications/IntelliJ IDEA CE.app' '-PverificationIdePath=/Applications/IntelliJ IDEA CE.app'`。

验证结果：插件测试 32 项通过（含新增 6 项）；Plugin Verifier 对 IC-232.10072.27 报告 Compatible；ZIP 内注册、版本、类文件校验通过。未进行全版本 GUI 验收，未发布网站或 Marketplace 新版本。
