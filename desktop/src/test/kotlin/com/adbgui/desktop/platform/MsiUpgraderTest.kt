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
        System.setProperty(PROPERTY, "D:\\Program Files\\AdbGui\\resources")
        assertEquals(
            listOf("msiexec", "/i", "X:\\tmp\\new.msi", "INSTALLDIR=D:\\Program Files\\AdbGui"),
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
