package net.azisaba.azisync.database.handler.redis

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.database.handler.DatabaseHealthData
import net.azisaba.azisync.database.handler.HealthStorageHandler
import java.util.UUID

class RedisHealthStorageHandler(private val plugin: AziSync) : HealthStorageHandler {
    private val prefix = "azisync:health:"

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
            it.hset(key, "health", "20.0")
            it.hset(key, "health_scale", "20.0")
            it.hset(key, "max_health", "20.0")
            it.hset(key, "food", "20")
            it.hset(key, "saturation", "5.0")
            it.hset(key, "air", "300")
            it.hset(key, "max_air", "300")
            it.hset(key, "sync_complete", "true")
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }

    override fun getData(uuid: UUID, playerName: String): DatabaseHealthData? {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        val data = plugin.databaseManager.redisManager?.getResource()?.use { it.hgetAll("$prefix$uuid") } ?: return null
        if (data.isEmpty()) return null
        return DatabaseHealthData(
            data["health"]?.toDoubleOrNull() ?: 20.0,
            data["health_scale"]?.toDoubleOrNull() ?: 20.0,
            data["max_health"]?.toDoubleOrNull() ?: 20.0,
            data["food"]?.toIntOrNull() ?: 20,
            data["saturation"] ?: "5.0",
            data["air"]?.toIntOrNull() ?: 300,
            data["max_air"]?.toIntOrNull() ?: 300,
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
        uuid: UUID, playerName: String, health: Double, healthScale: Double, maxHealth: Double,
        food: Int, saturation: String, air: Int, maxAir: Int, syncStatus: String
    ): Boolean {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        plugin.databaseManager.redisManager?.getResource()?.use {
            val key = "$prefix$uuid"
            it.hset(key, "player_name", playerName)
            it.hset(key, "health", health.toString())
            it.hset(key, "health_scale", healthScale.toString())
            it.hset(key, "max_health", maxHealth.toString())
            it.hset(key, "food", food.toString())
            it.hset(key, "saturation", saturation)
            it.hset(key, "air", air.toString())
            it.hset(key, "max_air", maxAir.toString())
            it.hset(key, "sync_complete", syncStatus)
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }
}
