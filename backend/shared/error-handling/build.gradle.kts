plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":backend:shared:common"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:${libs.versions.junit.get()}")
}

kotlin {
    jvmToolchain(17)
}
