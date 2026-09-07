package com.adbgui.core.update

class UpdateManifestParseException(
    val raw: String,
    reason: String,
    cause: Throwable? = null,
) : RuntimeException("Invalid update manifest: $reason", cause)
