package com.meetingnotes.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.meetingnotes.db.MeetingDatabase

// Test-scoped actual: uses in-memory SQLite so tests don't touch the filesystem.
// This file lives in desktopTest but the actual declaration is in desktopMain.
// For test isolation we override via a test helper below.

/** Returns an in-memory MeetingDatabase for unit tests. */
fun createInMemoryDatabase(): MeetingDatabase {
    val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    MeetingDatabase.Schema.create(driver)
    return MeetingDatabase(driver)
}
