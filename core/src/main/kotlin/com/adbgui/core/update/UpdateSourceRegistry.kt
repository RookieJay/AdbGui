package com.adbgui.core.update

/**
 * 开发者维护的内置更新源常量列表。用户在设置页从下拉选其一；
 * 不在运行时远程拉取源列表（防劫持）。新源随 app 版本在此增删。
 *
 * `resolve(id, customUrl)` 支持第三个虚拟 id "custom"：当 id == [CUSTOM_ID] 时，
 * 根据传入的 [customUrl]（须 http(s):// 开头）合成一个 UpdateSource，用于本地测试
 * 自托管 latest.json。
 */
object UpdateSourceRegistry {
    const val CUSTOM_ID: String = "custom"

    val all: List<UpdateSource> = listOf(
        UpdateSource(
            "github-official",
            "GitHub 官方",
            "https://github.com/RookieJay/AdbGui/releases/latest/download/latest.json",
        ),
        UpdateSource(
            "github-mirror",
            "GitHub 镜像 (gh-proxy)",
            "https://gh-proxy.com/https://github.com/RookieJay/AdbGui/releases/latest/download/latest.json",
            proxyPrefix = "https://gh-proxy.com/",
        ),
    )

    val default: UpdateSource = all.first { it.id == "github-official" }

    fun byId(id: String): UpdateSource? = all.firstOrNull { it.id == id }

    /** 解析更新源：内置 id 直接返回；"custom" + 合法 URL → 合成 UpdateSource；其余 null。 */
    fun resolve(id: String, customUrl: String?): UpdateSource? {
        byId(id)?.let { return it }
        if (id != CUSTOM_ID) return null
        val url = customUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        return UpdateSource(CUSTOM_ID, "自定义…", url)
    }
}
