package com.meetingnotes.data

import app.cash.sqldelight.db.SqlDriver
import com.meetingnotes.db.MeetingDatabase

/** Platform-specific factory for the SQLDelight driver. */
expect object DatabaseFactory {
    fun createDriver(): SqlDriver
}

/** Creates a fully configured [MeetingDatabase] for the current platform. */
fun createDatabase(): MeetingDatabase = MeetingDatabase(DatabaseFactory.createDriver())
