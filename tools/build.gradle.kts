// Code generator: SzFlags.kt from the runtime's szflags.json. Run with
//   ./gradlew :tools:generateFlags
// (SENZING_DIR, or the first runtime in senzing-runtime.yaml).
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("GenerateFlagsKt")
}

val senzingDir = rootProject.extra["senzingDir"] as File?

tasks.register<JavaExec>("generateFlags") {
    description = "Regenerates sdk/.../SzFlags.kt from the installed runtime's szflags.json."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("GenerateFlagsKt")
    val runtime = senzingDir
    doFirst { if (runtime == null) throw GradleException("No Senzing runtime: set SENZING_DIR") }
    args(
        runtime?.resolve("sdk/szflags.json")?.path ?: "",
        rootDir.resolve("sdk/src/main/kotlin/io/github/brianmacy/sz/SzFlags.kt").path,
    )
}
