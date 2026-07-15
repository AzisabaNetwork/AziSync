package net.azisaba.azisync.database.handler.redis

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.database.handler.AdvancementStorageHandler
import net.azisaba.azisync.database.handler.DatabaseAdvancementData
import java.util.UUID

class RedisAdvancementStorageHandler(private val plugin: AziSync) : AdvancementStorageHandler {
    private val prefix = "azisync:advancement:"

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
            it.hset(key, "advancements", "none")
            it.hset(key, "sync_complete", "true")
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }

    override fun getData(uuid: UUID, playerName: String): DatabaseAdvancementData? {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        val data = plugin.databaseManager.redisManager?.getResource()?.use { it.hgetAll("$prefix$uuid") } ?: return null
        if (data.isEmpty()) return null
        return DatabaseAdvancementData(
            data["advancements"] ?: "none",
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

    override fun setData(uuid: UUID, playerName: String, advancements: String, syncStatus: String): Boolean {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        plugin.databaseManager.redisManager?.getResource()?.use {
            val key = "$prefix$uuid"
            it.hset(key, "player_name", playerName)
            it.hset(key, "advancements", advancements)
            it.hset(key, "sync_complete", syncStatus)
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }
}
