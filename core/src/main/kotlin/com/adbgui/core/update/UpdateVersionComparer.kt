package com.adbgui.core.update

import com.adbgui.core.domain.UpdateVersion

object UpdateVersionComparer {
    fun isNewer(remote: String, current: String): Boolean =
        UpdateVersion.parse(remote).isGreaterThan(UpdateVersion.parse(current))

    /** [version] >= [floor]（相等也算），用于"机器上已装目标版本"的判断。 */
    fun isAtLeast(version: String, floor: String): Boolean =
        UpdateVersion.parse(version).isAtLeast(UpdateVersion.parse(floor))
}
