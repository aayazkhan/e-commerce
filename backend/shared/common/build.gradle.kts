plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // Runtime-only SLF4J binding: every service depends on shared:common, so this is the single
    // place that gets logback on every service's runtime classpath. Without it, SLF4J silently
    // no-ops all log.error/log.info calls (see "No SLF4J providers were found" at boot).
    runtimeOnly(libs.logback.classic)
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:${libs.versions.junit.get()}")
}

kotlin {
    jvmToolchain(17)
}
