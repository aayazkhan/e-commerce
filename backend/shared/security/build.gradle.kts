plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":backend:shared:common"))
    implementation(project(":backend:shared:error-handling"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:${libs.versions.junit.get()}")
}

kotlin {
    jvmToolchain(17)
}
