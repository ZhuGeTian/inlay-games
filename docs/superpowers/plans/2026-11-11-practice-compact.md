# Practice compact Implementation Plan

> Execute inline using the authorized design; no delegated work.

**Goal:** 补正横线，完整题目，紧凑选项和右键。
**Architecture:** PracticeAnswers可选stem补正保持身份；PracticeView增加同行选项片段及独立命中，题目不分页；Session统一题答阶段及一次性默认隐藏迁移；Actions提供专用基础菜单。
**Tech Stack:** Java / IntelliJ SDK / Gson / JUnit / Gradle。

- [ ] 给精确匹配的10道旧卷填词题补入专项题干，验证补正长度与来源，保持题库hash。
- [ ] 调整题目/答案布局与选项命中，题目高度至少容纳全部内容；解析仍按行数分页。
- [ ] 默认隐藏且仅迁移一次；独立题目右键菜单，保留Tools设置和Ctrl+D/G。
- [ ] 更新行为变化的回归测试，补上点击四个同行选项、窄宽度和真实题目横线检查；运行完整插件测试、构建及绘制预览。
- [ ] 发布0.6.6到网站/GitHub；更新文档、哈希、更新源并公网回读核验。
