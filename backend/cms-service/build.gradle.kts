plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization); alias(libs.plugins.ktor); application }
application { mainClass.set("io.ktor.server.netty.EngineMain") }
dependencies {
    implementation(project(":backend:shared:common")); implementation(project(":backend:shared:error-handling")); implementation(project(":backend:shared:security")); implementation(project(":backend:shared:service-support")); implementation(project(":backend:shared:kafka"))
    implementation(libs.ktor.server.core); implementation(libs.ktor.server.netty); implementation(libs.ktor.server.content.negotiation); implementation(libs.ktor.server.serialization); implementation(libs.ktor.server.call.id); implementation(libs.ktor.server.call.logging); implementation(libs.ktor.server.status.pages); implementation(libs.ktor.server.cors); implementation(libs.ktor.server.default.headers)
    testImplementation(libs.ktor.server.test.host); testImplementation(libs.testcontainers.postgresql); testImplementation(libs.testcontainers.kafka); testImplementation(libs.testcontainers.junit); testImplementation(kotlin("test")); testImplementation("org.junit.jupiter:junit-jupiter:${libs.versions.junit.get()}")
}
kotlin { jvmToolchain(17) }
tasks.test { useJUnitPlatform { if (!project.hasProperty("includeIntegration")) excludeTags("integration") } }
