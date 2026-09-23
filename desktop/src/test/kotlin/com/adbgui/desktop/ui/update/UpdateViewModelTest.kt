package com.adbgui.desktop.ui.update

import com.adbgui.core.log.NoopLogger
import com.adbgui.core.settings.SettingsStore
import com.adbgui.core.update.UpdateChecker
import com.adbgui.core.update.UpdateDownloadResult
import com.adbgui.core.update.UpdateDownloader
import com.adbgui.core.update.UpdateManifestFetcher
import com.adbgui.desktop.platform.InstalledAppInfo
import com.adbgui.desktop.platform.InstalledAppLauncher
import com.adbgui.desktop.platform.InstalledAppVersionProbe
import com.adbgui.desktop.platform.MsiUpgrader
import com.adbgui.desktop.platform.PortableUpdateNotifier
import com.adbgui.desktop.ui.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private class FakeFetcher(private val text: String?) : UpdateManifestFetcher {
        override suspend fun fetch(url: String): String = text ?: error("no stub")
    }

    private class FakeDownloader(private val result: UpdateDownloadResult) : UpdateDownloader {
        override suspend fun download(url: String, sha256: String, onProgress: (Float) -> Unit) = result
    }

    private class CancellingDownloader : UpdateDownloader {
        override suspend fun download(url: String, sha256: String, onProgress: (Float) -> Unit): UpdateDownloadResult {
            delay(1)
            throw CancellationException("cancelled")
        }
    }

    private class RecordingDownloader : UpdateDownloader {
        var receivedUrl: String? = null
        override suspend fun download(url: String, sha256: String, onProgress: (Float) -> Unit): UpdateDownloadResult {
            receivedUrl = url
            return UpdateDownloadResult.Success("/tmp/x.msi")
        }
    }

    private class FakeMsiUpgrader : MsiUpgrader() {
        var launched: String? = null
        override fun launch(msiPath: String) { launched = msiPath }
    }

    private class FakeProbe(private val info: InstalledAppInfo?) : InstalledAppVersionProbe {
        override fun probe(): InstalledAppInfo? = info
    }

    private class FakeAppLauncher : InstalledAppLauncher() {
        var launched: String? = null
        override fun launch(exePath: String) { launched = exePath }
    }

    private class ThrowingAppLauncher : InstalledAppLauncher() {
        override fun launch(exePath: String) { throw RuntimeException("spawn failed") }
    }

    private class FakeNotifier : PortableUpdateNotifier() {
        var opened: String? = null
        override fun openDownloadPage(url: String) { opened = url }
    }

    private fun manifestJson(version: String): String =
        """{"version":"$version","url":"https://example.com/AdbGui-$version.msi","sha256":"a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"}"""

    private fun buildVm(
        scope: TestScope,
        fetcherText: String?,
        current: String = "1.0.0",
        downloader: UpdateDownloader = FakeDownloader(UpdateDownloadResult.Success("/tmp/x.msi")),
        msiUpgrader: MsiUpgrader = MsiUpgrader(),
        notifier: PortableUpdateNotifier = PortableUpdateNotifier(),
        installedProbe: InstalledAppVersionProbe = FakeProbe(null),
        appLauncher: InstalledAppLauncher = FakeAppLauncher(),
        exit: (Int) -> Nothing = { throw CancellationException("exit ${it}") },
        io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    ): Triple<UpdateViewModel, SettingsStore, UpdateChecker> {
        val dir = Files.createTempDirectory("uvm")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val checker = UpdateChecker(FakeFetcher(fetcherText), current, NoopLogger)
        return Triple(UpdateViewModel(checker, store, scope, downloader, msiUpgrader, notifier, installedProbe, appLauncher, exit, io), store, checker)
    }

    @Test fun check_finds_update() = runTest {
        val (vm, _, _) = buildVm(this, manifestJson("1.1.0"))
        vm.checkForUpdates()
        advanceUntilIdle()
        val s = vm.state.value
        assertIs<UpdateState.Available>(s)
        assertEquals("1.1.0", s.manifest.version)
    }

    @Test fun check_no_update() = runTest {
        val (vm, _, _) = buildVm(this, manifestJson("1.0.0"))
        vm.checkForUpdates()
        advanceUntilIdle()
        assertEquals(UpdateState.NoUpdate, vm.state.value)
    }

    @Test fun check_error_sets_error_state() = runTest {
        val (vm, _, _) = buildVm(this, null)
        vm.checkForUpdates()
        advanceUntilIdle()
        assertIs<UpdateState.Error>(vm.state.value)
    }

    @Test fun check_error_carries_raw_on_parse_failure() = runTest {
        val (vm, _, _) = buildVm(this, "{not json")
        vm.checkForUpdates()
        advanceUntilIdle()
        val s = assertIs<UpdateState.Error>(vm.state.value)
        assertEquals("{not json", s.raw)
    }

    @Test fun open_download_page_proxies_url_for_mirror_source() = runTest {
        val notifier = FakeNotifier()
        val (vm, store, _) = buildVm(this, manifestJson("1.1.0"), "1.0.0", notifier = notifier)
        store.update { it.copy(update = it.update.copy(sourceId = "github-mirror")) }
        advanceUntilIdle()
        vm.checkForUpdates(); advanceUntilIdle()
        vm.openDownloadPage(); advanceUntilIdle()
        val expected = "https://gh-proxy.com/" + "https://example.com/AdbGui-1.1.0.msi"
        assertEquals(expected, notifier.opened)
    }

    @Test fun select_source_persists() = runTest {
        val (vm, store, _) = buildVm(this, null)
        vm.selectSource("github-mirror")
        advanceUntilIdle()
        assertEquals("github-mirror", store.load().update.sourceId)
    }

    @Test fun download_succeeds_to_ready() = runTest {
        val (vm, _, _) = buildVm(this, manifestJson("1.1.0"), "1.0.0",
            downloader = FakeDownloader(UpdateDownloadResult.Success("/tmp/x.msi")))
        vm.checkForUpdates(); advanceUntilIdle()
        vm.downloadUpdate(); advanceUntilIdle()
        val s = vm.state.value
        assertIs<UpdateState.Ready>(s)
        assertEquals("/tmp/x.msi", s.msiPath)
    }

    @Test fun download_hash_mismatch_sets_error() = runTest {
        val (vm, _, _) = buildVm(this, manifestJson("1.1.0"), "1.0.0",
            downloader = FakeDownloader(UpdateDownloadResult.HashMismatch("a", "b")))
        vm.checkForUpdates(); advanceUntilIdle()
        vm.downloadUpdate(); advanceUntilIdle()
        assertIs<UpdateState.Error>(vm.state.value)
    }

    @Test fun install_now_launches_msi_and_exits() = runTest {
        var exited = -1
        val msi = FakeMsiUpgrader()
        val (vm, _, _) = buildVm(this, manifestJson("1.1.0"), "1.0.0",
            downloader = FakeDownloader(UpdateDownloadResult.Success("/tmp/x.msi")),
            msiUpgrader = msi,
            exit = { exited = it; throw CancellationException("exit") })
        vm.checkForUpdates(); advanceUntilIdle()
        vm.downloadUpdate(); advanceUntilIdle()
        vm.installNow(); advanceUntilIdle()
        assertEquals("/tmp/x.msi", msi.launched)
        assertEquals(0, exited)
    }

    @Test fun install_now_skips_msi_when_target_already_installed() = runTest {
        // 机器上已装 1.1.0（同版本 ProductCode → 维护重装会弹 1926 风暴）→ 直接启动已装新版
        val installDir = Files.createTempDirectory("installed")
        Files.createFile(installDir.resolve("AdbGui.exe"))
        var exited = -1
        val msi = FakeMsiUpgrader()
        val launcher = FakeAppLauncher()
        val (vm, _, _) = buildVm(this, manifestJson("1.1.0"), "1.0.0",
            msiUpgrader = msi,
            installedProbe = FakeProbe(InstalledAppInfo("1.1.0", installDir.toAbsolutePath().toString())),
            appLauncher = launcher,
            exit = { exited = it; throw CancellationException("exit") })
        vm.checkForUpdates(); advanceUntilIdle()
        vm.downloadUpdate(); advanceUntilIdle()
        vm.installNow(); advanceUntilIdle()
        assertEquals(null, msi.launched)
        assertEquals(installDir.resolve("AdbGui.exe").toAbsolutePath().toString(), launcher.launched)
        assertEquals(0, exited)
    }

    @Test fun install_now_skips_msi_when_installed_version_is_newer() = runTest {
        val installDir = Files.createTempDirectory("installed-newer")
        Files.createFile(installDir.resolve("AdbGui.exe"))
        val msi = FakeMsiUpgrader()
        val launcher = FakeAppLauncher()
        val (vm, _, _) = buildVm(this, manifestJson("1.1.0"), "1.0.0",
            msiUpgrader = msi,
            installedProbe = FakeProbe(InstalledAppInfo("1.2.0", installDir.toAbsolutePath().toString())),
            appLauncher = launcher,
            exit = { throw CancellationException("exit") })
        vm.checkForUpdates(); advanceUntilIdle()
        vm.downloadUpdate(); advanceUntilIdle()
        vm.installNow(); advanceUntilIdle()
        assertEquals(null, msi.launched)
        assertEquals(installDir.resolve("AdbGui.exe").toAbsolutePath().toString(), launcher.launched)
    }

    @Test fun install_now_runs_msi_when_installed_version_is_older() = runTest {
        val installDir = Files.createTempDirectory("installed-older")
        Files.createFile(installDir.resolve("AdbGui.exe"))
        val msi = FakeMsiUpgrader()
        val launcher = FakeAppLauncher()
        val (vm, _, _) = buildVm(this, manifestJson("1.1.0"), "1.0.0",
            msiUpgrader = msi,
            installedProbe = FakeProbe(InstalledAppInfo("1.0.5", installDir.toAbsolutePath().toString())),
            appLauncher = launcher,
            exit = { throw CancellationException("exit") })
        vm.checkForUpdates(); advanceUntilIdle()
        vm.downloadUpdate(); advanceUntilIdle()
        vm.installNow(); advanceUntilIdle()
        assertEquals("/tmp/x.msi", msi.launched)
        assertEquals(null, launcher.launched)
    }

    @Test fun install_now_no_exe_found_sets_error() = runTest {
        // ARP 无 InstallLocation 且 dev 运行（无 resources.dir），找不到 exe → 内联报错，不退出
        val emptyDir = Files.createTempDirectory("installed-noexe")
        val (vm, _, _) = buildVm(this, manifestJson("1.1.0"), "1.0.0",
            installedProbe = FakeProbe(InstalledAppInfo("1.1.0", emptyDir.toAbsolutePath().toString())))
        vm.checkForUpdates(); advanceUntilIdle()
        vm.downloadUpdate(); advanceUntilIdle()
        vm.installNow(); advanceUntilIdle()
        val s = assertIs<UpdateState.Error>(vm.state.value)
        assertTrue(s.message.isNotEmpty())
    }

    @Test fun install_now_launch_failure_sets_error() = runTest {
        val installDir = Files.createTempDirectory("installed-launchfail")
        Files.createFile(installDir.resolve("AdbGui.exe"))
        val (vm, _, _) = buildVm(this, manifestJson("1.1.0"), "1.0.0",
            installedProbe = FakeProbe(InstalledAppInfo("1.1.0", installDir.toAbsolutePath().toString())),
            appLauncher = ThrowingAppLauncher())
        vm.checkForUpdates(); advanceUntilIdle()
        vm.downloadUpdate(); advanceUntilIdle()
        vm.installNow(); advanceUntilIdle()
        val s = assertIs<UpdateState.Error>(vm.state.value)
        assertTrue(s.message.contains("spawn failed"))
    }

    @Test fun open_download_page() = runTest {
        val notifier = FakeNotifier()
        val (vm, _, _) = buildVm(this, manifestJson("1.1.0"), "1.0.0", notifier = notifier)
        vm.checkForUpdates(); advanceUntilIdle()
        vm.openDownloadPage(); advanceUntilIdle()
        assertEquals("https://example.com/AdbGui-1.1.0.msi", notifier.opened)
    }

    @Test fun cancel_download_restores_available() = runTest {
        val (vm, _, _) = buildVm(this, manifestJson("1.1.0"), "1.0.0",
            downloader = CancellingDownloader())
        vm.checkForUpdates(); advanceUntilIdle()
        vm.downloadUpdate()
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s is UpdateState.Available, "expected Available after cancel, got $s")
    }

    @Test fun dismiss_current_update_persists_version() = runTest {
        val (vm, store, _) = buildVm(this, manifestJson("1.1.0"), "1.0.0")
        vm.checkForUpdates(); advanceUntilIdle()
        assertIs<UpdateState.Available>(vm.state.value)
        vm.dismissCurrentUpdate(); advanceUntilIdle()
        assertEquals("1.1.0", store.load().update.dismissedVersion)
    }

    @Test fun dismiss_propagates_to_shared_settings_viewmodel() = runTest {
        val dir = Files.createTempDirectory("shared")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val settingsVm = SettingsViewModel(store, this)
        val updateVm = UpdateViewModel(
            UpdateChecker(FakeFetcher(manifestJson("1.1.0")), "1.0.0", NoopLogger),
            store, this,
            FakeDownloader(UpdateDownloadResult.Success("/tmp/x.msi")),
            MsiUpgrader(), PortableUpdateNotifier(), FakeProbe(null), FakeAppLauncher(),
            exit = { throw CancellationException("exit") },
        )
        advanceUntilIdle() // populate settingsVm.settings via init load
        updateVm.checkForUpdates(); advanceUntilIdle()
        assertIs<UpdateState.Available>(updateVm.state.value)
        updateVm.dismissCurrentUpdate(); advanceUntilIdle()
        // The banner's data source (settingsVm.settings) must reflect the dismiss
        // without requiring a restart or explicit reload.
        assertEquals("1.1.0", settingsVm.settings.value.update.dismissedVersion)
    }

    @Test fun mirror_source_proxies_msi_download_url() = runTest {
        val recorder = RecordingDownloader()
        val (vm, store, _) = buildVm(this, manifestJson("1.1.0"), "1.0.0", downloader = recorder)
        // Persist github-mirror as the selected source before checking.
        store.update { it.copy(update = it.update.copy(sourceId = "github-mirror")) }
        advanceUntilIdle()
        vm.checkForUpdates(); advanceUntilIdle()
        assertIs<UpdateState.Available>(vm.state.value)
        vm.downloadUpdate(); advanceUntilIdle()
        val expected = "https://gh-proxy.com/" + "https://example.com/AdbGui-1.1.0.msi"
        assertEquals(expected, recorder.receivedUrl)
    }

    @Test fun restart_resumes_ready_when_file_and_sha_match() = runTest {
        val msiFile = Files.createTempFile("resume", ".msi").toFile()
        msiFile.writeBytes("dummy msi content for resume test".toByteArray())
        val sha = sha256Of(msiFile)
        val manifest = """{"version":"1.1.0","url":"https://example.com/x.msi","sha256":"$sha"}"""
        val dir = Files.createTempDirectory("uvm-resume")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val fetcher = FakeFetcher(manifest)
        val downloader = FakeDownloader(UpdateDownloadResult.Success(msiFile.absolutePath))
        fun mkVm() = UpdateViewModel(
            UpdateChecker(fetcher, "1.0.0", NoopLogger), store, this, downloader,
            MsiUpgrader(), PortableUpdateNotifier(), FakeProbe(null), FakeAppLauncher(),
            { throw CancellationException("exit") }, kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val vm1 = mkVm()
        vm1.checkForUpdates(); advanceUntilIdle()
        assertIs<UpdateState.Available>(vm1.state.value)
        vm1.downloadUpdate(); advanceUntilIdle()
        assertIs<UpdateState.Ready>(vm1.state.value)
        assertEquals(msiFile.absolutePath, (vm1.state.value as UpdateState.Ready).msiPath)
        assertEquals("1.1.0", store.load().update.readyVersion)
        assertEquals(sha, store.load().update.readySha256)
        // Restart: new VM, same store, same manifest → should resume Ready (file exists + sha matches).
        val vm2 = mkVm()
        vm2.checkForUpdates(); advanceUntilIdle()
        val s2 = vm2.state.value
        assertIs<UpdateState.Ready>(s2)
        assertEquals(msiFile.absolutePath, s2.msiPath)
    }

    @Test fun restart_falls_back_to_available_when_file_missing() = runTest {
        val msiFile = Files.createTempFile("resume-missing", ".msi").toFile()
        msiFile.writeBytes("dummy msi content".toByteArray())
        val sha = sha256Of(msiFile)
        val manifest = """{"version":"1.1.0","url":"https://example.com/x.msi","sha256":"$sha"}"""
        val dir = Files.createTempDirectory("uvm-missing")
        val store = SettingsStore(dir, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val fetcher = FakeFetcher(manifest)
        val downloader = FakeDownloader(UpdateDownloadResult.Success(msiFile.absolutePath))
        fun mkVm() = UpdateViewModel(
            UpdateChecker(fetcher, "1.0.0", NoopLogger), store, this, downloader,
            MsiUpgrader(), PortableUpdateNotifier(), FakeProbe(null), FakeAppLauncher(),
            { throw CancellationException("exit") }, kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val vm1 = mkVm()
        vm1.checkForUpdates(); advanceUntilIdle()
        vm1.downloadUpdate(); advanceUntilIdle()
        assertIs<UpdateState.Ready>(vm1.state.value)
        // Delete the MSI; on restart the check can't resume → falls back to Available.
        msiFile.delete()
        val vm2 = mkVm()
        vm2.checkForUpdates(); advanceUntilIdle()
        assertIs<UpdateState.Available>(vm2.state.value)
    }

    private fun sha256Of(file: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun open_portable_page_proxies_portable_url() = runTest {
        val notifier = FakeNotifier()
        val manifest = """{"version":"1.1.0","url":"https://example.com/AdbGui-1.1.0.msi",
            "sha256":"a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
            "portableUrl":"https://example.com/AdbGui-1.1.0-portable.zip"}""".trimIndent()
        val (vm, store, _) = buildVm(this, manifest, "1.0.0", notifier = notifier)
        store.update { it.copy(update = it.update.copy(sourceId = "github-mirror")) }
        advanceUntilIdle()
        vm.checkForUpdates(); advanceUntilIdle()
        vm.openPortablePage(); advanceUntilIdle()
        assertEquals(
            "https://gh-proxy.com/https://example.com/AdbGui-1.1.0-portable.zip",
            notifier.opened,
        )
    }

    @Test fun custom_source_resolves_to_custom_manifest_url() = runTest {
        val recorder = RecordingDownloader()
        val manifest = """{"version":"1.1.0","url":"http://127.0.0.1:8000/AdbGui-1.1.0.msi",
            "sha256":"a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"}""".trimIndent()
        val (vm, store, _) = buildVm(this, manifest, "1.0.0", downloader = recorder)
        // 选中 "custom" 并提供 URL
        vm.selectSource("custom", "http://127.0.0.1:8000/latest.json"); advanceUntilIdle()
        vm.checkForUpdates(); advanceUntilIdle()
        assertIs<UpdateState.Available>(vm.state.value)
        vm.downloadUpdate(); advanceUntilIdle()
        // 自定义源无代理，直接传清单里的 url
        assertEquals("http://127.0.0.1:8000/AdbGui-1.1.0.msi", recorder.receivedUrl)
        // 持久化
        val reloaded = store.load().update
        assertEquals("custom", reloaded.sourceId)
        assertEquals("http://127.0.0.1:8000/latest.json", reloaded.customManifestUrl)
    }

    @Test fun select_builtin_source_clears_custom_url() = runTest {
        val (vm, store, _) = buildVm(this, manifestJson("1.1.0"), "1.0.0")
        vm.selectSource("custom", "http://127.0.0.1:8000/latest.json"); advanceUntilIdle()
        assertEquals("custom", store.load().update.sourceId)
        assertEquals("http://127.0.0.1:8000/latest.json", store.load().update.customManifestUrl)
        // 切回内置源 → customManifestUrl 清零
        vm.selectSource("github-mirror"); advanceUntilIdle()
        assertEquals("github-mirror", store.load().update.sourceId)
        assertEquals("", store.load().update.customManifestUrl)
    }

    @Test fun custom_source_with_blank_url_falls_back_to_default() = runTest {
        // "custom" 但 URL 为空 → resolve 返回 null → 兜底 github-official
        val recorder = RecordingDownloader()
        val manifest = """{"version":"1.1.0","url":"https://example.com/x.msi",
            "sha256":"a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"}""".trimIndent()
        val (vm, store, _) = buildVm(this, manifest, "1.0.0", downloader = recorder)
        store.update { it.copy(update = it.update.copy(sourceId = "custom", customManifestUrl = "")) }
        advanceUntilIdle()
        vm.checkForUpdates(); advanceUntilIdle()
        vm.downloadUpdate(); advanceUntilIdle()
        // 无代理（兜底的 github-official 也无代理），直接 url
        assertEquals("https://example.com/x.msi", recorder.receivedUrl)
    }
}
