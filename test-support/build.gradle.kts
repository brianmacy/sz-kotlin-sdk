// Repository provisioning and the truth set, shared by the SDK's tests and the examples.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":sdk"))
    implementation(libs.snakeyaml)
}
