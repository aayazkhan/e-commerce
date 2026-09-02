plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:${libs.versions.junit.get()}")
}

kotlin {
    jvmToolchain(17)
}
