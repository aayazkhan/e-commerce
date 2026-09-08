pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // No repositoriesMode restriction (not FAIL_ON_PROJECT_REPOS): the Kotlin/Wasm Gradle plugin
    // registers its own Node.js distribution repository at the project level to download the
    // toolchain wasmJs browser dev-server/test tasks need -- legitimate tooling, not repo-governance
    // drift. PREFER_SETTINGS was tried first but silently drops project-declared Ivy-pattern repos
    // like that one, so dependency resolution still centers on the repositories below in practice.
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "commerce-platform"

include(
    ":backend:shared:common",
    ":backend:shared:error-handling",
    ":backend:shared:security",
    ":backend:shared:observability",
    ":backend:shared:database",
    ":backend:shared:redis",
    ":backend:shared:kafka",
    ":backend:shared:service-support",
    ":backend:api-gateway",
    ":backend:identity-service",
    ":backend:category-service",
    ":backend:catalog-service",
    ":backend:pricing-service",
    ":backend:media-service",
    ":backend:search-service",
    ":backend:inventory-service",
    ":backend:cart-service",
    ":backend:wishlist-service",
    ":backend:promotion-service",
    ":backend:order-service",
    ":backend:payment-service",
    ":backend:shipping-service",
    ":backend:refund-service",
    ":backend:checkout-service",
    ":backend:notification-service",
    ":backend:review-service",
    ":backend:recommendation-service",
    ":backend:analytics-service",
    ":backend:admin-service",
    ":backend:seller-service",
    ":backend:cms-service",
    ":backend:audit-service",
    ":backend:feature-flag-service",
    ":shared:core:common",
    ":shared:core:network",
    ":apps:sellerApp",
    ":apps:adminApp",
)
