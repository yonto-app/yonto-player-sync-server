package app.yonto.sync

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** The database the suite runs against: SQLite, or PostgreSQL when `postgresTest` sets `yonto.sync.testDatabase`. */
object TestDatabases {
    val postgres: Boolean = System.getProperty("yonto.sync.testDatabase") == "postgres"

    private val server: EmbeddedPostgres by lazy {
        EmbeddedPostgres.builder().start().also { started -> Runtime.getRuntime().addShutdownHook(Thread { started.close() }) }
    }
    private val made = AtomicInteger()

    /** An empty database of its own for each call: a file in [dir], or a new database on the one embedded server. */
    fun fresh(dir: File): Database = if (!postgres) {
        Database.Sqlite(File(dir, "sync.db").path)
    } else {
        val name = "sync_${made.incrementAndGet()}"
        server.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        Database.Postgres("postgresql://localhost:${server.port}/$name", "postgres", "postgres")
    }
}
