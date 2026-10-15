plugins { kotlin("jvm"); kotlin("plugin.serialization"); `java-library` }
kotlin { jvmToolchain(21) }
dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib:1.8.20")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

