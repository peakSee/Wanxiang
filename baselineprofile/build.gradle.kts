import com.android.build.api.dsl.TestExtension
import org.gradle.kotlin.dsl.configure

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

// Baseline Profile 生成模块（macrobenchmark）：
// 运行 `gradlew :app:generateBaselineProfile` 时，AGP 会构建 nonMinified 变体安装到
// 设备上，执行本模块的 collect 测试采集热点方法，合并写入 app/src/main/baseline-prof.txt。
// 该文件会随版本库提交；之后的每次构建由 AGP 自动把它打进 APK，
// 首次安装时经 profileinstaller 在 ART 上预编译，消除冷启动 JIT 现场编译开销。
//
// 默认使用已连接的物理设备采集（useConnectedDevices 默认为 true）。
// 若需要完全隔离的无人值守流程，可在下方追加 testOptions.managedDevices.localDevices
// 定义受管模拟器并设置 baselineProfile.useConnectedDevices = false。
extensions.configure<TestExtension> {
    namespace = "top.wanxiang.app.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 采集目标：主工程 app 模块
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.junit)
}
