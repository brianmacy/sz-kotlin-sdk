// The example programs: each has its own main and a run<Name> task (for example runLoadTruthset).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":sdk"))
    implementation(project(":test-support"))
}

val senzingDir = rootProject.extra["senzingDir"] as File?
val jniLibraryDir = rootProject.extra["jniLibraryDir"] as File

/** The example programs: each `<Name>.kt` in io.github.brianmacy.sz.examples has a `main`. */
val examples = listOf("AsyncEngine", "LoadTruthset", "Logging", "Search", "Why")

/** Runs against the installed runtime, as `useSenzingRuntime()` in sdk/build.gradle.kts does for tests. */
fun JavaExec.useSenzingRuntime() {
    val runtime =
        senzingDir ?: throw GradleException(
            "No Senzing runtime: set SENZING_DIR or install one (see senzing-runtime.yaml)",
        )
    dependsOn(":sdk:cargoBuild")
    systemProperty("java.library.path", jniLibraryDir.path)
    systemProperty("senzing.dir", runtime.path)
    val loaderPath = if (System.getProperty("os.name").startsWith("Mac")) "DYLD_LIBRARY_PATH" else "LD_LIBRARY_PATH"
    environment(loaderPath, runtime.resolve("lib").path)
    System.getenv("SENZING_ENGINE_CONFIGURATION_JSON")?.let { environment("SENZING_ENGINE_CONFIGURATION_JSON", it) }
}

examples.forEach { name ->
    tasks.register<JavaExec>("run$name") {
        group = "examples"
        description = "Runs the $name example."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("io.github.brianmacy.sz.examples.${name}Kt")
        useSenzingRuntime()
    }
}
