package com.adbgui.desktop.platform

import com.adbgui.core.log.Logger
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinUser
import java.io.File
import java.io.IOException

/**
 * 启动 msiexec /i 升级 MSI（perUserInstall 免管理员）。
 * - 传 `INSTALLDIR=当前安装目录`：jpackage MSI 模板没有 RegistrySearch 回读旧安装位置
 *   （只写 ARPINSTALLLOCATION），升级重装时目录选择页只会回落默认值（per-user 为
 *   %LocalAppData%）。传公共属性 INSTALLDIR 可让 msiexec 预填并装回原目录。
 *   当前安装目录由 [InstalledDir.detect] 检测（打包运行时有效，desktopRun 下为 null → 不传）。
 * - 必须经 ShellExecute 而非 ProcessBuilder：msiexec 公共属性语法只接受 `INSTALLDIR="值"`
 *   （引号只包值）。ProcessBuilder 对含空格参数整段加引号 `"INSTALLDIR=D:\a b"`，msiexec
 *   判命令行非法（实测退出码 1639）弹帮助框（2026-09-24 复现）。ShellExecute 的参数是
 *   原始字符串，没有 argv 引号转换层，可精确产出值引号形式。
 * - 调用方在 launch 后必须 exitProcess(0) 释放已安装文件锁。
 */
open class MsiUpgrader(private val logger: Logger? = null) {
    open fun launch(msiPath: String) {
        val params = buildParameters(msiPath)
        logger?.info("update: msiexec /i parameters=$params")
        val hinst = Shell32.INSTANCE.ShellExecute(null, "open", "msiexec.exe", params, null, WinUser.SW_SHOWNORMAL)
        // 返回值 > 32 才是成功（SE_ERR_* 错误码 ≤ 32）。注：Java Number.longValue() 在 Kotlin
        // 里映射为 toLong()，直接调 longValue() 会报 Unresolved reference。
        val code = hinst.toLong()
        if (code <= 32) throw IOException("ShellExecute msiexec failed: $code")
    }

    fun buildParameters(msiPath: String): String {
        val params = StringBuilder("/i \"").append(File(msiPath).absolutePath).append('"')
        InstalledDir.detect()?.let { params.append(" INSTALLDIR=\"").append(it).append('"') }
        return params.toString()
    }
}
