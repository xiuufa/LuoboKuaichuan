// 萝卜快传 根构建脚本
// - 基于 Gazee 的构建配置：AGP 9.x（内置 Kotlin 编译）
// - compileSdk / targetSdk 使用新版 DSL
plugins {
    alias(libs.plugins.android.application) apply false
}
