package net.azisaba.azisync.database.handler.redis

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.database.handler.DatabaseLocationData
import net.azisaba.azisync.database.handler.LocationStorageHandler
import java.util.UUID

class RedisLocationStorageHandler(private val plugin: AziSync) : LocationStorageHandler {
    private val prefix = "azisync:location:"

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
            it.hset(key, "world", "world")
            it.hset(key, "x", "0.0")
            it.hset(key, "y", "0.0")
            it.hset(key, "z", "0.0")
            it.hset(key, "yaw", "0.0")
            it.hset(key, "pitch", "0.0")
            it.hset(key, "bed_spawn", "none")
            it.hset(key, "sync_complete", "true")
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }

    override fun getData(uuid: UUID, playerName: String): DatabaseLocationData? {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        val data = plugin.databaseManager.redisManager?.getResource()?.use { it.hgetAll("$prefix$uuid") } ?: return null
        if (data.isEmpty()) return null
        return DatabaseLocationData(
            data["world"] ?: "world",
            data["x"]?.toDoubleOrNull() ?: 0.0,
            data["y"]?.toDoubleOrNull() ?: 0.0,
            data["z"]?.toDoubleOrNull() ?: 0.0,
            data["yaw"]?.toFloatOrNull() ?: 0f,
            data["pitch"]?.toFloatOrNull() ?: 0f,
            data["bed_spawn"] ?: "none",
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
        uuid: UUID, playerName: String, world: String, x: Double, y: Double, z: Double,
        yaw: Float, pitch: Float, bedSpawn: String, syncStatus: String
    ): Boolean {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        plugin.databaseManager.redisManager?.getResource()?.use {
            val key = "$prefix$uuid"
            it.hset(key, "player_name", playerName)
            it.hset(key, "world", world)
            it.hset(key, "x", x.toString())
            it.hset(key, "y", y.toString())
            it.hset(key, "z", z.toString())
            it.hset(key, "yaw", yaw.toString())
            it.hset(key, "pitch", pitch.toString())
            it.hset(key, "bed_spawn", bedSpawn)
            it.hset(key, "sync_complete", syncStatus)
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }
}
