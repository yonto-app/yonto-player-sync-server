package app.yonto.sync

/** The commands the server's binary runs instead of serving, against the database in `SYNC_DB`; the exit code. */
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
    else -> {
        out("usage: yonto-sync-server [accounts | delete-account <licence id> | new-replica]")
        2
    }
}
