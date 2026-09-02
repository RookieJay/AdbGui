import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}
dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.materialIconsExtended)
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
kotlin { jvmToolchain(21) }
compose.desktop.application {
    mainClass = "com.adbgui.desktop.main.MainKt"
    nativeDistributions {
        targetFormats(TargetFormat.Msi, TargetFormat.AppImage)
        packageName = "AdbGui"
        packageVersion = "1.0.0"
        // Bundles desktop/resources/ into the AppImage's app/resources/ — used to ship
        // platform-tools adb so users don't need adb on PATH. At runtime resolve via
        // System.getProperty("compose.application.resources.dir").
        appResourcesRootDir.set(layout.projectDirectory.dir("resources"))
        windows {
            dirChooser = true
            perUserInstall = true
            shortcut = true
            menu = true
            // To bundle adb later: add to appResourcesRootDir; v1 leaves unbundled (PATH/override).
        }
    }
}
