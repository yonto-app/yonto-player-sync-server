package app.yonto.sync

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/** Where the server keeps what it holds: a SQLite file, the default a self-hoster needs nothing else for, or PostgreSQL. */
sealed interface Database {
    fun connect(): Connection

    data class Sqlite(val path: String) : Database {
        override fun connect(): Connection {
            File(path).absoluteFile.parentFile?.mkdirs()
            return DriverManager.getConnection("jdbc:sqlite:$path").apply {
                createStatement().use {
                    it.execute("PRAGMA journal_mode=WAL")
                    it.execute("PRAGMA foreign_keys=ON")
                    it.execute("PRAGMA busy_timeout=5000")
                }
            }
        }

        override fun toString() = path
    }

    /** [url] is `postgresql://host[:port]/database`. */
    class Postgres(val url: String, val user: String, private val password: String) : Database {
        init {
            require(url.startsWith("postgresql://")) { "SYNC_DATABASE_URL must start postgresql://, not ${url.substringBefore("://")}://" }
        }

        override fun connect(): Connection = DriverManager.getConnection(
            "jdbc:$url",
            Properties().apply {
                setProperty("user", user)
                setProperty("password", password)
                setProperty("ApplicationName", "yonto-sync-server")
            },
        )

        override fun equals(other: Any?) = other is Postgres && other.url == url && other.user == user && other.password == password

        override fun hashCode() = url.hashCode() * 31 + user.hashCode()

        override fun toString() = "$url as $user"
    }
}
