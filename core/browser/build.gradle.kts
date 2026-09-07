plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(25)
    sourceSets {
        main {
            kotlin.srcDir("src/main/java")
        }
        test {
            kotlin.srcDir("src/test/java")
        }
    }
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":core:model"))
    testImplementation(libs.junit)
}
