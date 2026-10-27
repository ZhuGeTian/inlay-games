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

## 0.5.3 修订：直接配置

用户要求取消首次确认。首次打开项目时直接添加更新源，不弹提示。使用独立的 autoConfigured 标记，使 0.5.2 已提示但未启用的用户也能完成配置；已配置后不重复添加，用户后来移除不强行恢复。保持其他仓库与全局更新检查/安装开关。删除通知组及所有提示动作，更新测试和安装说明，交付 0.5.3 ZIP。

- [x] 构建与平台测试，验证原提示版升级、持久化、重复添加、删除后重启与全局设置保留。
- [x] 最低支持 IDE 兼容检查，校验 ZIP 并复制至桌面。

0.5.3 验证：31 项插件测试通过；IC-232.10072.27 兼容检查通过；安装包中无更新通知组，启动注册和版本正确。未进行全部 IDE 版本的界面验收，未上传网站或 Marketplace。
