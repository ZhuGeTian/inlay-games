# 第三方组件说明

项目原创代码采用根目录MIT许可证；这不改变第三方组件自身的版权与许可。构建时下载的组件和SDK以各自附带的LICENSE、NOTICE及元数据为准。

| 组件 | 用途 | 上游 |
| --- | --- | --- |
| Gradle Wrapper | 仓库包含的构建启动器及wrapper JAR；Apache License 2.0 | https://github.com/gradle/gradle |
| Kotlin / kotlinx.serialization | Kotlin语言运行时、序列化 | https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.serialization |
| kotlinx.coroutines / Ktor | 协程、服务端HTTP和WebSocket | https://github.com/Kotlin/kotlinx.coroutines / https://github.com/ktorio/ktor |
| Logback | 服务日志 | https://logback.qos.ch/license.html |
| IntelliJ Platform Gradle Plugin | 插件构建工具 | https://github.com/JetBrains/intellij-platform-gradle-plugin |
| IntelliJ Platform / Plugin Verifier | IDE SDK、插件兼容校验；IDE SDK不随本源码仓库分发 | https://github.com/JetBrains/intellij-community / https://github.com/JetBrains/intellij-plugin-verifier |
| JUnit | 自动化测试 | https://junit.org/ |

Gradle Wrapper的Apache 2.0许可全文见 [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt)；wrapper启动脚本保留上游版权声明。发行插件和服务端依赖JAR保留原有包内许可文件，传递依赖也遵循各自许可。完整依赖树可通过Gradle的dependencies任务查看。

## missNovel / Novel Inlay

小说阅读模块改编自 [datouggg/missNovel](https://github.com/datouggg/missNovel)，上游版本Novel Inlay0.8.2，固定提交`f94dcef71b9f98e05993507e3453fba562aab642`，采用Apache License2.0。

`plugin/src/main/java/dev/gomoku/novel/`中的上游派生文件，以及`plugin/src/test/java/dev/gomoku/novel/`中的NovelBookTest、NovelShelfTest、QishuxiaSourceTest保留Apache2.0许可；不改为项目根目录的MIT许可。修改包括命名空间与独立本地存储、Java17/IDE232适配、合集菜单和显隐协同。文件头标注来源和修改说明。

发行JAR附带`META-INF/licenses/missNovel-Apache-2.0.txt`许可证全文和`META-INF/NOTICE-missNovel.txt`来源说明。未随包分发第三方小说正文。在线书源属于第三方服务，不由本项目运营。
