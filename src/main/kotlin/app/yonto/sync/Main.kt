package app.yonto.sync

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val config = Config.fromEnvironment()
    if (args.isNotEmpty()) {
        val code = Store(config.database).use { admin(args.toList(), it, ::println) }
        exitProcess(code)
    }
    val server = SyncServer(config).start()
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    val mode = if (config.licensingUrl != null) "licence checks against ${config.licensingUrl}" else "no licence checks"
    val accounts = config.accounts?.let { "${it.size} allowed accounts" } ?: "any account may be made"
    println("Yonto sync server on port ${server.port}, database ${config.database}, replica ${server.store.replica}; $mode; $accounts")
}
