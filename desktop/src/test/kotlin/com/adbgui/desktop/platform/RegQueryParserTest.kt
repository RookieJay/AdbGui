package com.adbgui.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fixtures 录制自本机 Windows 11 Home China（zh-CN，reg.exe 输出 GBK），
 * 2026-09-22，AdbGui 1.2.1 per-user 安装（ARP 键在 HKLM）：
 *   reg query "HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall" /s /f AdbGui /d
 *   reg query "HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\{F3713F5C-44F9-34C4-B0D8-8BCD9BE1299A}"
 *   reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall" /s /f AdbGui /d
 * 汇总行（GBK 中文）按录制原样保留——parser 必须无视它（locale 会变）。
 */
class RegQueryParserTest {
    private val keyPath = "HKEY_LOCAL_MACHINE\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{F3713F5C-44F9-34C4-B0D8-8BCD9BE1299A}"

    // reg query "HKLM\...\Uninstall" /s /f AdbGui /d —— 4 个匹配值同属一个键
    private val searchOutput = """
$keyPath
    Comments    REG_SZ    AdbGui
    InstallLocation    REG_SZ    D:\Program Files\AdbGui\
    InstallSource    REG_SZ    C:\Users\Jay\AppData\Roaming\AdbGui\updates\
    DisplayName    REG_SZ    AdbGui

搜索结束: 找到 4 个匹配。
""".trimIndent()

    // reg query "HKCU\...\Uninstall" /s /f AdbGui /d —— 0 匹配：只有汇总行
    private val emptySearchOutput = "\r\n搜索结束: 找到 0 个匹配。\r\n"

    // reg query "<AdbGui ARP key>" —— 完整值列表（截取实际出现的相关值，含空数据与 REG_DWORD）
    private val keyOutput = """
$keyPath
    AuthorizedCDFPrefix    REG_SZ
    Comments    REG_SZ    AdbGui
    DisplayVersion    REG_SZ    1.2.1
    HelpLink    REG_SZ
    InstallDate    REG_SZ    20260922
    InstallLocation    REG_SZ    D:\Program Files\AdbGui\
    InstallSource    REG_SZ    C:\Users\Jay\AppData\Roaming\AdbGui\updates\
    NoModify    REG_DWORD    0x1
    UninstallString    REG_EXPAND_SZ    MsiExec.exe /X{F3713F5C-44F9-34C4-B0D8-8BCD9BE1299A}
    DisplayName    REG_SZ    AdbGui
""".trimIndent()

    @Test fun search_extracts_matching_key_paths() {
        assertEquals(listOf(keyPath), RegQueryParser.searchMatchKeyPaths(searchOutput))
    }

    @Test fun search_ignores_localized_summary_line() {
        assertTrue(RegQueryParser.searchMatchKeyPaths(searchOutput).none { it.contains("搜索结束") })
    }

    @Test fun search_empty_result_yields_no_keys() {
        assertEquals(emptyList(), RegQueryParser.searchMatchKeyPaths(emptySearchOutput))
    }

    @Test fun search_handles_crlf() {
        assertEquals(listOf(keyPath), RegQueryParser.searchMatchKeyPaths(searchOutput.replace("\n", "\r\n")))
    }

    @Test fun values_extracts_named_values() {
        val values = RegQueryParser.values(keyOutput)
        assertEquals("AdbGui", values["DisplayName"])
        assertEquals("1.2.1", values["DisplayVersion"])
        assertEquals("D:\\Program Files\\AdbGui\\", values["InstallLocation"])
        assertEquals("0x1", values["NoModify"])
    }

    @Test fun values_keeps_data_with_spaces() {
        // 路径含单个空格（"Program Files"），列间才是 ≥2 空格
        val values = RegQueryParser.values(keyOutput)
        assertEquals("C:\\Users\\Jay\\AppData\\Roaming\\AdbGui\\updates\\", values["InstallSource"])
    }

    @Test fun values_empty_data_maps_to_blank() {
        assertEquals("", RegQueryParser.values(keyOutput)["AuthorizedCDFPrefix"])
    }

    @Test fun values_empty_data_accepts_trailing_padding() {
        // 录制输出中空数据值行带 4 个尾随空格（"REG_SZ    "）
        val output = "HKEY_X\\k\r\n    HelpLink    REG_SZ    \r\n    DisplayName    REG_SZ    AdbGui\r\n"
        val values = RegQueryParser.values(output)
        assertEquals("", values["HelpLink"])
        assertEquals("AdbGui", values["DisplayName"])
        assertEquals(2, values.size)
    }

    @Test fun values_ignores_key_header_and_blank_lines() {
        val values = RegQueryParser.values(keyOutput)
        assertTrue(values.keys.none { it.startsWith("HKEY_") })
        assertEquals(10, values.size)
    }
}
