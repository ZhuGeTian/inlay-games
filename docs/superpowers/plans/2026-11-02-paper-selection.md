# Paper Selection Implementation Plan

> Execute inline with executing-plans; user approved the paper-specific scope.

**Goal:** 选择 2025 或 2026 广东行测试卷，按原题型逐题练习。
**Architecture:** 私有 PDF 转换为标准题库资源；轻量目录合并内置题库与本地导入记录；已有 PracticeSession 和视图支持判断、多选。
**Tech Stack:** pdfplumber/Pillow、Java17、IntelliJ232、Gson/JUnit。

- [x] 从 PDF 定位 1–90 题、章节与四组资料。校验选项顺序，渲染所有图形与跨页题，输出 JSON 和抽查图片；不编造答案。
- [x] 扩展 PracticePaper/Progress/View/Actions 题型及选项校验、选择切换和全选判分，测试三种题型与旧格式。
- [x] 增加 PracticeCatalog：固定 classpath 目录、SHA 校验、资源大小限制；选择试卷合并和去重。目录读取不解码所有图片。
- [x] Gradle 显式 practiceBanksDir 资源打包；私人内容 gitignored。验证两份题库各90题、题型和资料映射、索引/hash以及进度独立。
- [x] 插件测试、构建0.6.1、复制桌面安装包；更新题库说明与验证记录，不推送公开仓库或网站。
