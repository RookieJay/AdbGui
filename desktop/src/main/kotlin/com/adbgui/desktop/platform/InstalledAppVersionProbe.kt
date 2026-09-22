package com.adbgui.desktop.platform

import com.adbgui.core.domain.UpdateVersion
import java.util.concurrent.TimeUnit

/** Windows 卸载表（ARP）里已装 AdbGui 的信息。 */
data class InstalledAppInfo(
    val version: String,
    val installLocation: String?,
)

/**
 * 探测本机已安装的 AdbGui（版本 + 安装目录）。
 *
 * 用途：升级前查"目标版本是否已装"。jpackage 的 ProductCode 由 应用名+版本 确定性生成，
 * 对已装的同版本 MSI 再跑 msiexec 是"维护重装"：Windows Installer 会把每个被覆盖的文件
 * 备份到 <盘符>\Config.Msi\*.rbf 并逐一设置备份文件的安全描述符；若先前是提升安装、
 * 这次非提升，会逐文件弹 Error 1926 模态框（2026-09-22 实测 25 连弹）。
 */
interface InstalledAppVersionProbe {
    fun probe(): InstalledAppInfo?
}

/** 走 reg.exe 查 HKCU/HKLM(64)/WOW6432Node 三个卸载表根。reg.exe 查询失败返回 null（退回安装器路径）。 */
class RegInstalledAppVersionProbe : InstalledAppVersionProbe {
    override fun probe(): InstalledAppInfo? {
        var best: InstalledAppInfo? = null
        for (root in UNINSTALL_ROOTS) {
            val search = runReg(listOf("query", root, "/s", "/f", APP_NAME, "/d")) ?: continue
            for (key in RegQueryParser.searchMatchKeyPaths(search)) {
                val values = runReg(listOf("query", key))?.let(RegQueryParser::values) ?: continue
                // /f 是子串匹配（"AdbGuiFoo" 也会命中），必须精确校验 DisplayName
                if (values["DisplayName"] != APP_NAME) continue
                val version = values["DisplayVersion"] ?: continue
                val location = values["InstallLocation"]?.takeIf { it.isNotBlank() }
                val info = InstalledAppInfo(version, location)
                if (best == null || isNewer(info, best)) best = info
            }
        }
        return best
    }

    private fun isNewer(a: InstalledAppInfo, b: InstalledAppInfo): Boolean = try {
        UpdateVersion.parse(a.version).isGreaterThan(UpdateVersion.parse(b.version))
    } catch (e: IllegalArgumentException) {
        false // 版本串非法的条目不参与"取最新"
    }

    private fun runReg(args: List<String>): String? = try {
        val p = ProcessBuilder(listOf("reg.exe") + args).redirectErrorStream(true).start()
        val out = p.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        p.waitFor(REG_TIMEOUT_SEC, TimeUnit.SECONDS)
        // 退出码 1 = 搜索 0 命中（仍有汇总输出可解析），不按失败处理
        out
    } catch (t: Throwable) {
        null
    }

    private companion object {
        const val APP_NAME = "AdbGui"
        const val REG_TIMEOUT_SEC = 10L
        val UNINSTALL_ROOTS = listOf(
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        )
    }
}

/** 直接启动已安装的 AdbGui.exe（跳过 MSI 的场景用）。 */
open class InstalledAppLauncher {
    open fun launch(exePath: String) {
        ProcessBuilder(exePath).start()
    }
}
