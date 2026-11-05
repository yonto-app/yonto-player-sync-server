// Kotlin and serialization follow the Yonto repo's gradle/libs.versions.toml; this build stands alone so it can be exported.
plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
}

group = "app.yonto"
version = "1.0.0"

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("app.yonto.sync.MainKt")
    applicationName = "yonto-sync-server"
    // sqlite-jdbc loads its native library, which JDK 25 warns about at every start unless allowed.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
    // The JDK's HTTP server reads its limits once per JVM, so each class gets the ones the server sets, not a fake's.
    forkEvery = 1
}
