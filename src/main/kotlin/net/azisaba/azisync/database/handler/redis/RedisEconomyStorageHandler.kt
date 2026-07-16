package net.azisaba.azisync.database.handler.redis

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

    override fun addOfflineMoney(uuid: UUID, amount: Double): Boolean {
        plugin.databaseManager.redisManager?.getResource()?.use { it.hincrByFloat("$prefix$uuid", "offline_money", amount) }
        return true
    }

    override fun consumeOfflineMoney(uuid: UUID): Double? {
        return plugin.databaseManager.redisManager?.getResource()?.use {
            val amount = it.hget("$prefix$uuid", "offline_money")?.toDoubleOrNull() ?: return@use null
            it.hset("$prefix$uuid", "offline_money", "0.0")
            amount
        }
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
