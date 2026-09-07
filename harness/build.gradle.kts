plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "top.wanxiang.app.harness"
    resourcePrefix = "harness_"
    compileSdk = 37

    defaultConfig {
        minSdk = 29

        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:browser"))
    api(project(":runtime:browser"))
    implementation(project(":core:model"))
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))
    implementation(project(":core:security"))
    implementation(project(":core:network"))
    implementation(project(":runtime"))
    implementation(project(":tools"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.okhttp)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // 集成测试需要直接构建 in-memory Room 数据库（core:database 是 implementation 依赖不传递 Room）
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.androidx.room.ktx)
    testImplementation(libs.asm)
    testImplementation(libs.asm.commons)
    testImplementation(libs.asm.util)
    testImplementation(libs.asm.tree)
    testImplementation(libs.asm.analysis)
}
