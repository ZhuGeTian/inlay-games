# 刷题显示行数 Implementation Plan

> **For agentic workers:** Use executing-plans inline for the already authorized changes.

**Goal:** 刷题默认紧凑展示，用户可调正文行数，未来功能遵循可调区域约定。

**Architecture:** PracticeSession 保存 visibleLines；PracticeActions 注册输入菜单；PracticeView 使用行数和视口高度计算分页预算，复用既有分页/原图功能。

**Tech Stack:** Java17, IntelliJ232 SDK, JUnit, Gradle。

- [x] Session: `SavedState.visibleLines = 6`，快照复制、loadState 校验 1–20、getVisibleLines / setVisibleLines；setter 越界抛 IllegalArgumentException，有 view 时 firstPage。
- [x] View: key 加入 visibleLines；`bodyHeight = lineHeight * Math.max(1, Math.min(session.getVisibleLines(), (area.height > 0 ? area.height : 600) / 2 / lineHeight))`。
- [x] Actions/XML: 添加 VisibleLines action，Messages 输入框整数 1–20，默认当前值，取消不变；菜单放在字号旁。
- [x] Integration tests: 长材料从 6→1 行高度下降、页数增加；逐页还原全部材料；底部 B/submit 可用且源码不变；XML往返恢复 3 行、非法值恢复默认、旧状态默认6；1行图像仍可绘制且按钮在inlay范围内。
- [x] Build: `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :plugin:test :plugin:buildPlugin '-PlocalIdePath=/Applications/IntelliJ IDEA CE.app' '-PpracticeBanksDir=/Users/lidangjuanyan.0815/Desktop/酒店比价/inlay-games/private/practice-banks'`，预期全通过且0.6.2包内保留两卷180题。
- [x] 更新使用文档、AGENTS未来区域约定；按现有流程发布0.6.2，核对公网文件字节数/SHA/更新源并记录证据。
