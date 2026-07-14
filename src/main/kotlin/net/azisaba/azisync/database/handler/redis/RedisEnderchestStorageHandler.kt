package net.azisaba.azisync.database.handler.redis

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.database.handler.DatabaseEnderchestData
import net.azisaba.azisync.database.handler.EnderchestStorageHandler
import java.util.UUID

class RedisEnderchestStorageHandler(private val plugin: AziSync) : EnderchestStorageHandler {
    private val prefix = "azisync:enderchest:"

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
            it.hset(key, "enderchest", "none")
            it.hset(key, "sync_complete", "true")
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }

    override fun getData(uuid: UUID, playerName: String): DatabaseEnderchestData? {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        val data = plugin.databaseManager.redisManager?.getResource()?.use { it.hgetAll("$prefix$uuid") } ?: return null
        if (data.isEmpty()) return null
        return DatabaseEnderchestData(
            data["enderchest"] ?: "none",
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

    override fun setData(uuid: UUID, playerName: String, enderchest: String, syncStatus: String): Boolean {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        plugin.databaseManager.redisManager?.getResource()?.use {
            val key = "$prefix$uuid"
            it.hset(key, "player_name", playerName)
            it.hset(key, "enderchest", enderchest)
            it.hset(key, "sync_complete", syncStatus)
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }
}
