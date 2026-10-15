plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(21) }
application { mainClass.set("dev.gomoku.server.MainKt") }
dependencies {
    implementation(project(":core"))
    implementation("io.ktor:ktor-server-netty:3.3.3")
    implementation("io.ktor:ktor-server-websockets:3.3.3")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.21")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

