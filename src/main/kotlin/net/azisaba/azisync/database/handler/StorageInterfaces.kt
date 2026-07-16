package net.azisaba.azisync.database.handler

import java.util.UUID

interface EconomyStorageHandler {
    fun getSyncStatus(uuid: UUID): String?
    fun hasAccount(uuid: UUID): Boolean
    fun createAccount(uuid: UUID, playerName: String): Boolean
    fun getData(uuid: UUID, playerName: String): DatabaseEconomyData?
    fun getBalance(uuid: UUID): Double?
    fun setBalance(uuid: UUID, balance: Double): Boolean
    fun getOfflineBalance(uuid: UUID): Double?
    fun setOfflineMoney(uuid: UUID, amount: Double): Boolean
    fun addOfflineMoney(uuid: UUID, amount: Double): Boolean
    fun consumeOfflineMoney(uuid: UUID): Double?
    fun setSyncStatus(uuid: UUID, playerName: String, status: String): Boolean
    fun setData(uuid: UUID, playerName: String, money: Double, syncStatus: String): Boolean
}

interface EnderchestStorageHandler {
    fun getSyncStatus(uuid: UUID): String?
    fun hasAccount(uuid: UUID): Boolean
    fun createAccount(uuid: UUID, playerName: String): Boolean
    fun getData(uuid: UUID, playerName: String): DatabaseEnderchestData?
    fun setSyncStatus(uuid: UUID, playerName: String, status: String): Boolean
    fun setData(uuid: UUID, playerName: String, enderchest: String, syncStatus: String): Boolean
}

interface ExperienceStorageHandler {
    fun getSyncStatus(uuid: UUID): String?
    fun hasAccount(uuid: UUID): Boolean
    fun createAccount(uuid: UUID, playerName: String): Boolean
    fun getData(uuid: UUID, playerName: String): DatabaseExperienceData?
    fun setSyncStatus(uuid: UUID, playerName: String, status: String): Boolean
    fun setData(uuid: UUID, playerName: String, exp: Float, expToLevel: Int, totalExp: Int, expLvl: Int, syncStatus: String): Boolean
}

interface HealthStorageHandler {
    fun getSyncStatus(uuid: UUID): String?
    fun hasAccount(uuid: UUID): Boolean
    fun createAccount(uuid: UUID, playerName: String): Boolean
    fun getData(uuid: UUID, playerName: String): DatabaseHealthData?
    fun setSyncStatus(uuid: UUID, playerName: String, status: String): Boolean
    fun setData(
        uuid: UUID, playerName: String, health: Double, healthScale: Double, maxHealth: Double,
        food: Int, saturation: String, air: Int, maxAir: Int, syncStatus: String
    ): Boolean
}

interface InventoryStorageHandler {
    fun getSyncStatus(uuid: UUID): String?
    fun hasAccount(uuid: UUID): Boolean
    fun createAccount(uuid: UUID, playerName: String): Boolean
    fun getData(uuid: UUID, playerName: String): DatabaseInventoryData?
    fun setSyncStatus(uuid: UUID, playerName: String, status: String): Boolean
    fun setData(uuid: UUID, playerName: String, inventory: String, armor: String, hotbarSlot: Int, gamemode: Int, syncStatus: String): Boolean
}

interface LocationStorageHandler {
    fun getSyncStatus(uuid: UUID): String?
    fun hasAccount(uuid: UUID): Boolean
    fun createAccount(uuid: UUID, playerName: String): Boolean
    fun getData(uuid: UUID, playerName: String): DatabaseLocationData?
    fun setSyncStatus(uuid: UUID, playerName: String, status: String): Boolean
    fun setData(
        uuid: UUID, playerName: String, world: String, x: Double, y: Double, z: Double,
        yaw: Float, pitch: Float, bedSpawn: String, syncStatus: String
    ): Boolean
}

interface PotionEffectsStorageHandler {
    fun getSyncStatus(uuid: UUID): String?
    fun hasAccount(uuid: UUID): Boolean
    fun createAccount(uuid: UUID, playerName: String): Boolean
    fun getData(uuid: UUID, playerName: String): DatabasePotionData?
    fun setSyncStatus(uuid: UUID, playerName: String, status: String): Boolean
    fun setData(uuid: UUID, playerName: String, potionEffects: String, syncStatus: String): Boolean
}

data class DatabaseAdvancementData(
    val advancements: String,
    val syncComplete: String,
    val lastSeen: String
)

interface AdvancementStorageHandler {
    fun getSyncStatus(uuid: UUID): String?
    fun hasAccount(uuid: UUID): Boolean
    fun createAccount(uuid: UUID, playerName: String): Boolean
    fun getData(uuid: UUID, playerName: String): DatabaseAdvancementData?
    fun setSyncStatus(uuid: UUID, playerName: String, status: String): Boolean
    fun setData(uuid: UUID, playerName: String, advancements: String, syncStatus: String): Boolean
}

data class DatabaseCraftGuiData(
    val soundEnabled: Boolean,
    val showResultItems: Boolean,
    val craftableOnly: Boolean,
    val stashEnabled: Boolean,
    val syncStatus: String,
    val lastSeen: String
)

interface CraftGuiStorageHandler {
    fun getSyncStatus(uuid: UUID): String?
    fun hasAccount(uuid: UUID): Boolean
    fun createAccount(uuid: UUID, playerName: String): Boolean
    fun getData(uuid: UUID, playerName: String): DatabaseCraftGuiData?
    fun setSyncStatus(uuid: UUID, playerName: String, status: String): Boolean
    fun setData(
        uuid: UUID, playerName: String, soundEnabled: Boolean, showResultItems: Boolean,
        craftableOnly: Boolean, stashEnabled: Boolean, syncStatus: String
    ): Boolean
}
