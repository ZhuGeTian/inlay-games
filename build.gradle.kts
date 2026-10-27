plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    // Newer build tooling requires IDE 233+, but our plugin also supports 232.
    id("org.jetbrains.intellij.platform") version "2.10.5" apply false
}
allprojects {
    group = "dev.gomoku"
    version = "0.5.3"
    repositories { mavenCentral() }
}
subprojects {
    val clientModule = name != "server"
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(if (clientModule) 17 else 21)
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(if (clientModule) org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 else org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            val kotlinApi = if (clientModule) org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8 else org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1
            apiVersion.set(kotlinApi)
            languageVersion.set(kotlinApi)
            if (clientModule) freeCompilerArgs.add("-Xjdk-release=17")
        }
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

