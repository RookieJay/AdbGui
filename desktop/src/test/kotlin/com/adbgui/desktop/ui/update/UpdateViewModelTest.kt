package com.adbgui.desktop.ui.update

import com.adbgui.core.log.NoopLogger
import com.adbgui.core.settings.SettingsStore
import com.adbgui.core.update.UpdateChecker
import com.adbgui.core.update.UpdateManifestFetcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateViewModelTest {
    private class FakeFetcher(private val text: String?) : UpdateManifestFetcher {
        override suspend fun fetch(url: String): String = text ?: error("no stub")
    }

    private fun vm(scope: TestScope, fetcherText: String?, current: String = "1.0.0"): Triple<UpdateViewModel, SettingsStore, UpdateChecker> {
        val dir = Files.createTempDirectory("uvm")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val checker = UpdateChecker(FakeFetcher(fetcherText), current, NoopLogger)
        return Triple(UpdateViewModel(checker, store, scope), store, checker)
    }

    @Test fun check_finds_update() = runTest {
        val (vm, _, _) = vm(this, """
            {"version":"1.1.0","url":"https://x/y.msi",
             "sha256":"a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"}
        """.trimIndent())
        vm.checkForUpdates()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("1.1.0", (s as UpdateState.Available).version)
    }

    @Test fun check_no_update() = runTest {
        val (vm, _, _) = vm(this, """
            {"version":"1.0.0","url":"https://x/y.msi",
             "sha256":"a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"}
        """.trimIndent())
        vm.checkForUpdates()
        advanceUntilIdle()
        assertEquals(UpdateState.NoUpdate, vm.state.value)
    }

    @Test fun check_error_sets_error_state() = runTest {
        val (vm, _, _) = vm(this, null)
        vm.checkForUpdates()
        advanceUntilIdle()
        assert(vm.state.value is UpdateState.Error)
    }

    @Test fun select_source_persists() = runTest {
        val (vm, store, _) = vm(this, null)
        vm.selectSource("github-mirror")
        advanceUntilIdle()
        assertEquals("github-mirror", store.load().update.sourceId)
    }
}
