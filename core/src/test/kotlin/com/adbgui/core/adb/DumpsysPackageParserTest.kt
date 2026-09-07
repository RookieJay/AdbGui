package com.adbgui.core.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DumpsysPackageParserTest {
    private fun fixture(name: String): String {
        val raw = javaClass.classLoader!!.getResource("fixtures/$name")!!
            .readText()
        return raw.lineSequence().dropWhile { it.startsWith("#") }.joinToString("\n")
    }

    @Test
    fun parses_dangbeimarket_fields_and_install_perms_only() {
        val out = fixture("dumpsys_package_hisense_android9.txt")
        val pkg = DumpsysPackageParser.parse(out)
        assertNotNull(pkg)
        assertEquals("6.0.7", pkg!!.versionName)
        assertEquals(613L, pkg.versionCode)
        assertEquals("/data/app/com.dangbeimarket-Q70KvGAKH3s15xDJwIo89A==", pkg.codePath)
        // publicSourceDir has no publicSourceDir= line on Android 9 → falls back to resourcePath=
        assertEquals("/data/app/com.dangbeimarket-Q70KvGAKH3s15xDJwIo89A==", pkg.publicSourceDir)
        assertEquals("/data/app/com.dangbeimarket-Q70KvGAKH3s15xDJwIo89A==/lib", pkg.nativeLibraryDir)
        assertEquals("armeabi-v7a", pkg.primaryCpuAbi)
        // install perms present
        val internet = pkg.permissions.firstOrNull { it.name == "android.permission.INTERNET" }
        assertNotNull(internet) { "INTERNET install perm expected" }
        assertTrue(internet!!.granted, "INTERNET should be granted=true")
        assertTrue(!internet.runtime, "INTERNET in install section → runtime=false")
        // runtime section empty on this fixture
        assertTrue(pkg.permissions.none { it.runtime }, "no runtime perms expected on dangbeimarket fixture")
    }

    @Test
    fun parses_enchatroom_with_runtime_perms() {
        val out = fixture("dumpsys_package_hisense_enchatroom_android9.txt")
        val pkg = DumpsysPackageParser.parse(out)
        assertNotNull(pkg)
        assertEquals("1.07.62.209.0.000", pkg!!.versionName)
        assertEquals(107622090L, pkg.versionCode)
        assertEquals("/data/app/com.speech.enchatroom-cWUd4NHKuEfkWnWXuFmANw==", pkg.codePath)
        assertEquals("/data/app/com.speech.enchatroom-cWUd4NHKuEfkWnWXuFmANw==/lib", pkg.nativeLibraryDir)
        assertEquals("armeabi-v7a", pkg.primaryCpuAbi)
        // runtime perms non-empty
        val runtimePerms = pkg.permissions.filter { it.runtime }
        assertTrue(runtimePerms.isNotEmpty(), "enchatroom should have runtime perms")
        // 13 runtime perms in the fixture; 6 of them carry ", flags=[ ... ]" after granted=true,
        // which the parser regex must tolerate (not just match clean lines).
        assertEquals(13, runtimePerms.size, "all 13 runtime perms should be captured (incl. those with trailing flags=)")
        val loc = runtimePerms.firstOrNull { it.name == "android.permission.ACCESS_FINE_LOCATION" }
        assertNotNull(loc) { "ACCESS_FINE_LOCATION runtime perm expected" }
        assertTrue(loc!!.granted, "ACCESS_FINE_LOCATION should be granted=true")
        // install perms also present, and must be tagged runtime=false (not misclassified)
        val installPerms = pkg.permissions.filter { !it.runtime }
        assertTrue(installPerms.isNotEmpty(), "enchatroom should have install perms tagged non-runtime")
    }

    @Test
    fun returns_null_when_no_packages_section() {
        val pkg = DumpsysPackageParser.parse("garbage output\nno Packages section here")
        assertNull(pkg)
    }
}
