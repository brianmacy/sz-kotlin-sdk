import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
}

group = "io.github.brianmacy"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.snakeyaml)
    testImplementation(project(":test-support"))
    testImplementation(kotlin("test"))
}

val senzingDir = rootProject.extra["senzingDir"] as File?
val jniLibraryDir = rootProject.extra["jniLibraryDir"] as File

/**
 * The cargo to run: $CARGO, else the first `cargo` on this build's PATH (resolved here because
 * a reused Gradle daemon would otherwise resolve it on the PATH it started with).
 */
fun cargo(): String =
    System.getenv("CARGO")
        ?: System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "cargo") }
            .firstOrNull { it.canExecute() }?.path
        ?: "cargo"

/** Builds the JNI library (native/) against the installed runtime's libSz. */
val cargoBuild by tasks.registering(Exec::class) {
    description = "Builds the sz-kotlin-jni library with cargo."
    workingDir = rootDir.resolve("native")
    commandLine(cargo(), "build")
    senzingDir?.let { environment("SENZING_DIR", it.path) }
    inputs.dir(rootDir.resolve("native/src"))
    inputs.files(rootDir.resolve("native/Cargo.toml"), rootDir.resolve("native/build.rs"))
    outputs.dir(jniLibraryDir)
}

/** The loader path variable that finds libSz beside nothing else: the runtime's lib directory. */
fun Test.useSenzingRuntime() {
    val runtime = senzingDir ?: throw GradleException(
        "No Senzing runtime: set SENZING_DIR or install one (see senzing-runtime.yaml)",
    )
    dependsOn(cargoBuild)
    systemProperty("java.library.path", jniLibraryDir.path)
    systemProperty("senzing.dir", runtime.path)
    val loaderPath = if (System.getProperty("os.name").startsWith("Mac")) "DYLD_LIBRARY_PATH" else "LD_LIBRARY_PATH"
    environment(loaderPath, runtime.resolve("lib").path)
}

tasks.test {
    useJUnitPlatform()
    useSenzingRuntime()
    // One engine per process: test classes run one at a time in a single JVM.
    maxParallelForks = 1
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

kover {
    reports {
        verify {
            rule {
                minBound(100)
            }
        }
    }
}

dokka {
    moduleName.set("sz-kotlin-sdk")
    dokkaSourceSets.configureEach {
        includes.from("Module.md")
    }
}
