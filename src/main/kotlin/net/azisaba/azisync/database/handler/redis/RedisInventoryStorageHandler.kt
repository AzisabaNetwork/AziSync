package net.azisaba.azisync.database.handler.redis

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.database.handler.DatabaseInventoryData
import net.azisaba.azisync.database.handler.InventoryStorageHandler
import java.util.UUID

class RedisInventoryStorageHandler(private val plugin: AziSync) : InventoryStorageHandler {
    private val prefix = "azisync:inventory:"

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
            it.hset(key, "inventory", "none")
            it.hset(key, "armor", "none")
            it.hset(key, "hotbar_slot", "0")
            it.hset(key, "gamemode", "0")
            it.hset(key, "sync_complete", "true")
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }

    override fun getData(uuid: UUID, playerName: String): DatabaseInventoryData? {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        val data = plugin.databaseManager.redisManager?.getResource()?.use { it.hgetAll("$prefix$uuid") } ?: return null
        if (data.isEmpty()) return null
        return DatabaseInventoryData(
            data["inventory"] ?: "none",
            data["armor"] ?: "none",
            data["hotbar_slot"]?.toIntOrNull() ?: 0,
            data["gamemode"]?.toIntOrNull() ?: 0,
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

    override fun setData(uuid: UUID, playerName: String, inventory: String, armor: String, hotbarSlot: Int, gamemode: Int, syncStatus: String): Boolean {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        plugin.databaseManager.redisManager?.getResource()?.use {
            val key = "$prefix$uuid"
            it.hset(key, "player_name", playerName)
            it.hset(key, "inventory", inventory)
            it.hset(key, "armor", armor)
            it.hset(key, "hotbar_slot", hotbarSlot.toString())
            it.hset(key, "gamemode", gamemode.toString())
            it.hset(key, "sync_complete", syncStatus)
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }
}
