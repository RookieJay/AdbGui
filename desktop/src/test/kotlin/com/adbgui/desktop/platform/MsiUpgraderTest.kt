package com.adbgui.desktop.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MsiUpgraderTest {
    private var savedResourcesDir: String? = null

    @BeforeTest fun saveProperty() {
        savedResourcesDir = System.getProperty(PROPERTY)
    }

    @AfterTest fun restoreProperty() {
        val saved = savedResourcesDir
        if (saved == null) System.clearProperty(PROPERTY) else System.setProperty(PROPERTY, saved)
    }

    @Test fun parameters_passes_current_install_dir_as_installdir() {
        // jpackage 实际布局：<install>\app\resources（jcmd 实测 MSI 安装的运行 JVM：
        // compose.application.resources.dir=D:\Program Files\AdbGui\app\resources）
        val installDir = Files.createTempDirectory("pkg-install").toFile()
        val appDir = File(installDir, "app").apply { mkdirs() }
        File(appDir, "resources").mkdirs()
        File(installDir, "AdbGui.exe").createNewFile()
        System.setProperty(PROPERTY, File(appDir, "resources").absolutePath)
        assertEquals(
            "/i \"X:\\tmp\\new.msi\" INSTALLDIR=\"${installDir.absolutePath}\"",
            MsiUpgrader().buildParameters("X:\\tmp\\new.msi"),
        )
    }

    @Test fun parameters_quote_value_only_not_whole_property() {
        // 回归（2026-09-24）：安装目录含空格时，INSTALLDIR 的引号必须只包值
        // （INSTALLDIR="D:\a b"）。ProcessBuilder 会整段包引号（"INSTALLDIR=D:\a b"），
        // msiexec 判命令行非法退出码 1639 并弹帮助框（q1/q2 批处理实测）。
        val tmpRoot = Files.createTempDirectory("outer").toFile()
        val installDir = File(tmpRoot, "dir with space").apply { mkdirs() }
        val jpackageApp = File(installDir, "app").apply { mkdirs() }
        File(jpackageApp, "resources").mkdirs()
        File(installDir, "AdbGui.exe").createNewFile()
        System.setProperty(PROPERTY, File(jpackageApp, "resources").absolutePath)
        val params = MsiUpgrader().buildParameters("X:\\tmp\\new.msi")
        assertEquals(true, params.contains("INSTALLDIR=\"${installDir.absolutePath}\""))
        assertEquals(false, params.contains("\"INSTALLDIR="))
    }

    @Test fun parameters_handles_install_dir_named_app() {
        // 安装目录本身叫 "app"：<install_named_app>\app\resources
        //   - install_dir = <tmp>/app          （用户选的安装路径，恰好叫 "app"）
        //   - jpackage app 子目录 = <tmp>/app/app
        //   - resources = <tmp>/app/app/resources
        // 回退逻辑走到 "app" 子目录后停（只回退一层），所以 INSTALLDIR 应是 <tmp>/app
        val tmpRoot = Files.createTempDirectory("outer").toFile()
        val installDir = File(tmpRoot, "app").apply { mkdirs() }
        val jpackageApp = File(installDir, "app").apply { mkdirs() }
        File(jpackageApp, "resources").mkdirs()
        File(installDir, "AdbGui.exe").createNewFile()
        System.setProperty(PROPERTY, File(jpackageApp, "resources").absolutePath)
        assertEquals(
            "/i \"X:\\tmp\\new.msi\" INSTALLDIR=\"${installDir.absolutePath}\"",
            MsiUpgrader().buildParameters("X:\\tmp\\new.msi"),
        )
    }

    @Test fun parameters_omits_installdir_when_resources_dir_unset() {
        System.clearProperty(PROPERTY)
        assertEquals(
            "/i \"X:\\tmp\\new.msi\"",
            MsiUpgrader().buildParameters("X:\\tmp\\new.msi"),
        )
    }

    @Test fun parameters_omits_installdir_when_dir_is_not_a_real_install() {
        // dev/desktopRun 场景：resources.dir 指向 build\compose\tmp 这种没有 AdbGui.exe 的临时目录
        // → 不应把该目录当成安装位置传给 msiexec
        val tmp = Files.createTempDirectory("dev-run").toFile()
        val tmpResources = File(tmp, "resources").apply { mkdirs() }
        System.setProperty(PROPERTY, tmpResources.absolutePath)
        assertEquals(
            "/i \"X:\\tmp\\new.msi\"",
            MsiUpgrader().buildParameters("X:\\tmp\\new.msi"),
        )
    }
}

private const val PROPERTY = "compose.application.resources.dir"
