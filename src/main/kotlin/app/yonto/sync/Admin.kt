package app.yonto.sync

import java.io.File

/** The commands the server's binary runs instead of serving, against the database it is configured with; the exit code. */
fun admin(args: List<String>, store: Store, out: (String) -> Unit): Int = when {
    args == listOf("accounts") -> {
        store.accountIds().forEach(out)
        0
    }
    args.size == 2 && args[0] == "delete-account" -> if (store.deleteAccount(args[1]) > 0) {
        out("Deleted the account, with its records, devices, wraps, joins and any reset waiting.")
        0
    } else {
        out("No such account.")
        1
    }
    args == listOf("new-replica") -> {
        out("Replica ${store.newReplica()}: devices push what they hold again once the server starts on it.")
        0
    }
    args.size == 2 && args[0] == "import-sqlite" -> when {
        !File(args[1]).isFile -> {
            out("No SQLite database at ${args[1]}.")
            1
        }
        store.accountIds().isNotEmpty() -> {
            out("This database already holds accounts: import only into an empty one.")
            1
        }
        else -> {
            val rows = Store(Database.Sqlite(args[1])).use { store.copyFrom(it) }
            out("Copied $rows rows and the replica id: the devices carry on where they were once the server starts on it.")
            0
        }
    }
    else -> {
        out("usage: yonto-sync-server [accounts | delete-account <licence id> | new-replica | import-sqlite <file>]")
        2
    }
}
