package com.adbgui.core.update

import com.adbgui.core.domain.UpdateVersion
import kotlinx.serialization.json.Json

object UpdateManifestParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): UpdateManifest {
        val cleaned = stripComments(text)
        val m = runCatching { json.decodeFromString<UpdateManifest>(cleaned) }
            .getOrElse { throw UpdateManifestParseException(text, "json decode failed", it) }
        validate(m, text)
        return m
    }

    /** Strip `//` line comments (fixture header convention; not valid JSON). */
    private fun stripComments(text: String): String =
        text.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    private fun validate(m: UpdateManifest, raw: String) {
        if (m.version.isBlank()) throw fail(raw, "version missing")
        runCatching { UpdateVersion.parse(m.version) }.onFailure { throw fail(raw, "version not semver") }
        if (m.url.isBlank()) throw fail(raw, "url missing")
        if (!isHex64(m.sha256)) throw fail(raw, "sha256 must be 64 lowercase hex")
    }

    private fun isHex64(s: String): Boolean =
        s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

    private fun fail(raw: String, reason: String): UpdateManifestParseException =
        UpdateManifestParseException(raw, reason)
}
