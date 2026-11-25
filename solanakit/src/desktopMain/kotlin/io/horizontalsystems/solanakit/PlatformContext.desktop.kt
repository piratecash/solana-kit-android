package io.horizontalsystems.solanakit

import java.io.File

actual abstract class PlatformContext internal constructor() {
    abstract val dataDir: File
}

/**
 * [dataDir] holds the kit's databases; it is created on first use if missing and must be writable,
 * otherwise opening or migrating a database fails with [IllegalArgumentException].
 */
fun PlatformContext(dataDir: File): PlatformContext = DesktopPlatformContext(dataDir)

private class DesktopPlatformContext(override val dataDir: File) : PlatformContext()
