plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":backend:shared:common"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:${libs.versions.junit.get()}")
}

kotlin {
    jvmToolchain(17)
}
