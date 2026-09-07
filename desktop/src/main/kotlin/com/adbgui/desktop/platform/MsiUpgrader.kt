package com.adbgui.desktop.platform

import java.io.File

/**
 * 启动 msiexec /i 升级 MSI（perUserInstall 免管理员）。
 * 调用方在 launch 后必须 exitProcess(0) 释放已安装文件锁。
 */
open class MsiUpgrader {
    open fun launch(msiPath: String) {
        val pb = ProcessBuilder("msiexec", "/i", File(msiPath).absolutePath)
            .redirectErrorStream(true)
        pb.start()
    }
}
