plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.ktlint) apply false
}

/**
 * The installed Senzing runtime's `er` directory: $SENZING_DIR, else the first candidate in
 * senzing-runtime.yaml that holds its marker file. Null when none is installed.
 */
val senzingDir: File? = run {
    val config = rootDir.resolve("senzing-runtime.yaml").readLines()
    val candidates = config.filter { it.trimStart().startsWith("- ") }.map { it.trim().removePrefix("- ").trim() }
    val marker = config.first { it.startsWith("marker:") }.substringAfter(":").trim()
    val explicit = System.getenv("SENZING_DIR")?.takeIf { it.isNotBlank() }
    (listOfNotNull(explicit) + candidates).map(::File).firstOrNull { it.resolve(marker).isFile }
}
extra["senzingDir"] = senzingDir

/** The cargo profile directory the JNI library is built into. */
extra["jniLibraryDir"] = rootDir.resolve("native/target/debug")
