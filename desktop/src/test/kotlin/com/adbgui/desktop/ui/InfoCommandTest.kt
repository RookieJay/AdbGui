package com.adbgui.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfoCommandTest {
    @Test
    fun every_needsPackage_command_has_pkg_placeholder() {
        // Commands that need a package must declare {pkg} so the VM can substitute it.
        systemInfoCommands.filter { it.needsPackage }.forEach { c ->
            assertTrue(c.cmd.contains("{pkg}"), "needsPackage command '${c.titleKey}' must contain {pkg}: ${c.cmd}")
        }
    }

    @Test
    fun every_command_is_non_blank_and_has_group_title_cmd() {
        systemInfoCommands.forEach { c ->
            assertTrue(c.cmd.isNotBlank(), "cmd blank for ${c.titleKey}")
            assertTrue(c.titleKey.isNotBlank(), "titleKey blank")
            assertTrue(c.group.isNotBlank(), "group blank for ${c.titleKey}")
        }
    }

    @Test
    fun at_least_one_command_per_group() {
        systemInfoCommands.groupBy { it.group }.forEach { (g, cmds) ->
            assertTrue(cmds.isNotEmpty(), "group $g has no commands")
        }
    }

    @Test
    fun command_count_matches_spec() {
        // Spec §7.3 lists 16 curated commands. If you add/remove, update this and the spec.
        assertEquals(16, systemInfoCommands.size, "expected 16 system info commands, got ${systemInfoCommands.size}")
    }
}
