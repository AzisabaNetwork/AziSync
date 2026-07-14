package net.azisaba.azisync.database.handler.redis

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.database.handler.DatabaseExperienceData
import net.azisaba.azisync.database.handler.ExperienceStorageHandler
import java.util.UUID

class RedisExperienceStorageHandler(private val plugin: AziSync) : ExperienceStorageHandler {
    private val prefix = "azisync:experience:"

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
            it.hset(key, "exp", "0.0")
            it.hset(key, "exp_to_level", "0")
            it.hset(key, "total_exp", "0")
            it.hset(key, "exp_lvl", "0")
            it.hset(key, "sync_complete", "true")
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }

    override fun getData(uuid: UUID, playerName: String): DatabaseExperienceData? {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        val data = plugin.databaseManager.redisManager?.getResource()?.use { it.hgetAll("$prefix$uuid") } ?: return null
        if (data.isEmpty()) return null
        return DatabaseExperienceData(
            data["exp"]?.toFloatOrNull() ?: 0f,
            data["exp_to_level"]?.toIntOrNull() ?: 0,
            data["total_exp"]?.toIntOrNull() ?: 0,
            data["exp_lvl"]?.toIntOrNull() ?: 0,
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

    override fun setData(uuid: UUID, playerName: String, exp: Float, expToLevel: Int, totalExp: Int, expLvl: Int, syncStatus: String): Boolean {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        plugin.databaseManager.redisManager?.getResource()?.use {
            val key = "$prefix$uuid"
            it.hset(key, "player_name", playerName)
            it.hset(key, "exp", exp.toString())
            it.hset(key, "exp_to_level", expToLevel.toString())
            it.hset(key, "total_exp", totalExp.toString())
            it.hset(key, "exp_lvl", expLvl.toString())
            it.hset(key, "sync_complete", syncStatus)
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }
}
