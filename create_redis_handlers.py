import os

base_dir = r"c:\Users\user\Desktop\Azisaba\AziSync\src\main\kotlin\net\azisaba\azisync\database\handler\redis"

templates = {
    "RedisEconomyStorageHandler.kt": """package net.azisaba.azisync.database.handler.redis

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.database.handler.DatabaseEconomyData
import net.azisaba.azisync.database.handler.EconomyStorageHandler
import java.util.UUID

class RedisEconomyStorageHandler(private val plugin: AziSync) : EconomyStorageHandler {
    private val prefix = "azisync:economy:"

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
            it.hset(key, "money", "0.0")
            it.hset(key, "offline_money", "0.0")
            it.hset(key, "sync_complete", "true")
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }

    override fun getData(uuid: UUID, playerName: String): DatabaseEconomyData? {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        val data = plugin.databaseManager.redisManager?.getResource()?.use { it.hgetAll("$prefix$uuid") } ?: return null
        if (data.isEmpty()) return null
        return DatabaseEconomyData(
            data["money"]?.toDoubleOrNull() ?: 0.0,
            data["offline_money"]?.toDoubleOrNull() ?: 0.0,
            data["sync_complete"] ?: "true",
            data["last_seen"] ?: "0"
        )
    }

    override fun getBalance(uuid: UUID): Double? {
        return plugin.databaseManager.redisManager?.getResource()?.use { it.hget("$prefix$uuid", "money")?.toDoubleOrNull() }
    }

    override fun setBalance(uuid: UUID, balance: Double): Boolean {
        plugin.databaseManager.redisManager?.getResource()?.use { it.hset("$prefix$uuid", "money", balance.toString()) }
        return true
    }

    override fun getOfflineBalance(uuid: UUID): Double? {
        return plugin.databaseManager.redisManager?.getResource()?.use { it.hget("$prefix$uuid", "offline_money")?.toDoubleOrNull() }
    }

    override fun setOfflineMoney(uuid: UUID, amount: Double): Boolean {
        plugin.databaseManager.redisManager?.getResource()?.use { it.hset("$prefix$uuid", "offline_money", amount.toString()) }
        return true
    }

    override fun setSyncStatus(uuid: UUID, playerName: String, status: String): Boolean {
        plugin.databaseManager.redisManager?.getResource()?.use { 
            it.hset("$prefix$uuid", "sync_complete", status)
            it.hset("$prefix$uuid", "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }

    override fun setData(uuid: UUID, playerName: String, money: Double, syncStatus: String): Boolean {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        plugin.databaseManager.redisManager?.getResource()?.use { 
            val key = "$prefix$uuid"
            it.hset(key, "player_name", playerName)
            it.hset(key, "money", money.toString())
            it.hset(key, "sync_complete", syncStatus)
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }
}
""",
    "RedisEnderchestStorageHandler.kt": """package net.azisaba.azisync.database.handler.redis

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
""",
    "RedisExperienceStorageHandler.kt": """package net.azisaba.azisync.database.handler.redis

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
""",
    "RedisHealthStorageHandler.kt": """package net.azisaba.azisync.database.handler.redis

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
""",
    "RedisInventoryStorageHandler.kt": """package net.azisaba.azisync.database.handler.redis

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
""",
    "RedisLocationStorageHandler.kt": """package net.azisaba.azisync.database.handler.redis

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
""",
    "RedisPotionEffectsStorageHandler.kt": """package net.azisaba.azisync.database.handler.redis

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.database.handler.DatabasePotionData
import net.azisaba.azisync.database.handler.PotionEffectsStorageHandler
import java.util.UUID

class RedisPotionEffectsStorageHandler(private val plugin: AziSync) : PotionEffectsStorageHandler {
    private val prefix = "azisync:potions:"

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
            it.hset(key, "potion_effects", "none")
            it.hset(key, "sync_complete", "true")
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }

    override fun getData(uuid: UUID, playerName: String): DatabasePotionData? {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        val data = plugin.databaseManager.redisManager?.getResource()?.use { it.hgetAll("$prefix$uuid") } ?: return null
        if (data.isEmpty()) return null
        return DatabasePotionData(
            data["potion_effects"] ?: "none",
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

    override fun setData(uuid: UUID, playerName: String, potionEffects: String, syncStatus: String): Boolean {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        plugin.databaseManager.redisManager?.getResource()?.use {
            val key = "$prefix$uuid"
            it.hset(key, "player_name", playerName)
            it.hset(key, "potion_effects", potionEffects)
            it.hset(key, "sync_complete", syncStatus)
            it.hset(key, "last_seen", System.currentTimeMillis().toString())
        }
        return true
}
}
""",
    "RedisCraftGuiStorageHandler.kt": """package net.azisaba.azisync.database.handler.redis

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.database.handler.DatabaseCraftGuiData
import net.azisaba.azisync.database.handler.CraftGuiStorageHandler
import java.util.UUID

class RedisCraftGuiStorageHandler(private val plugin: AziSync) : CraftGuiStorageHandler {
    private val prefix = "azisync:craftgui:"

    override fun getSyncStatus(uuid: UUID): String? {
        return plugin.databaseManager.redisManager?.getResource()?.use { it.hget("${"$"}prefix${"$"}uuid", "sync_complete") }
    }

    override fun hasAccount(uuid: UUID): Boolean {
        return plugin.databaseManager.redisManager?.getResource()?.use { it.exists("${"$"}prefix${"$"}uuid") } ?: false
    }

    override fun createAccount(uuid: UUID, playerName: String): Boolean {
        plugin.databaseManager.redisManager?.getResource()?.use {
            val key = "${"$"}prefix${"$"}uuid"
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
        val data = plugin.databaseManager.redisManager?.getResource()?.use { it.hgetAll("${"$"}prefix${"$"}uuid") } ?: return null
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
            it.hset("${"$"}prefix${"$"}uuid", "sync_complete", status)
            it.hset("${"$"}prefix${"$"}uuid", "last_seen", System.currentTimeMillis().toString())
        }
        return true
    }

    override fun setData(
        uuid: UUID, playerName: String, soundEnabled: Boolean, showResultItems: Boolean,
        craftableOnly: Boolean, stashEnabled: Boolean, syncStatus: String
    ): Boolean {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        plugin.databaseManager.redisManager?.getResource()?.use {
            val key = "${"$"}prefix${"$"}uuid"
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
"""
}

for filename, content in templates.items():
    with open(os.path.join(base_dir, filename), "w", encoding="utf-8") as f:
        f.write(content)
