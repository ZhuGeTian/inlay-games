# Study dashboard Implementation Plan

> Execute inline for the approved personal dashboard and existing AI preference.

**Goal:** 自建个人每日刷题统计、错题明细和AI复盘，插件可可靠同步。
**Architecture:** dashboard/下使用Node内置HTTP/SQLite/crypto，无前端构建依赖；PracticeActivity维护本地待同步事件，PracticeSync通过一次性配对码连接服务。复用原作答入口，核心游戏服务不变。
**Tech Stack:** Java17 IntelliJ SDK / Node22 node:sqlite / browser JavaScript / nginx / systemd。

- [x] 确认现有AI接口及部署入口，不输出凭据；设计个人登录和插件配对。
- [x] 实现SQLite事件、统计/详情、设备令牌、会话及受限AI复盘端点；测试时区、重复重试、未知答案、鉴权和失败边界。
- [x] 实现响应式看板、日期/板块/对错筛选、详情、AI复盘和设备配对；验证空态与实际数据流程。
- [x] 插件统一记录显式/翻页提交，持久化待同步队列，后台重试，密码库保存令牌；完整菜单加入看板连接及状态，题内菜单不变。
- [x] 构建保留3份题库；本地/服务器接口及浏览器验收；部署独立服务，备份恢复核验，验证健康及权限隔离。
- [ ] 更新文档、AI安装说明、版本下载和更新源；推送GitHub与网站并公网回读，交付看板地址、私人登录资料及插件包。
