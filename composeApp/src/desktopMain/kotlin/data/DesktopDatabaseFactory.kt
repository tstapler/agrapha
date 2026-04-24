package com.meetingnotes.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.meetingnotes.db.MeetingDatabase
import java.nio.file.Path
import java.sql.SQLException
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories

actual object DatabaseFactory {
    actual fun createDriver(): SqlDriver {
        val dbDir = storageBaseDir().resolve("db")
        dbDir.createDirectories()
        val dbPath = dbDir.resolve("meetings.db").absolutePathString()
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        MeetingDatabase.Schema.create(driver)
        // Additive migrations for new columns — safe to run on every startup (SQLite throws
        // SQLException on duplicate column names, which we swallow so existing DBs get the
        // column on first run).
        try { driver.execute(null, "ALTER TABLE Meeting ADD COLUMN audioDurationMs INTEGER", 0, null) } catch (_: SQLException) {}
        try { driver.execute(null, "ALTER TABLE Meeting ADD COLUMN transcriptionDurationMs INTEGER", 0, null) } catch (_: SQLException) {}
        try { driver.execute(null, "ALTER TABLE Meeting ADD COLUMN transcriptionMetricsJson TEXT", 0, null) } catch (_: SQLException) {}
        return driver
    }
}

/** Returns the base data directory: ~/.local/share/meeting-notes/ */
internal fun storageBaseDir(): Path =
    Path.of(System.getProperty("user.home"), ".local", "share", "meeting-notes")
