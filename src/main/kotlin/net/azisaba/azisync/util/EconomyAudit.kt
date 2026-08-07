package net.azisaba.azisync.util

import net.azisaba.azisync.AziSync
import java.util.UUID
import java.util.logging.Level

object EconomyAudit {
    fun info(
        plugin: AziSync,
        action: String,
        uuid: UUID? = null,
        playerName: String? = null,
        vararg details: Pair<String, Any?>
    ) = log(plugin, Level.INFO, action, uuid, playerName, null, details)

    fun warning(
        plugin: AziSync,
        action: String,
        uuid: UUID? = null,
        playerName: String? = null,
        error: Throwable? = null,
        vararg details: Pair<String, Any?>
    ) = log(plugin, Level.WARNING, action, uuid, playerName, error, details)

    fun severe(
        plugin: AziSync,
        action: String,
        uuid: UUID? = null,
        playerName: String? = null,
        error: Throwable? = null,
        vararg details: Pair<String, Any?>
    ) = log(plugin, Level.SEVERE, action, uuid, playerName, error, details)

    private fun log(
        plugin: AziSync,
        level: Level,
        action: String,
        uuid: UUID?,
        playerName: String?,
        error: Throwable?,
        details: Array<out Pair<String, Any?>>
    ) {
        if (!plugin.config.getBoolean("general.economyAudit.enabled", true)) return
        if (level == Level.INFO && !plugin.config.getBoolean("general.economyAudit.logSuccess", true)) return

        val configuredServerId = plugin.config.getString("general.serverId", "")?.trim().orEmpty()
        val serverId = configuredServerId.ifEmpty { "port-${plugin.server.port}" }
        val fields = ArrayList<Pair<String, Any?>>(details.size + 7).apply {
            add("action" to action)
            add("server" to serverId)
            add("player" to playerName)
            add("uuid" to uuid)
            add("thread" to Thread.currentThread().name)
            addAll(details)
            if (error != null) {
                add("errorType" to error.javaClass.simpleName)
                add("error" to error.message)
            }
        }
        val message = fields.joinToString(prefix = "[EconomyAudit] ", separator = " ") { (key, value) ->
            "$key=${format(value)}"
        }
        plugin.logger.log(level, message)
        if (error != null && plugin.config.getBoolean("general.economyAudit.logStackTraces", true)) {
            plugin.logger.log(level, "[EconomyAudit] stacktrace action=$action uuid=${uuid ?: "none"}", error)
        }
    }

    private fun format(value: Any?): String {
        if (value == null) return "none"
        val sanitized = value.toString().replace(Regex("[\\r\\n\\t]+"), " ")
        return if (sanitized.any(Char::isWhitespace)) "\"${sanitized.replace("\"", "'")}\"" else sanitized
    }
}
