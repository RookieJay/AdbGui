package com.adbgui.core.update

import com.adbgui.core.domain.UpdateVersion

object UpdateVersionComparer {
    fun isNewer(remote: String, current: String): Boolean =
        UpdateVersion.parse(remote).isGreaterThan(UpdateVersion.parse(current))
}
