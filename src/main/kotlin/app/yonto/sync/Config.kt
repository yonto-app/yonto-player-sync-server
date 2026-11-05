package app.yonto.sync

data class Config(
    val port: Int = 8080,
    val database: String = "/data/sync.db",
    /** Licence ids that may make an account; null lets any. */
    val accounts: Set<String>? = null,
    val licensingUrl: String? = null,
    /** The product's licence public keys, uncompressed P-256 points in base64, comma-separated. */
    val licenceKeys: String? = null,
    val licenceAllowSeconds: Long = 24 * 60 * 60,
    val licenceRefuseSeconds: Long = 10 * 60,
    val licenceStaleSeconds: Long = 7 * 24 * 60 * 60,
    val maxRecordBytes: Int = 64 * 1024,
    val maxAccountBytes: Long = 32L * 1024 * 1024,
    val maxAccountRecords: Int = 10_000,
    val maxDevices: Int = 20,
    val maxBatchRecords: Int = 500,
    val maxBodyBytes: Int = 8 * 1024 * 1024,
    val accountRequestsPerMinute: Int = 600,
    val addressRequestsPerMinute: Int = 1200,
    /** Read the client's address from the last `X-Forwarded-For` entry, which the proxy in front wrote. */
    val behindProxy: Boolean = false,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): Config {
            val defaults = Config()
            fun int(name: String, default: Int) = env[name]?.trim()?.takeIf { it.isNotEmpty() }?.toInt() ?: default
            fun long(name: String, default: Long) = env[name]?.trim()?.takeIf { it.isNotEmpty() }?.toLong() ?: default
            return Config(
                port = int("PORT", defaults.port),
                database = env["SYNC_DB"]?.takeIf { it.isNotBlank() } ?: defaults.database,
                accounts = env["SYNC_ACCOUNTS"]?.takeIf { it.isNotBlank() }
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet(),
                licensingUrl = env["YONTO_LICENSING_URL"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() },
                licenceKeys = env["YONTO_LICENCE_KEYS"]?.takeIf { it.isNotBlank() },
                licenceAllowSeconds = long("YONTO_LICENCE_ALLOW_SECONDS", defaults.licenceAllowSeconds),
                licenceRefuseSeconds = long("YONTO_LICENCE_REFUSE_SECONDS", defaults.licenceRefuseSeconds),
                licenceStaleSeconds = long("YONTO_LICENCE_STALE_SECONDS", defaults.licenceStaleSeconds),
                maxRecordBytes = int("SYNC_MAX_RECORD_BYTES", defaults.maxRecordBytes),
                maxAccountBytes = long("SYNC_MAX_ACCOUNT_BYTES", defaults.maxAccountBytes),
                maxAccountRecords = int("SYNC_MAX_ACCOUNT_RECORDS", defaults.maxAccountRecords),
                maxDevices = int("SYNC_MAX_DEVICES", defaults.maxDevices),
                maxBatchRecords = int("SYNC_MAX_BATCH_RECORDS", defaults.maxBatchRecords),
                maxBodyBytes = int("SYNC_MAX_BODY_BYTES", defaults.maxBodyBytes),
                accountRequestsPerMinute = int("SYNC_RATE_ACCOUNT", defaults.accountRequestsPerMinute),
                addressRequestsPerMinute = int("SYNC_RATE_ADDRESS", defaults.addressRequestsPerMinute),
                behindProxy = env["SYNC_BEHIND_PROXY"]?.trim()?.lowercase() in setOf("1", "true", "yes"),
            )
        }
    }
}
