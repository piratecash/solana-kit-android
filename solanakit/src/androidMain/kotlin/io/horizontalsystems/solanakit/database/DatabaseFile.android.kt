package io.horizontalsystems.solanakit.database

import io.horizontalsystems.solanakit.PlatformContext
import java.io.File

internal actual fun databaseFile(context: PlatformContext, name: String): File =
    context.getDatabasePath(name)
