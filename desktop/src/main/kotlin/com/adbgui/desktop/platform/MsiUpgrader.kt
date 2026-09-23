package com.adbgui.desktop.platform

import java.io.File

/**
 * 启动 msiexec /i 升级 MSI（perUserInstall 免管理员）。
 * - 传 `INSTALLDIR=当前安装目录`：jpackage MSI 模板没有 RegistrySearch 回读旧安装位置
 *   （只写 ARPINSTALLLOCATION），升级重装时目录选择页只会回落默认值（per-user 为
 *   %LocalAppData%）。传公共属性 INSTALLDIR 可让 msiexec 预填并装回原目录。
 *   打包运行时 Compose 启动器设 `compose.application.resources.dir=<安装目录>\app\resources`
 *   （jpackage 布局；desktopRun 下为 null → 不传）。
 * - 调用方在 launch 后必须 exitProcess(0) 释放已安装文件锁。
 */
open class MsiUpgrader {
    open fun launch(msiPath: String) {
        ProcessBuilder(buildCommand(msiPath)).redirectErrorStream(true).start()
    }

    fun buildCommand(msiPath: String): List<String> {
        val cmd = mutableListOf("msiexec", "/i", File(msiPath).absolutePath)
        currentInstallDir()?.let { cmd += "INSTALLDIR=$it" }
        return cmd
    }

    private fun currentInstallDir(): String? {
        val resDir = System.getProperty("compose.application.resources.dir") ?: return null
        // <install>\app\resources → 回退两层；若安装目录本身名为 "app"（<install>\app\app\resources），
        // 第二层不回退（只有紧贴 resources 的那层 "app" 才是 jpackage 的 app 子目录）
        var dir = File(resDir).parentFile ?: return null
        if (dir.parentFile != null && dir.name.equals("app", ignoreCase = true)) {
            dir = dir.parentFile!!
        }
        return dir.path
    }
}
