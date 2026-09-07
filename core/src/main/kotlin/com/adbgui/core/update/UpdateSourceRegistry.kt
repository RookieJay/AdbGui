package com.adbgui.core.update

/**
 * 开发者维护的内置更新源常量列表。用户在设置页从下拉选其一；
 * 不在运行时远程拉取源列表（防劫持）。新源随 app 版本在此增删。
 */
object UpdateSourceRegistry {
    val all: List<UpdateSource> = listOf(
        UpdateSource(
            "github-official",
            "GitHub 官方",
            "https://github.com/OWNER/ADBGUI/releases/latest/download/latest.json",
        ),
        UpdateSource(
            "github-mirror",
            "GitHub 镜像 (ghproxy)",
            "https://ghproxy.com/https://github.com/OWNER/ADBGUI/releases/latest/download/latest.json",
        ),
    )

    val default: UpdateSource = all.first { it.id == "github-official" }

    fun byId(id: String): UpdateSource? = all.firstOrNull { it.id == id }
}
