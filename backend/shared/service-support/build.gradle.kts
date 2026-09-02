plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":backend:shared:common"))
    implementation(project(":backend:shared:error-handling"))
    implementation(project(":backend:shared:kafka"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.lettuce)
    implementation(libs.kafka.clients)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:${libs.versions.junit.get()}")
}

kotlin { jvmToolchain(17) }
