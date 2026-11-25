package io.horizontalsystems.solanakit.database

import io.horizontalsystems.solanakit.PlatformContext
import java.io.File

/** Path computation only, no I/O: [name] is either a bare file name or an absolute path. */
internal expect fun databaseFile(context: PlatformContext, name: String): File
