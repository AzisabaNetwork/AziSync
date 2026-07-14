package net.azisaba.azisync.database.handler.redis

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.database.handler.DatabaseCraftGuiData
import net.azisaba.azisync.database.handler.CraftGuiStorageHandler
import java.util.UUID

class RedisCraftGuiStorageHandler(private val plugin: AziSync) : CraftGuiStorageHandler {
    private val prefix = "azisync:craftgui:"

    override fun getSyncStatus(uuid: UUID): String? {
        return plugin.databaseManager.redisManager?.getResource()?.use { it.hget("$prefix$uuid", "sync_complete") }
    }

    override fun hasAccount(uuid: UUID): Boolean {
        return plugin.databaseManager.redisManager?.getResource()?.use { it.exists("$prefix$uuid") } ?: false
    }

    override fun createAccount(uuid: UUID, playerName: String): Boolean {
        plugin.databaseManager.redisManager?.getResource()?.use {
            val key = "$prefix$uuid"
            it.hset(key, "player_name", playerName)
            it.hset(key, "sound_enabled", "true")
            it.hset(key, "show_result_items", "true")
            it.hset(key, "craftable_only", "false")
            it.hset(key, "stash_enabled", "false")
            it.hset(key, "sync_complete", "true")
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }

    override fun getData(uuid: UUID, playerName: String): DatabaseCraftGuiData? {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        val data = plugin.databaseManager.redisManager?.getResource()?.use { it.hgetAll("$prefix$uuid") } ?: return null
        if (data.isEmpty()) return null
        return DatabaseCraftGuiData(
            data["sound_enabled"]?.toBoolean() ?: true,
            data["show_result_items"]?.toBoolean() ?: true,
            data["craftable_only"]?.toBoolean() ?: false,
            data["stash_enabled"]?.toBoolean() ?: false,
            data["sync_complete"] ?: "true",
            data["last_seen"] ?: "0"
        )
    }

    override fun setSyncStatus(uuid: UUID, playerName: String, status: String): Boolean {
        plugin.databaseManager.redisManager?.getResource()?.use {
            it.hset("$prefix$uuid", "sync_complete", status)
            it.hset("$prefix$uuid", "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }

    override fun setData(
        uuid: UUID, playerName: String, soundEnabled: Boolean, showResultItems: Boolean,
        craftableOnly: Boolean, stashEnabled: Boolean, syncStatus: String
    ): Boolean {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        plugin.databaseManager.redisManager?.getResource()?.use {
            val key = "$prefix$uuid"
            it.hset(key, "player_name", playerName)
            it.hset(key, "sound_enabled", soundEnabled.toString())
            it.hset(key, "show_result_items", showResultItems.toString())
            it.hset(key, "craftable_only", craftableOnly.toString())
            it.hset(key, "stash_enabled", stashEnabled.toString())
            it.hset(key, "sync_complete", syncStatus)
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }
}
