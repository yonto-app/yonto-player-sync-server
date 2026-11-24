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
    implementation("org.postgresql:postgresql:42.7.15")
    testImplementation(kotlin("test"))
    testImplementation("io.zonky.test:embedded-postgres:2.2.3")
    testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.7.0"))
    // The library lists the Intel binaries alone; these are the ones an Apple-silicon Mac and an ARM Linux run.
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-darwin-arm64v8")
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-linux-arm64v8")
}

application {
    mainClass.set("app.yonto.sync.MainKt")
    applicationName = "yonto-sync-server"
    // sqlite-jdbc loads its native library, which JDK 25 warns about at every start unless allowed.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// The same suite against PostgreSQL, which `test` runs too: Yonto's own server keeps its data there, a self-hoster's in SQLite.
val postgresTest by tasks.registering(Test::class) {
    description = "Runs the tests against PostgreSQL, an embedded server each test class starts."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("yonto.sync.testDatabase", "postgres")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The JDK's HTTP server reads its limits once per JVM, so each class gets the ones the server sets, not a fake's.
    forkEvery = 1
}

tasks.test {
    dependsOn(postgresTest)
}
