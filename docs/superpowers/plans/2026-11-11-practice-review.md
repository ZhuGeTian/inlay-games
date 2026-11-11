# 行测复习 Implementation Plan

> Use executing-plans inline for the authorized changes.

**Goal:** 带来源参考答案、断点续做、同色加粗和跨试卷错题库。
**Architecture:** 原始题库身份不变；PracticeAnswers 加载精确匹配的静态补充数据，PracticeProgress 保存页号，PracticeMistakes 聚合已有错误记录，Session/Actions 打开原题。
**Tech Stack:** Java17 / IntelliJ SDK / Gson / JUnit / Gradle。

- [x] 对照原题与公开来源，保存60题核对记录（无法确认则留空），不复制机构长解析。
- [x] PracticeAnswers 读取受限静态资源，验证题号及答案集合，保留 paper.id；Paper.Question 增加来源；View/Progress 区分参考答案，答案页展示来源。
- [x] Progress.page 复制/归一化，View 构造恢复与布局回写，板块切换恢复对应页；选项只加粗且使用对应字体度量。
- [x] PracticeMistakes 按 paperId/questionId 去重历史错题与新可判分的已提交记录；后台加载、单卷失败隔离、点击原题复习。
- [x] 测试来源/题目身份、缺答案、旧记录补判、续做序列化、切换与首尾边界、实际渲染不变色和全局错题去重/恢复。
- [ ] 构建0.6.5保留两卷180题并新增225题专项，更新安装说明与更新源；推送GitHub/网站，公网回读包与元数据。

- [x] 配对225题专项与完整解析，核对跨页/水印/填空/图片符号及缺印D选项，加入独立题库和打包回归检查。
