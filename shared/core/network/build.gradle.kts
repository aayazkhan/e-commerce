plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    @Suppress("OPT_IN_USAGE")
    wasmJs {
        browser()
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:core:common"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core.mp)
            implementation(libs.ktor.client.core.mp)
            implementation(libs.ktor.client.content.negotiation.mp)
            implementation(libs.ktor.client.serialization.mp)
        }
        val wasmJsMain by getting {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock.mp)
            implementation(libs.kotlinx.coroutines.test.mp)
        }
    }

    jvmToolchain(17)
}
