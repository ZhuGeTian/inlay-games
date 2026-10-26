import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins { kotlin("jvm"); id("org.jetbrains.intellij.platform") }
kotlin { jvmToolchain(21) }
repositories {
    // The pinned Studio macOS installer is published under install/, not ide-zips/.
    exclusiveContent {
        forRepository {
            ivy {
                name = "GoogleStudioInstaller"
                url = uri("https://dl.google.com/edgedl/android/studio/" + if (System.getProperty("os.name").startsWith("Mac")) "install" else "ide-zips")
                patternLayout { artifact("[revision]/[artifact]-[revision]-[classifier].[ext]") }
                metadataSources { artifact() }
            }
        }
        filter { includeModule("com.google.android.studio", "android-studio") }
    }
    intellijPlatform { defaultRepositories() }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(project(":core")) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
    }
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde) else intellijIdeaCommunity("2023.2.3")
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }
}
intellijPlatform {
    pluginConfiguration {
        name = "Moyu Collection"
        ideaVersion {
            sinceBuild = "232.10072.27"
            untilBuild = "262.*"
        }
    }
    buildSearchableOptions = false
    pluginVerification {
        ides {
            val verificationIde = providers.gradleProperty("verificationIdePath").orNull
            if (verificationIde != null) local(file(verificationIde))
            else create("IC", providers.gradleProperty("verificationIdeaVersion").getOrElse("2023.2.3"))
        }
    }
}
tasks.verifyPlugin {
    providers.gradleProperty("verificationPluginZip").orNull?.let { archiveFile = file(it) }
}
tasks.buildPlugin { archiveBaseName.set("inlay-gomoku") }
tasks.test { useJUnit() }
tasks.runIde {
    args(rootProject.file("demo").absolutePath)
    jvmArgs("-Didea.trust.all.projects=true", "-Dide.show.tips.on.startup.default.value=false")
}

