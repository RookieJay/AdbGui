package com.adbgui.desktop.platform

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

    @Test fun command_passes_current_install_dir_as_installdir() {
        // jpackage 实际布局：<install>\app\resources（jcmd 实测 MSI 安装的运行 JVM：
        // compose.application.resources.dir=D:\Program Files\AdbGui\app\resources）
        System.setProperty(PROPERTY, "D:\\Program Files\\AdbGui\\app\\resources")
        assertEquals(
            listOf("msiexec", "/i", "X:\\tmp\\new.msi", "INSTALLDIR=D:\\Program Files\\AdbGui"),
            MsiUpgrader().buildCommand("X:\\tmp\\new.msi"),
        )
    }

    @Test fun command_handles_install_dir_named_app() {
        // 安装目录本身叫 "app"：<install>\app\app\resources —— 第二层 app 不回退
        System.setProperty(PROPERTY, "D:\\Tools\\app\\app\\resources")
        assertEquals(
            listOf("msiexec", "/i", "X:\\tmp\\new.msi", "INSTALLDIR=D:\\Tools\\app"),
            MsiUpgrader().buildCommand("X:\\tmp\\new.msi"),
        )
    }

    @Test fun command_omits_installdir_when_resources_dir_unset() {
        System.clearProperty(PROPERTY)
        assertEquals(
            listOf("msiexec", "/i", "X:\\tmp\\new.msi"),
            MsiUpgrader().buildCommand("X:\\tmp\\new.msi"),
        )
    }

    private companion object {
        const val PROPERTY = "compose.application.resources.dir"
    }
}
