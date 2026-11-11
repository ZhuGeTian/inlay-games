# 行测板块练习 Implementation Plan

> Use executing-plans inline for the authorized feature.

**Goal:** 当前题库按板块筛选，下一页依次展示答案和下一题。
**Architecture:** PracticeSections 提供精确题库分类；PracticeProgress 维护筛选与答案阶段，Session 使用独立板块进度；View 复用段分页并提供新的卡片翻页语义。
**Tech Stack:** Java17, IntelliJ232, Gradle/JUnit。

- [x] Paper 增加可选section，PracticeSections按已知hash映射或显式section分类；不改题库字节。
- [x] Progress 增加section/answerVisible，indices组合板块与错题，normalize不越界；advance按题目/答案阶段切换。
- [x] Session 持久化当前板块、独立进度；Actions菜单选择板块/整卷和段翻页；View将答案与题目分开显示，保留区域控制。
- [x] 测试两卷真实题量、阶段往返、末题边界、缺答案、不选不计错、隔离/恢复、长题完整阅读、隐显快捷键。
- [ ] 构建0.6.4，检查ZIP和题库；更新说明、发行包和更新源，发布服务器/GitHub并回读校验。
