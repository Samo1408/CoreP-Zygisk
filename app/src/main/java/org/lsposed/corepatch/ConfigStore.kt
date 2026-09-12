package org.lsposed.corepatch

import java.io.File
import java.util.Properties

/**
 * Shared configuration for the normal manager app and system_server.
 *
 * The Zygisk module is installed with root, so the manager writes this small
 * file through `su`; system_server reads it directly after specialization.
 */
object ConfigStore {
    private const val FILE = "/data/adb/modules/corepatch/config.properties"

    @Synchronized
    fun get(key: String): Boolean {
        return try {
            val p = Properties()
            File(FILE).inputStream().use { p.load(it) }
            p.getProperty(key)?.equals("true", ignoreCase = true) == true
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    fun set(key: String, value: Boolean) {
        // Manager-side implementation uses a tiny root shell writer.
        try {
            val escapedKey = key.replace("'", "'\\''")
            val v = if (value) "true" else "false"
            val command =
                "mkdir -p /data/adb/modules/corepatch; " +
                "touch /data/adb/modules/corepatch/config.properties; " +
                "chmod 0644 /data/adb/modules/corepatch/config.properties; " +
                "grep -v '^$escapedKey=' /data/adb/modules/corepatch/config.properties > /data/adb/modules/corepatch/config.properties.tmp 2>/dev/null || true; " +
                "echo '$escapedKey=$v' >> /data/adb/modules/corepatch/config.properties.tmp; " +
                "mv /data/adb/modules/corepatch/config.properties.tmp /data/adb/modules/corepatch/config.properties; " +
                "chmod 0644 /data/adb/modules/corepatch/config.properties"
            Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor()
        } catch (_: Throwable) {
            // Fallback for development/non-root builds.
            try {
                val local = File("/data/local/tmp/corepatch.properties")
                val p = Properties()
                if (local.exists()) local.inputStream().use { p.load(it) }
                p[key] = value.toString()
                local.outputStream().use { p.store(it, null) }
            } catch (_: Throwable) {}
        }
    }
}
