package com.starlwr.bot.bilibili.report

import com.alibaba.fastjson2.JSON
import java.sql.Connection
import java.sql.DriverManager

class JdbcLiveReportDataDriver(
    override val id: String,
    private val url: String,
    private val username: String? = null,
    private val password: String? = null
) : LiveReportDataDriver {
    private val mysql = id.equals("mysql", true)
    private fun connection(): Connection = if (username.isNullOrBlank()) DriverManager.getConnection(url)
        else DriverManager.getConnection(url, username, password ?: "")

    override fun initialize() {
        connection().use { c ->
            if (!mysql) c.createStatement().use {
                it.execute("PRAGMA journal_mode=WAL"); it.execute("PRAGMA busy_timeout=5000")
                val version = it.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                require(version <= DB_SCHEMA_VERSION) { "SQLite report schema $version is newer than supported $DB_SCHEMA_VERSION" }
                if (version in 1 until DB_SCHEMA_VERSION) backupSqlite(version)
            }
            c.createStatement().use { s ->
                s.execute("""CREATE TABLE IF NOT EXISTS starbot_report_session (
                    session_id VARCHAR(160) PRIMARY KEY, uid BIGINT NOT NULL, started_at BIGINT NOT NULL,
                    ended_at BIGINT NULL, schema_version INTEGER NOT NULL, payload ${if (mysql) "LONGTEXT" else "TEXT"} NOT NULL)""")
                s.execute("""CREATE TABLE IF NOT EXISTS starbot_report_event (
                    session_id VARCHAR(160) NOT NULL, event_id VARCHAR(160) NOT NULL,
                    created_at BIGINT NOT NULL, PRIMARY KEY(session_id,event_id))""")
                runCatching { s.execute("CREATE INDEX starbot_report_uid_time ON starbot_report_session(uid,started_at)") }
                s.execute("""CREATE TABLE IF NOT EXISTS starbot_report_migration (
                    migration_id VARCHAR(160) PRIMARY KEY, completed_at BIGINT NOT NULL, details ${if (mysql) "LONGTEXT" else "TEXT"})""")
            }
            if (!mysql) c.createStatement().use { it.execute("PRAGMA user_version=$DB_SCHEMA_VERSION") }
        }
    }

    override fun createOrResume(session: ReportSession): LiveReportSnapshot = transaction { c ->
        load(c, session.sessionId, true) ?: session.snapshot().also { save(c, it) }
    }

    override fun apply(session: ReportSession, eventId: String, delta: ReportDelta): Boolean = transaction { c ->
        val inserted = try {
            c.prepareStatement("INSERT INTO starbot_report_event(session_id,event_id,created_at) VALUES(?,?,?)").use {
                it.setString(1, session.sessionId); it.setString(2, eventId); it.setLong(3, System.currentTimeMillis()); it.executeUpdate() == 1
            }
        } catch (_: java.sql.SQLIntegrityConstraintViolationException) { false }
          catch (e: java.sql.SQLException) {
              if (e.sqlState?.startsWith("23") == true || (!mysql && e.errorCode == 19)) false else throw e
          }
        if (inserted) {
            val snap = load(c, session.sessionId, true) ?: session.snapshot()
            snap.apply(delta); save(c, snap)
        }
        inserted
    }

    override fun snapshot(sessionId: String): LiveReportSnapshot? = connection().use { load(it, sessionId, false)?.copySafe() }
    override fun complete(sessionId: String, endedAt: Long): LiveReportSnapshot? = transaction { c ->
        load(c, sessionId, true)?.also { it.endedAt = endedAt; save(c, it) }?.copySafe()
    }
    override fun recent(uid: Long, limit: Int): List<LiveReportSnapshot> = connection().use { c ->
        c.prepareStatement("SELECT payload FROM starbot_report_session WHERE uid=? AND ended_at IS NOT NULL ORDER BY started_at DESC LIMIT ?").use { p ->
            p.setLong(1, uid); p.setInt(2, limit.coerceIn(1, 100)); p.executeQuery().use { rs ->
                buildList { while (rs.next()) add(decode(rs.getString(1))) }
            }
        }
    }
    override fun health() = runCatching { connection().use { it.isValid(2) } }.fold({ DriverHealth(it) }, { DriverHealth(false, it.message ?: "jdbc error") })

    private fun load(c: Connection, id: String, lock: Boolean): LiveReportSnapshot? {
        val suffix = if (lock && mysql) " FOR UPDATE" else ""
        return c.prepareStatement("SELECT payload FROM starbot_report_session WHERE session_id=?$suffix").use { p ->
            p.setString(1, id); p.executeQuery().use { if (it.next()) decode(it.getString(1)) else null }
        }
    }
    private fun save(c: Connection, s: LiveReportSnapshot) {
        val sql = if (mysql) """INSERT INTO starbot_report_session(session_id,uid,started_at,ended_at,schema_version,payload)
            VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE ended_at=VALUES(ended_at),schema_version=VALUES(schema_version),payload=VALUES(payload)"""
        else """INSERT INTO starbot_report_session(session_id,uid,started_at,ended_at,schema_version,payload) VALUES(?,?,?,?,?,?)
            ON CONFLICT(session_id) DO UPDATE SET ended_at=excluded.ended_at,schema_version=excluded.schema_version,payload=excluded.payload"""
        c.prepareStatement(sql).use { p ->
            p.setString(1, s.sessionId); p.setLong(2, s.uid); p.setLong(3, s.startedAt)
            if (s.endedAt == null) p.setNull(4, java.sql.Types.BIGINT) else p.setLong(4, s.endedAt!!)
            p.setInt(5, s.schemaVersion); p.setString(6, JSON.toJSONString(s)); p.executeUpdate()
        }
    }
    private fun decode(json: String): LiveReportSnapshot = LiveReportSchemaMigration.migrate(
        JSON.parseObject(json, LiveReportSnapshot::class.java))
    private fun <T> transaction(block: (Connection) -> T): T = connection().use { c ->
        c.autoCommit = false
        try { block(c).also { c.commit() } } catch (e: Exception) { c.rollback(); throw e }
    }
    private fun backupSqlite(version: Int) {
        val raw = url.removePrefix("jdbc:sqlite:"); if (raw == url || raw == ":memory:") return
        val source = java.nio.file.Path.of(raw); if (!java.nio.file.Files.exists(source)) return
        val backup = source.resolveSibling("${source.fileName}.schema-$version-${System.currentTimeMillis()}.bak")
        java.nio.file.Files.copy(source, backup)
    }
    companion object { const val DB_SCHEMA_VERSION = 1 }
}
