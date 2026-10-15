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
