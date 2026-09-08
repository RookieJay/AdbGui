package com.adbgui.core.domain

/**
 * 语义版本（semver 子集）：MAJOR.MINOR.PATCH[-PRERELEASE]。
 * 仅支持点分数字 prerelease 段（beta.1, rc.2），不支持 build metadata。
 */
data class UpdateVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: String? = null,
) {
    fun isGreaterThan(other: UpdateVersion): Boolean {
        if (major != other.major) return major > other.major
        if (minor != other.minor) return minor > other.minor
        if (patch != other.patch) return patch > other.patch
        // 均有 prerelease：按段逐项比；无 prerelease 者 > 有 prerelease 者
        if (prerelease == null && other.prerelease == null) return false
        if (prerelease == null) return true              // release > prerelease
        if (other.prerelease == null) return false       // prerelease < release
        return comparePrerelease(prerelease, other.prerelease) > 0
    }

    private fun comparePrerelease(a: String, b: String): Int {
        val sa = a.split(".")
        val sb = b.split(".")
        val n = minOf(sa.size, sb.size)
        for (i in 0 until n) {
            val cmp = sa[i].toIntOrNull()?.let { x -> sb[i].toIntOrNull()?.let { y -> x.compareTo(y) } }
                ?: sa[i].compareTo(sb[i])
            if (cmp != 0) return cmp
        }
        return sa.size.compareTo(sb.size)
    }

    companion object {
        private val re = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.\-]+))?$""")
        fun parse(text: String): UpdateVersion {
            val m = re.matchEntire(text.trim())
            require(m != null) { "Invalid version: $text" }
            return UpdateVersion(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].toInt(),
                m.groupValues[4].takeIf { it.isNotEmpty() },
            )
        }
    }
}
