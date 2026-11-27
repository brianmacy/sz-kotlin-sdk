import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

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
    // Interface defaults compile to JVM default methods only: Java callers use them directly,
    // with no DefaultImpls bridge classes.
    compilerOptions { jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY) }
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
        ?: System
            .getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .map { File(it, "cargo") }
            .firstOrNull { it.canExecute() }
            ?.path
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
    val runtime =
        senzingDir ?: throw GradleException(
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
    // An external resolution fixture set for EndToEndTest (skipped when unset; see end-to-end.yaml).
    System.getenv("RESOLUTION_FIXTURES_DIR")?.let { systemProperty("resolution.fixtures.dir", it) }
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

/** A `key: value` from android-build.yaml. */
fun androidSetting(key: String): String =
    rootDir
        .resolve("android-build.yaml")
        .readLines()
        .first { it.startsWith("$key:") }
        .substringAfter(":")
        .trim()

val androidRuntime = System.getenv("SENZING_ANDROID_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
val androidStubDir = rootDir.resolve("native/target/android-link-stub")

/** Generates the link-time stub libSz.so (only when no Android libSz is given). */
val androidLinkStub by tasks.registering(Exec::class) {
    description = "Builds a link-time stub libSz.so for Android from the runtime's C headers."
    onlyIf { androidRuntime == null }
    val runtime = senzingDir
    commandLine(
        rootDir.resolve("native/tools/android-link-stub.sh").path,
        runtime?.path ?: "",
        androidStubDir.path,
        androidSetting("apiLevel"),
    )
    doFirst { if (runtime == null) throw GradleException("No Senzing runtime headers: set SENZING_DIR") }
}

/**
 * Builds the JNI library for Android with cargo-ndk (needs ANDROID_NDK_HOME). It links against
 * the Android libSz in $SENZING_ANDROID_DIR/lib when set, else against a link-time stub
 * generated from the C headers (native/tools/android-link-stub.sh).
 */
val cargoBuildAndroid by tasks.registering(Exec::class) {
    description = "Builds sz-kotlin-jni for Android (cargo-ndk)."
    dependsOn(androidLinkStub)
    workingDir = rootDir.resolve("native")
    senzingDir?.let { environment("SENZING_DIR", it.path) }
    environment("SENZING_LIB_PATH", androidRuntime?.resolve("lib")?.path ?: androidStubDir.path)
    commandLine(cargo(), "ndk", "-t", androidSetting("abi"), "-P", androidSetting("apiLevel"), "build", "--release")
}
