package app.yonto.sync

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals

/** What only PostgreSQL does to a server: its connections can be ended under it. */
class PostgresTest {
    @BeforeEach
    fun onPostgres() = assumeTrue(TestDatabases.postgres, "PostgreSQL's alone")

    @Test
    fun `a connection PostgreSQL ended costs one request, and the next gets a new one`() = Harness().use { h ->
        h.createAccount()
        h.push(record())
        val database = h.database as Database.Postgres
        database.connect().use { admin ->
            val ended = admin.prepareStatement(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()",
            ).use { statement -> statement.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getBoolean(1) else null }.toList() } }
            assertEquals(listOf(true), ended)
        }

        assertEquals(500, h.pull().status)
        assertEquals(1, h.pulled().size)
        assertEquals(200, h.push(record(id = idOf(2))).status)
    }
}
