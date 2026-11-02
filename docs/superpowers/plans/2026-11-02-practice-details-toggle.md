# Ctrl+D 切换辅助信息 Implementation Plan

> **For agentic workers:** Use executing-plans inline for this authorized change.

**Goal:** 顶部信息与底部操作栏一起隐藏，保留正文与操作途径。

**Architecture:** PracticeSession 保存 detailsVisible；PracticeView 统一 bodyTop 与隐藏布局、管理局部快捷键；PracticeActions/XML 添加显隐和翻页菜单。

**Tech Stack:** Java17, IntelliJ232 SDK, Gradle/JUnit。

- [x] SavedState 增加 `detailsVisible=true`，快照复制；提供读取、切换、当前编辑器可见判断与翻页代理，切换只 refresh。
- [x] View 注册 `Moyu.Practice.Details` 到当前 editor.contentComponent 并随 view 释放；显示时 bodyTop=2*lineHeight+4，隐藏时=4，隐藏不生成按钮，height=bodyTop+当前页内容高度+4；正文命中与绘制都使用 bodyTop。
- [x] Actions/XML 添加 Details（Control+D，Mac 同键），仅当前刷题编辑器启用；添加 PreviousPage/NextPage 复用 movePage 并限制页边界。
- [x] 集成测试覆盖正文/页码/作答不变、高度下降、旧按钮区域不响应、移位后选项命中、菜单翻页边界、状态序列化、局部快捷键注册与释放。
- [x] 版本改为0.6.3，执行带 practiceBanksDir 的 :plugin:test :plugin:buildPlugin；核对资源/版本/试卷/哈希，更新文档并同步服务器和 GitHub，公网回读验证。
