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
        windows {
            dirChooser = true
            perUserInstall = true
            shortcut = true
            menu = true
            // To bundle adb later: add to appResourcesRootDir; v1 leaves unbundled (PATH/override).
        }
    }
}

// Bundles platform-tools adb (desktop/resources/adb/win/) into the AppImage's
// app/resources/adb/win/ so the distributed app needs no adb on PATH. The Compose
// launcher sets compose.application.resources.dir=$APPDIR\resources at runtime,
// which ResourceBundledAdbProvider reads to locate adb.exe.
// (appResourcesRootDir is a no-op in Compose 1.7.x — the new compose.resources {}
// system superseded it — so we copy into the built image dir directly.)
val copyBundledAdb by tasks.registering(Copy::class) {
    from(layout.projectDirectory.dir("resources/adb/win"))
    into(layout.buildDirectory.dir("compose/binaries/main/app/AdbGui/app/resources/adb/win"))
}
afterEvaluate {
    // Compose plugin registers packaging tasks in afterEvaluate, so wire finalizedBy here.
    tasks.findByName("packageAppImage")?.finalizedBy(copyBundledAdb)
    tasks.findByName("packageReleaseAppImage")?.finalizedBy(copyBundledAdb)
}
