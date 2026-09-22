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
// Share core's recorded adb fixtures with desktop tests (VM state-machine tests read
// real dumpsys-package output to assert parsing + repository wiring end-to-end).
sourceSets {
    test {
        resources.srcDir(project(":core").projectDir.resolve("src/test/resources"))
    }
}
kotlin { jvmToolchain(21) }
compose.desktop.application {
    mainClass = "com.adbgui.desktop.main.MainKt"
    // 未设时 JVM 按物理内存自选（31.3GB → Initial 504MB / Max 7.8GB），G1 young gen 会随
    // 启动 burst 撑大且基本不回还（G1PeriodicGCInterval 默认 0）→ 任务管理器被顶到 1GB+。
    // 实测活跃堆仅 43MB（logcat 环 ≤50000 行）→ 512m 余量充足。见
    // docs/superpowers/specs/2026-09-16-page-driven-loading-and-logcat-publish-design.md §1.1.2。
    jvmArgs += listOf("-Xms64m", "-Xmx512m")
    nativeDistributions {
        targetFormats(TargetFormat.Msi, TargetFormat.AppImage)
        packageName = "AdbGui"
        packageVersion = "1.2.1"  // keep in sync with AppMeta.APP_VERSION
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
