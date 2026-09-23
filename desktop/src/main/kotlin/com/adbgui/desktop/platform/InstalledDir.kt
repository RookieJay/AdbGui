package com.adbgui.desktop.platform

import java.io.File

/**
 * 检测当前运行中的 app 的安装目录。
 *
 * 打包运行：Compose 启动器设 `compose.application.resources.dir = <install>\app\resources`
 * （jpackage 布局；jcmd 实测），回退两层到 `<install>`，并用 `AdbGui.exe` 存在性兜底校验
 * （避免 desktopRun 等 dev 场景把 `build\compose\tmp` 这种临时目录误当成安装位置）。
 *
 * dev/desktopRun：resources.dir 不存在或指向不含 `AdbGui.exe` 的目录 → 返回 null。
 */
object InstalledDir {
    private const val PROPERTY = "compose.application.resources.dir"

    fun detect(): String? {
        val resDir = System.getProperty(PROPERTY) ?: return null
        var dir = File(resDir).parentFile ?: return null
        // <install>\app\resources → 回退两层；若安装目录本身名为 "app"（<install>\app\app\resources），
        // 第二层不回退（只有紧贴 resources 的那层 "app" 才是 jpackage 的 app 子目录）
        if (dir.parentFile != null && dir.name.equals("app", ignoreCase = true)) {
            dir = dir.parentFile!!
        }
        return if (File(dir, "AdbGui.exe").isFile) dir.path else null
    }
}
