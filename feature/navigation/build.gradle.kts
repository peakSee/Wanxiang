plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "top.wanxiang.app.feature.navigation"
    resourcePrefix = "navigation_"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25) }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":feature:components"))
    implementation(project(":feature:theme"))
    // LocalLiquidGlassBackdrop 的类型 LayerBackdrop 来自该库，类型推断需要它在 classpath 上
    implementation(libs.backdrop)
    implementation(project(":feature:home"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:workspace"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:developer"))
    implementation(project(":feature:custom_iteration"))
    implementation(project(":feature:browser"))
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
