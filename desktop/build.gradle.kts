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
        // 版本唯一真相源是根 gradle.properties 的 version=…。MSI 的 ProductVersion 只接受
        // 数字段，预发布后缀（如 -rc.1）剥掉后再给 jpackage。
        packageVersion = project.version.toString().substringBefore('-')
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
// launcher sets compose.application.resources.dir=$APPDIR\app\resources at runtime
// (jpackage layout: everything lives under app/; verified via jcmd on an installed app),
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

// Generates /version.properties from the gradle project version (truth source: root
// gradle.properties). AppMeta.APP_VERSION reads it at runtime for the update check,
// so bumping the version never requires touching Kotlin sources.
val generateVersionProperties by tasks.registering {
    val versionFile = layout.buildDirectory.file("versionProperties/version.properties")
    val appVersion = project.version.toString()
    // 必须把版本声明为 input：否则只有 outputs 时 Gradle 见输出已存在即 UP-TO-DATE，
    // 改 gradle.properties 后不会重新生成，打进包里的还是旧 version.properties。
    inputs.property("appVersion", appVersion)
    outputs.file(versionFile)
    doLast { versionFile.get().asFile.apply { parentFile.mkdirs() }.writeText("app.version=$appVersion\n") }
}
tasks.processResources { from(generateVersionProperties) }
