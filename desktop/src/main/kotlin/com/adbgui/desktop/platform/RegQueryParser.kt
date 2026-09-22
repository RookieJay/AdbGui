package com.adbgui.desktop.platform

/**
 * 解析 reg.exe 输出（纯函数，fixture 单测）。
 *
 * 输出是控制台本地化文本（zh-CN 为 GBK）：键头行以 "HKEY_" 开头，值行 4 空格缩进
 * `名称    类型    数据`（列间 ≥2 空格；数据本身可含单个空格）。末尾的汇总行
 * （"搜索结束: 找到 N 个匹配。"/"End of search: ..."）随 locale 变化，**不许**依赖它。
 * 只取 ASCII 字段（版本号、路径），非 ASCII 数据按 UTF-8 解码可能乱码，可接受。
 */
object RegQueryParser {
    /** `reg query ROOT /s /f NAME /d` 搜索输出 → 命中的键路径（每个含匹配值的键一行）。 */
    fun searchMatchKeyPaths(output: String): List<String> =
        output.lineSequence().filter { it.startsWith("HKEY_") }.toList()

    /** `reg query KEY` 单键输出 → 值名 → 数据。非值行（键头、空行、汇总行）忽略。 */
    fun values(output: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in output.lineSequence()) {
            val m = VALUE_LINE.matchEntire(line) ?: continue
            result[m.groupValues[1]] = m.groupValues[3]
        }
        return result
    }

    // 值行：4 空格缩进 + 名称 + ≥2 空格 + REG_类型 [+ ≥2 空格 + 数据]（数据可为空，
    // 空数据时 reg.exe 可能带尾随空格也可能直接换行，两种都接受）。
    private val VALUE_LINE = Regex("""^ {4}(\S+) {2,}(REG_\S+)(?: {2,}(.*))?$""")
}
