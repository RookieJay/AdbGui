package com.adbgui.core.adb

object ContentQueryParser {
    // Row: 0 _id=1, name=foo, value=bar
    private val rowRe = Regex("""Row:\s*\d+\s+(.+)""")
    private val kvRe = Regex("""(\w+)=(.+?)(?:,\s*|$)""")

    fun parse(stdout: String): List<Map<String, String>> {
        return stdout.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith("Row:")) return@mapNotNull null
                val pairs = trimmed.removePrefix("Row:").trim()
                kvRe.findAll(pairs).associate { it.groupValues[1] to it.groupValues[2].trim() }
                    .ifEmpty { null }
            }
            .toList()
    }
}
