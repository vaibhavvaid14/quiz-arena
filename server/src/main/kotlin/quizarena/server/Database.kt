package quizarena.server

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLite connection handling, transactions and schema migrations.
 *
 * Like the previous implementation this keeps a single connection rather than a
 * pool. SQLite has one writer regardless, WAL lets readers run alongside it, and
 * a single connection keeps `BEGIN IMMEDIATE` semantics easy to reason about.
 * JDBC connections are not thread-safe, so every access goes through [lock];
 * Ktor serves requests on many threads, unlike the previous server.
 */
class Db(val conn: Connection) : AutoCloseable {
    private val lock = ReentrantLock()
    private var depth = 0

    /** Runs [block] holding the connection lock, outside any transaction. */
    fun <T> read(block: (Connection) -> T): T = lock.withLock { block(conn) }

    /**
     * BEGIN IMMEDIATE ... COMMIT, rolled back on any exception.
     *
     * IMMEDIATE takes the write lock up front, so two requests racing on the
     * same attempt (a double-clicked answer, say) are serialised instead of both
     * reading stale state. Nested calls join the outer transaction rather than
     * issuing a second BEGIN, which SQLite would reject.
     */
    fun <T> transaction(block: (Connection) -> T): T = lock.withLock {
        if (depth > 0) {
            depth++
            try {
                return@withLock block(conn)
            } finally {
                depth--
            }
        }
        exec("BEGIN IMMEDIATE")
        depth = 1
        try {
            val result = block(conn)
            exec("COMMIT")
            result
        } catch (t: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw t
        } finally {
            depth = 0
        }
    }

    private fun exec(sql: String) = conn.createStatement().use { it.execute(sql) }

    override fun close() = conn.close()

    companion object {
        const val MEMORY = ":memory:"

        /**
         * Opens a connection configured for this app:
         * foreign keys enforced (SQLite leaves them off), WAL so readers never
         * block the writer, and a busy timeout so a contended write waits rather
         * than failing immediately.
         */
        fun connect(path: String): Db {
            val url = if (path == MEMORY) "jdbc:sqlite::memory:" else "jdbc:sqlite:$path"
            val conn = DriverManager.getConnection(url)
            conn.autoCommit = true // transactions are issued explicitly
            conn.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys = ON")
                st.execute("PRAGMA busy_timeout = 5000")
                if (path != MEMORY) {
                    st.execute("PRAGMA journal_mode = WAL")
                    st.execute("PRAGMA synchronous = NORMAL")
                }
            }
            return Db(conn)
        }

        /**
         * Ordered migrations. `PRAGMA user_version` records how many have been
         * applied, so upgrading an existing quiz.db only runs the new ones.
         */
        private val MIGRATIONS: List<String> by lazy {
            listOf(resourceText("/schema.sql"))
        }

        fun resourceText(path: String): String =
            Db::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
                ?: error("Missing resource $path")

        /** Applies pending migrations. Returns the resulting schema version. */
        fun migrate(db: Db): Int = db.read { conn ->
            val version = conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            }
            MIGRATIONS.drop(version).forEachIndexed { offset, script ->
                val target = version + offset + 1
                conn.createStatement().use { st ->
                    st.execute("BEGIN")
                    try {
                        // JDBC will not run a multi-statement script in one call,
                        // so the migration is split. The schema has no triggers,
                        // so no semicolon appears inside a statement body.
                        splitStatements(script).forEach { st.execute(it) }
                        st.execute("PRAGMA user_version = $target")
                        st.execute("COMMIT")
                    } catch (t: Throwable) {
                        runCatching { st.execute("ROLLBACK") }
                        throw t
                    }
                }
            }
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            }
        }

        internal fun splitStatements(script: String): List<String> = script
            .lineSequence()
            .map { it.substringBefore("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}

// ----------------------------------------------------------------- helpers

/** Maps every row of a query to [map]. */
fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
        st.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

/** Maps the first row, or null when the query returns nothing. */
fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    prepareStatement(sql).use { st ->
        params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
        st.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
    }

/** Runs a statement, returning the number of affected rows. */
fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { st ->
        params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
        st.executeUpdate()
    }

/** SQLite stores booleans as 0/1. */
fun ResultSet.bool(column: String): Boolean = getInt(column) != 0

/** Reads a column that may be SQL NULL as a nullable Long. */
fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

/** Reads a column that may be SQL NULL as a nullable Int. */
fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
