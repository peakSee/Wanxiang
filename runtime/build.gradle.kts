plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "top.wanxiang.app.runtime"
    resourcePrefix = "runtime_"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    implementation(project(":project-template"))
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // Android must use the AAR; the default JVM JAR does not package Android JNI libraries.
    implementation("com.github.luben:zstd-jni:${libs.versions.zstd.get()}@aar")
    implementation(libs.xz)
    implementation(libs.okhttp)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapi.bypass)
    implementation(libs.kadb)
    testImplementation(libs.junit)
}
