plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}
dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
kotlin { jvmToolchain(21) }
compose.desktop.application.mainClass = "com.adbgui.desktop.main.MainKt"
