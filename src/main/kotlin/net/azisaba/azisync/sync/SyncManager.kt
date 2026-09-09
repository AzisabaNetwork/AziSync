package net.azisaba.azisync.sync

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.database.DatabaseManager
import net.azisaba.azisync.util.AdvancementSerializer
import net.azisaba.azisync.util.SerializedAdvancement
import net.azisaba.azisync.util.EffectSerializer
import net.azisaba.azisync.util.EconomyAudit
import net.azisaba.azisync.util.ItemSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SyncManager(private val plugin: AziSync) {
    private val maxHealthAttribute: Attribute by lazy {
        val field = runCatching { Attribute::class.java.getField("MAX_HEALTH") }
            .getOrElse { Attribute::class.java.getField("GENERIC_MAX_HEALTH") }
        field.get(null) as Attribute
    }
    
    private val loadedPlayers = ConcurrentHashMap<UUID, Boolean>()
    private val economyReadyPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val preloadedAdvancements = ConcurrentHashMap<UUID, List<SerializedAdvancement>>()
    private val advancementPreloadHandled = ConcurrentHashMap.newKeySet<UUID>()
    private val saveChains = ConcurrentHashMap<UUID, CompletableFuture<Void>>()
    private val databaseExecutor: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "AziSync-Database").apply { isDaemon = true }
    }
    private val syncStateExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AziSync-SyncState").apply { isDaemon = true }
    }

    fun isLoaded(player: Player): Boolean {
        return loadedPlayers[player.uniqueId] ?: false
    }

    fun markLoaded(player: Player) {
        loadedPlayers[player.uniqueId] = true
        if (plugin.config.getBoolean("general.enableModules.shareEconomy", true)) {
            economyReadyPlayers.add(player.uniqueId)
        }
    }

    fun saveData(player: Player, syncComplete: Boolean = false) {
        saveData(player, syncComplete, allowDisabledPlugin = false)
    }

    fun saveDataNow(player: Player, syncComplete: Boolean = false) {
        saveData(player, syncComplete, allowDisabledPlugin = true)
    }

    private fun saveData(player: Player, syncComplete: Boolean, allowDisabledPlugin: Boolean) {
        if (!Bukkit.isPrimaryThread()) {
            if (plugin.isEnabled) {
                Bukkit.getScheduler().runTask(plugin, Runnable { saveData(player, syncComplete, allowDisabledPlugin) })
            }
            return
        }
        if (!allowDisabledPlugin && !plugin.isEnabled) return
        if (!plugin.databaseManager.isAvailable()) {
            plugin.logger.warning("Skipping save for ${player.name}: database is not available.")
            return
        }

        val uuid = player.uniqueId
        val playerName = player.name
        val economyOperationId = UUID.randomUUID().toString().substring(0, 8)
        val syncStatus = "true"

        // Inventory
        val creativeInventoryDisabled = plugin.config.getBoolean("general.disableCreativeItemShare", false) && player.gameMode == GameMode.CREATIVE
        val shareInventory = plugin.config.getBoolean("general.enableModules.shareInventory", true) && !creativeInventoryDisabled
        val shareArmor = plugin.config.getBoolean("general.enableModules.shareArmor", false) && !creativeInventoryDisabled
        val shareGameMode = plugin.config.getBoolean("general.enableModules.shareGameMode", false)
        val inventoryBase64 = if (shareInventory) ItemSerializer.toBase64(player.inventory.contents) else null
        val armorBase64 = if (shareArmor) ItemSerializer.toBase64(player.inventory.armorContents) else null
        val heldItemSlot = if (shareInventory) player.inventory.heldItemSlot else 0
        @Suppress("DEPRECATION")
        val gameModeValue = if (shareGameMode) player.gameMode.value else 0
        
        // EnderChest
        val shareEnderChest = plugin.config.getBoolean("general.enableModules.shareEnderChest", true)
        val enderchestBase64 = if (shareEnderChest) ItemSerializer.toBase64(player.enderChest.contents) else null
        
        // Experience
        val shareExperience = plugin.config.getBoolean("general.enableModules.shareExperience", true)
        val exp = if (shareExperience) player.exp else 0f
        val expToLevel = if (shareExperience) player.expToLevel else 0
        val totalExperience = if (shareExperience) player.totalExperience else 0
        val level = if (shareExperience) player.level else 0
        
        // Health/Food/Air
        val shareHealth = plugin.config.getBoolean("general.enableModules.shareHealth", true)
        val shareFood = plugin.config.getBoolean("general.enableModules.shareFood", false)
        val shareAir = plugin.config.getBoolean("general.enableModules.shareAir", false)
        val health = if (shareHealth) player.health else null
        val healthScale = if (shareHealth) player.healthScale else null
        val maxHealth = if (shareHealth) player.getAttribute(maxHealthAttribute)?.baseValue else null
        val foodLevel = if (shareFood) player.foodLevel else null
        val saturation = if (shareFood) player.saturation.toString() else null
        val remainingAir = if (shareAir) player.remainingAir else null
        val maximumAir = if (shareAir) player.maximumAir else null
        
        // Potion Effects
        val sharePotionEffects = plugin.config.getBoolean("general.enableModules.sharePotionEffects", true)
        val effectsBase64 = if (sharePotionEffects) EffectSerializer.toBase64(player.activePotionEffects) else null

        // Advancements
        val shareAdvancement = plugin.config.getBoolean("general.enableModules.shareAdvancement", false)
        val advancementsBase64 = if (shareAdvancement) AdvancementSerializer.toBase64(player) else null
        
        // Location
        val shareLocation = plugin.config.getBoolean("general.enableModules.shareLocation", true)
        val shareBedSpawn = plugin.config.getBoolean("general.enableModules.shareBedSpawn", false)
        val location = if (shareLocation) player.location else null
        val locWorld = location?.world?.name
        val locX = location?.x
        val locY = location?.y
        val locZ = location?.z
        val locYaw = location?.yaw
        val locPitch = location?.pitch
        val bedSpawn = if (shareBedSpawn) {
            player.bedSpawnLocation?.let { "${it.world?.name},${it.x},${it.y},${it.z}" } ?: "none"
        } else null
        
        // Economy
        val shareEconomy = plugin.config.getBoolean("general.enableModules.shareEconomy", true)
        val econ = plugin.hookManager.economyHook.getEconomy()
        val isReady = economyReadyPlayers.contains(uuid) || isLoaded(player)
        val balance = if (shareEconomy && econ != null && isReady) {
            econ.getBalance(player).also {
                EconomyAudit.info(plugin, "ECONOMY_SAVE_CAPTURED", uuid, playerName,
                    "operationId" to economyOperationId, "balance" to it,
                    "provider" to econ.name, "syncComplete" to syncComplete)
            }
        } else {
            if (shareEconomy) {
                EconomyAudit.warning(plugin, "ECONOMY_SAVE_SKIPPED", uuid, playerName,
                    details = arrayOf(
                        "providerAvailable" to (econ != null),
                        "economyReady" to isReady,
                        "readySet" to economyReadyPlayers.contains(uuid),
                        "isLoaded" to isLoaded(player),
                        "syncComplete" to syncComplete,
                        "operationId" to economyOperationId
                    ))
            }
            null
        }

        // CraftGUI
        val shareCraftGui = plugin.config.getBoolean("general.enableModules.shareCraftGui", false)
        val craftGuiPref = if (shareCraftGui) getCraftGuiPreference(uuid) else null

        val syncVersionFuture: CompletableFuture<Long?> = if (syncComplete) {
            CompletableFuture.supplyAsync({
                plugin.databaseManager.beginSync(uuid, playerName).also {
                    if (plugin.databaseManager.storageMode == DatabaseManager.StorageMode.HYBRID) {
                        plugin.databaseManager.redisManager?.setSyncStatus(uuid, "saving")
                    }
                }
            }, syncStateExecutor)
        } else {
            CompletableFuture.completedFuture(null)
        }

        val saveTask = Runnable {
            var syncVersion: Long? = null
            var syncLock: DatabaseManager.SyncLock? = null
            try {
                if (!plugin.databaseManager.isAvailable()) {
                    return@Runnable
                }

                syncVersion = syncVersionFuture.join()

                val jobsPollMillis = plugin.config.getLong("general.jobsWait.pollMillis", 25L).coerceIn(10L, 250L)
                val jobsTimeoutMillis = plugin.config.getLong("general.jobsWait.timeoutMillis", 5000L).coerceAtLeast(0L)
                val jobsWaitStarted = System.nanoTime()
                while (syncComplete && plugin.hookManager.jobsHook.isPlayerSaving(uuid) &&
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - jobsWaitStarted) < jobsTimeoutMillis) {
                    Thread.sleep(jobsPollMillis)
                }
                val jobsWaitMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - jobsWaitStarted)
                if (syncComplete && plugin.hookManager.jobsHook.isPlayerSaving(uuid)) {
                    plugin.logger.warning("Jobs saving task for $playerName timed out after ${jobsWaitMillis}ms. Saving AziSync data anyway.")
                } else if (jobsWaitMillis >= jobsPollMillis) {
                    plugin.logger.info("Waited ${jobsWaitMillis}ms for Jobs to finish saving $playerName's data.")
                }

                if (syncVersion != null) {
                    val lockTimeoutSeconds = plugin.config.getInt("general.syncWait.lockTimeoutSeconds", 10).coerceAtLeast(1)
                    syncLock = plugin.databaseManager.acquireSyncLock(uuid, lockTimeoutSeconds)
                    if (!plugin.databaseManager.isCurrentSyncVersion(uuid, syncVersion)) {
                        plugin.logger.warning("Skipped stale save for $playerName (sync version $syncVersion)")
                        return@Runnable
                    }
                }

                // Save Inventory
                if (shareInventory || shareArmor || shareGameMode) {
                    val current = plugin.databaseManager.inventoryHandler.getData(uuid, playerName)
                    requireSaved(plugin.databaseManager.inventoryHandler.setData(
                        uuid, playerName,
                        inventoryBase64 ?: current?.inventory ?: "none",
                        armorBase64 ?: current?.armor ?: "none",
                        if (shareInventory) heldItemSlot else current?.hotbarSlot ?: 0,
                        if (shareGameMode) gameModeValue else current?.gamemode ?: 0,
                        syncStatus
                    ), "inventory")
                }

                // Save EnderChest
                if (shareEnderChest && enderchestBase64 != null) {
                    requireSaved(plugin.databaseManager.enderchestHandler.setData(uuid, playerName, enderchestBase64, syncStatus), "ender chest")
                }

                // Save Experience
                if (shareExperience) {
                    requireSaved(plugin.databaseManager.experienceHandler.setData(
                        uuid, playerName, exp, expToLevel, totalExperience, level, syncStatus
                    ), "experience")
                }

                // Save Health/Food/Air
                if (shareHealth || shareFood || shareAir) {
                    val current = plugin.databaseManager.healthHandler.getData(uuid, playerName)
                    requireSaved(plugin.databaseManager.healthHandler.setData(
                        uuid, playerName,
                        health ?: current?.health ?: 20.0,
                        healthScale ?: current?.healthScale ?: 20.0,
                        maxHealth ?: current?.maxHealth ?: 20.0,
                        foodLevel ?: current?.food ?: 20,
                        saturation ?: current?.saturation ?: "5.0",
                        remainingAir ?: current?.air ?: 300,
                        maximumAir ?: current?.maxAir ?: 300,
                        syncStatus
                    ), "health")
                }

                // Save Potion Effects
                if (sharePotionEffects && effectsBase64 != null) {
                    requireSaved(plugin.databaseManager.potionEffectsHandler.setData(uuid, playerName, effectsBase64, syncStatus), "potion effects")
                }

                // Save Advancements
                if (shareAdvancement && advancementsBase64 != null) {
                    requireSaved(plugin.databaseManager.advancementHandler.setData(uuid, playerName, advancementsBase64, syncStatus), "advancements")
                }

                // Save Location
                if (shareLocation || shareBedSpawn) {
                    val current = plugin.databaseManager.locationHandler.getData(uuid, playerName)
                    requireSaved(plugin.databaseManager.locationHandler.setData(
                        uuid, playerName,
                        locWorld ?: current?.world ?: "world",
                        locX ?: current?.x ?: 0.0,
                        locY ?: current?.y ?: 0.0,
                        locZ ?: current?.z ?: 0.0,
                        locYaw ?: current?.yaw ?: 0f,
                        locPitch ?: current?.pitch ?: 0f,
                        bedSpawn ?: current?.bedSpawn ?: "none",
                        syncStatus
                    ), "location")
                }

                // Save Economy
                if (shareEconomy && balance != null) {
                    EconomyAudit.info(plugin, "ECONOMY_SAVE_STARTED", uuid, playerName,
                        "operationId" to economyOperationId, "balance" to balance, "syncVersion" to syncVersion)
                    requireSaved(plugin.databaseManager.economyHandler.setData(uuid, playerName, balance, syncStatus), "economy")
                }

                // Save CraftGUI
                if (shareCraftGui && craftGuiPref != null) {
                    requireSaved(plugin.databaseManager.craftGuiHandler.setData(
                        uuid, playerName,
                        getBooleanPreference(craftGuiPref, "isSoundEnabled", true),
                        getBooleanPreference(craftGuiPref, "isShowResultItems", true),
                        getBooleanPreference(craftGuiPref, "isCraftableOnly", false),
                        getBooleanPreference(craftGuiPref, "isStashEnabled", false),
                        syncStatus
                    ), "CraftGUI")
                }

                if (syncVersion != null) {
                    if (!plugin.databaseManager.completeSync(uuid, syncVersion)) {
                        plugin.logger.warning("Skipped stale sync completion for $playerName")
                    } else if (plugin.databaseManager.storageMode == DatabaseManager.StorageMode.HYBRID) {
                        plugin.databaseManager.redisManager?.setSyncStatus(uuid, "complete")
                    }
                }
                
                plugin.logger.info("Successfully saved data for $playerName")
            } catch (e: Exception) {
                syncVersion?.let { version ->
                    runCatching { plugin.databaseManager.failSync(uuid, version) }
                    if (plugin.databaseManager.storageMode == DatabaseManager.StorageMode.HYBRID) {
                        plugin.databaseManager.redisManager?.setSyncStatus(uuid, "failed")
                    }
                }
                plugin.logger.severe("Failed to save data for $playerName: ${e.message}")
                if (shareEconomy) {
                    EconomyAudit.severe(plugin, "ECONOMY_SAVE_PIPELINE_ABORTED", uuid, playerName, e,
                        "operationId" to economyOperationId,
                        "capturedBalance" to balance,
                        "syncVersion" to syncVersion)
                }
                e.printStackTrace()
            } finally {
                runCatching { syncLock?.close() }.onFailure {
                    plugin.logger.warning("Failed to release sync lock for $playerName: ${it.message}")
                }
            }
        }

        enqueueSave(uuid, saveTask)
    }

    private fun enqueueSave(uuid: UUID, task: Runnable) {
        val next = saveChains.compute(uuid) { _, previous ->
            (previous ?: CompletableFuture.completedFuture(null))
                .handle { _, _ -> null }
                .thenRunAsync(task, databaseExecutor)
        }!!
        next.whenComplete { _, _ -> saveChains.remove(uuid, next) }
    }

    private fun requireSaved(saved: Boolean, module: String) {
        if (!saved) throw IllegalStateException("Failed to persist $module data")
    }

    fun flushPendingSaves(timeoutSeconds: Long): Boolean {
        val pending = saveChains.values.toTypedArray()
        if (pending.isEmpty()) return true
        return try {
            CompletableFuture.allOf(*pending).get(timeoutSeconds, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            plugin.logger.warning("Timed out while waiting for pending AziSync saves: ${e.message}")
            false
        }
    }

    fun shutdown() {
        syncStateExecutor.shutdown()
        databaseExecutor.shutdown()
        if (!syncStateExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
            syncStateExecutor.shutdownNow()
        }
        if (!databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
            databaseExecutor.shutdownNow()
        }
    }

    fun isShutdown(): Boolean = databaseExecutor.isShutdown

    fun preloadAdvancements(uuid: UUID, playerName: String) {
        advancementPreloadHandled.add(uuid)
        preloadedAdvancements.remove(uuid)
        if (!plugin.databaseManager.isAvailable()) {
            logAdvancement("PRELOAD_SKIPPED_DATABASE_UNAVAILABLE", uuid, playerName, warning = true)
            return
        }

        try {
            val graceMillis = plugin.config.getLong("general.syncWait.handoffGraceMillis", 100L).coerceAtLeast(0L)
            val pollMillis = plugin.config.getLong("general.syncWait.pollMillis", 50L).coerceIn(10L, 250L)
            val timeoutMillis = plugin.config.getLong("general.syncWait.timeoutMillis", 10000L).coerceAtLeast(0L)
            if (graceMillis > 0) Thread.sleep(graceMillis)
            val waitStarted = System.nanoTime()

            while (true) {
                when (val status = getSyncStatus(uuid)) {
                    null, "complete" -> break
                    "failed" -> {
                        logAdvancement("PRELOAD_SKIPPED_FAILED_SYNC", uuid, playerName, warning = true)
                        return
                    }
                    "saving" -> Unit
                    else -> {
                        logAdvancement("PRELOAD_SKIPPED_UNKNOWN_SYNC_STATE", uuid, playerName, warning = true,
                            "status" to status)
                        return
                    }
                }
                val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStarted)
                if (waitedMillis >= timeoutMillis) {
                    logAdvancement("PRELOAD_SKIPPED_TIMEOUT", uuid, playerName, warning = true,
                        "waitedMillis" to waitedMillis)
                    return
                }
                Thread.sleep(pollMillis)
            }

            val data = plugin.databaseManager.advancementHandler.getData(uuid, playerName)
            if (data == null || data.advancements == "none") {
                logAdvancement("PRELOAD_NO_REMOTE_DATA", uuid, playerName)
                return
            }
            val decoded = AdvancementSerializer.fromBase64(data.advancements)
            preloadedAdvancements[uuid] = decoded
            logAdvancement("PRELOAD_READY", uuid, playerName,
                "advancementCount" to decoded.size,
                "criteriaCount" to decoded.sumOf { it.awardedCriteria.size })
        } catch (e: Exception) {
            logAdvancement("PRELOAD_FAILED", uuid, playerName, warning = true,
                "errorType" to e.javaClass.simpleName,
                "error" to e.message)
            plugin.logger.log(java.util.logging.Level.WARNING, "Failed to preload advancements for $playerName", e)
        }
    }

    fun applyPreloadedAdvancements(player: Player) {
        val uuid = player.uniqueId
        val advancements = preloadedAdvancements.remove(uuid) ?: return
        try {
            val result = AdvancementSerializer.merge(player, advancements)
            logAdvancement("PRELOGIN_MERGE_APPLIED", uuid, player.name,
                "savedAdvancements" to result.savedAdvancements,
                "localAdvancements" to result.localAdvancements,
                "awardedCriteria" to result.awardedCriteria,
                "missingAdvancements" to result.missingAdvancements,
                "invalidCriteria" to result.invalidCriteria,
                "restoreFailures" to result.restoreFailures)
        } catch (e: Exception) {
            logAdvancement("PRELOGIN_MERGE_FAILED", uuid, player.name, warning = true,
                "errorType" to e.javaClass.simpleName,
                "error" to e.message)
            plugin.logger.log(java.util.logging.Level.WARNING, "Failed to merge preloaded advancements for ${player.name}", e)
        }
    }

    private fun logAdvancement(
        action: String,
        uuid: UUID,
        playerName: String,
        vararg details: Pair<String, Any?>
    ) = logAdvancement(action, uuid, playerName, false, *details)

    private fun logAdvancement(
        action: String,
        uuid: UUID,
        playerName: String,
        warning: Boolean,
        vararg details: Pair<String, Any?>
    ) {
        val serverId = plugin.config.getString("general.serverId", "")?.takeIf { it.isNotBlank() }
            ?: "port-${plugin.server.port}"
        val suffix = details.joinToString(separator = " ") { (key, value) -> "$key=${value ?: "none"}" }
        val message = "[AdvancementAudit] action=$action server=$serverId player=$playerName uuid=$uuid" +
            if (suffix.isEmpty()) "" else " $suffix"
        if (warning) plugin.logger.warning(message) else plugin.logger.info(message)
    }

    fun loadData(player: Player) {
        if (!plugin.databaseManager.isAvailable()) {
            plugin.logger.warning("Skipping load for ${player.name}: database is not available.")
            return
        }

        val uuid = player.uniqueId
        val economyOperationId = UUID.randomUUID().toString().substring(0, 8)
        economyReadyPlayers.remove(uuid)
        val playerName = player.name
        val creativeInventoryDisabled = plugin.config.getBoolean("general.disableCreativeItemShare", false) && player.gameMode == GameMode.CREATIVE
        val loadInventory = plugin.config.getBoolean("general.enableModules.shareInventory", true) && !creativeInventoryDisabled
        val loadArmor = plugin.config.getBoolean("general.enableModules.shareArmor", false) && !creativeInventoryDisabled
        val loadGameMode = plugin.config.getBoolean("general.enableModules.shareGameMode", false)
        val loadHealth = plugin.config.getBoolean("general.enableModules.shareHealth", true)
        val loadFood = plugin.config.getBoolean("general.enableModules.shareFood", false)
        val loadAir = plugin.config.getBoolean("general.enableModules.shareAir", false)
        val loadLocation = plugin.config.getBoolean("general.enableModules.shareLocation", true)
        val loadBedSpawn = plugin.config.getBoolean("general.enableModules.shareBedSpawn", false)
        if (!plugin.config.getBoolean("general.disableSounds", false)) {
            player.playSound(player.location, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)
        }
        databaseExecutor.execute {
            try {
                if (!plugin.databaseManager.isAvailable()) {
                    return@execute
                }

                // Inventory
                if (loadInventory || loadArmor || loadGameMode) {
                    try {
                        val invData = plugin.databaseManager.inventoryHandler.getData(uuid, playerName)
                        if (invData != null) {
                            val contents = if (loadInventory && invData.inventory != "none") ItemSerializer.fromBase64(invData.inventory, 36) else null
                            val armor = if (loadArmor && invData.armor != "none") ItemSerializer.fromBase64(invData.armor, 4) else null
                            
                            Bukkit.getScheduler().runTask(plugin, Runnable {
                                if (!player.isOnline) return@Runnable
                                if (contents != null) {
                                    if (contents.size >= 41) {
                                        val storage = contents.copyOfRange(0, 36)
                                        val armorFromContents = contents.copyOfRange(36, 40)
                                        val offhandFromContents = contents[40]
                                        player.inventory.storageContents = storage
                                        if (loadArmor) player.inventory.setArmorContents(armorFromContents)
                                        player.inventory.setItemInOffHand(offhandFromContents ?: ItemStack(Material.AIR))
                                    } else {
                                        player.inventory.storageContents = contents
                                        player.inventory.setItemInOffHand(ItemStack(Material.AIR))
                                    }
                                    player.inventory.heldItemSlot = invData.hotbarSlot
                                }
                                if (loadArmor && armor != null && armor.any { it != null && it.type != Material.AIR }) {
                                    player.inventory.setArmorContents(armor)
                                }
                                if (loadGameMode) {
                                    val gm = GameMode.getByValue(invData.gamemode)
                                    if (gm != null) player.gameMode = gm
                                }
                                player.updateInventory()
                            })
                        }
                    } catch (e: Exception) {
                        plugin.logger.severe("Failed to load inventory/armor for $playerName: ${e.message}")
                    }
                }

                // EnderChest
                if (plugin.config.getBoolean("general.enableModules.shareEnderChest", true)) {
                    try {
                        val ecData = plugin.databaseManager.enderchestHandler.getData(uuid, playerName)
                        if (ecData != null && ecData.enderchest != "none") {
                            val contents = ItemSerializer.fromBase64(ecData.enderchest, 27)
                            Bukkit.getScheduler().runTask(plugin, Runnable {
                                player.enderChest.contents = contents
                            })
                        }
                    } catch (e: Exception) {
                        plugin.logger.severe("Failed to load enderchest for $playerName: ${e.message}")
                    }
                }

                // Experience
                if (plugin.config.getBoolean("general.enableModules.shareExperience", true)) {
                    try {
                        val expData = plugin.databaseManager.experienceHandler.getData(uuid, playerName)
                        if (expData != null) {
                            Bukkit.getScheduler().runTask(plugin, Runnable {
                                player.exp = expData.exp
                                player.level = expData.expLvl
                                player.totalExperience = expData.totalExp
                            })
                        }
                    } catch (e: Exception) {
                        plugin.logger.severe("Failed to load experience for $playerName: ${e.message}")
                    }
                }

                // Health/Food/Air
                if (loadHealth || loadFood || loadAir) {
                    try {
                        val healthData = plugin.databaseManager.healthHandler.getData(uuid, playerName)
                        if (healthData != null) {
                            Bukkit.getScheduler().runTask(plugin, Runnable {
                                if (!player.isOnline) return@Runnable
                                try {
                                    if (loadHealth) {
                                        val maxHealth = player.getAttribute(maxHealthAttribute)
                                        if (healthData.maxHealth > 0.0) {
                                            maxHealth?.baseValue = healthData.maxHealth
                                        }
                                        if (healthData.healthScale > 0.0) {
                                            try {
                                                player.isHealthScaled = true
                                                player.healthScale = healthData.healthScale
                                            } catch (_: Exception) {}
                                        } else {
                                            player.isHealthScaled = false
                                        }
                                        val effectiveMaxHealth = maxHealth?.value ?: (if (healthData.maxHealth > 0.0) healthData.maxHealth else 20.0)
                                        val targetHealth = healthData.health.coerceIn(0.1, effectiveMaxHealth)
                                        try {
                                            player.health = targetHealth
                                        } catch (_: Exception) {}
                                    }
                                    if (loadFood) {
                                        player.foodLevel = healthData.food.coerceIn(0, 20)
                                        player.saturation = (healthData.saturation.toFloatOrNull() ?: 5.0f).coerceIn(0.0f, 20.0f)
                                    }
                                    if (loadAir) {
                                        player.maximumAir = if (healthData.maxAir > 0) healthData.maxAir else 300
                                        player.remainingAir = healthData.air.coerceIn(0, player.maximumAir)
                                    }
                                } catch (e: Exception) {
                                    plugin.logger.warning("Failed to apply health/food/air for ${player.name}: ${e.message}")
                                }
                            })
                        }
                    } catch (e: Exception) {
                        plugin.logger.severe("Failed to load health/food/air for $playerName: ${e.message}")
                    }
                }

                // Potion Effects
                if (plugin.config.getBoolean("general.enableModules.sharePotionEffects", true)) {
                    try {
                        val potionData = plugin.databaseManager.potionEffectsHandler.getData(uuid, playerName)
                        if (potionData != null && potionData.potionEffects != "none") {
                            val effects = EffectSerializer.fromBase64(potionData.potionEffects)
                            Bukkit.getScheduler().runTask(plugin, Runnable {
                                player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
                                player.addPotionEffects(effects)
                            })
                        }
                    } catch (e: Exception) {
                        plugin.logger.severe("Failed to load potion effects for $playerName: ${e.message}")
                    }
                }

                // Advancements
                if (plugin.config.getBoolean("general.enableModules.shareAdvancement", false)) {
                    if (advancementPreloadHandled.contains(uuid)) {
                        logAdvancement("POST_JOIN_APPLY_SKIPPED_PRELOADED", uuid, playerName)
                    } else {
                        // Never restore through Bukkit after the initial advancement packet:
                        // doing so would replay rewards, events, sounds, and client toasts.
                        logAdvancement("POST_JOIN_RESTORE_SKIPPED_NO_PRELOAD", uuid, playerName, warning = true)
                    }
                }

                // Location
                if (loadLocation || loadBedSpawn) {
                    try {
                        val locData = plugin.databaseManager.locationHandler.getData(uuid, playerName)
                        if (locData != null) {
                            Bukkit.getScheduler().runTask(plugin, Runnable {
                                if (loadLocation) {
                                    val world = Bukkit.getWorld(locData.world)
                                    if (world != null) {
                                        player.teleport(Location(world, locData.x, locData.y, locData.z, locData.yaw, locData.pitch))
                                    }
                                }
                                if (loadBedSpawn && locData.bedSpawn != "none") {
                                    val split = locData.bedSpawn.split(",")
                                    if (split.size == 4) {
                                        val bedWorld = Bukkit.getWorld(split[0])
                                        if (bedWorld != null) {
                                            player.setBedSpawnLocation(Location(bedWorld, split[1].toDouble(), split[2].toDouble(), split[3].toDouble()), true)
                                        }
                                    }
                                }
                            })
                        }
                    } catch (e: Exception) {
                        plugin.logger.severe("Failed to load location for $playerName: ${e.message}")
                    }
                }

                // Economy
                if (plugin.config.getBoolean("general.enableModules.shareEconomy", true)) {
                    try {
                        val econ = plugin.hookManager.economyHook.getEconomy()
                        if (econ != null) {
                            EconomyAudit.info(plugin, "ECONOMY_LOAD_STARTED", uuid, playerName,
                                "operationId" to economyOperationId, "provider" to econ.name)
                            if (!plugin.databaseManager.economyHandler.hasAccount(uuid)) {
                                val currentVaultBalance = if (player.isOnline) econ.getBalance(player).coerceAtLeast(0.0) else 0.0
                                plugin.databaseManager.economyHandler.createAccount(uuid, playerName, currentVaultBalance)
                                if (currentVaultBalance > 0.0) {
                                    EconomyAudit.info(plugin, "ECONOMY_ACCOUNT_INITIALIZED_FROM_VAULT", uuid, playerName,
                                        "initialBalance" to currentVaultBalance, "provider" to econ.name)
                                }
                            }
                            val econData = plugin.databaseManager.economyHandler.getData(uuid, playerName)
                            if (econData != null) {
                                val mergeResult = plugin.databaseManager.economyHandler.mergeOfflineMoneyIntoBalance(uuid)
                                if (mergeResult == null) {
                                    EconomyAudit.severe(plugin, "ECONOMY_LOAD_PREPARE_FAILED", uuid, playerName,
                                        details = arrayOf("provider" to econ.name, "storedBalance" to econData.money,
                                            "observedOfflineDelta" to econData.offlineMoney,
                                            "operationId" to economyOperationId))
                                } else {
                                    Bukkit.getScheduler().runTask(plugin, Runnable {
                                        if (!player.isOnline) {
                                            EconomyAudit.warning(plugin, "ECONOMY_VAULT_APPLY_SKIPPED_OFFLINE", uuid, playerName,
                                                details = arrayOf("targetBalance" to mergeResult.mergedBalance,
                                                    "provider" to econ.name, "operationId" to economyOperationId))
                                            return@Runnable
                                        }
                                        val currentBalance = econ.getBalance(player)
                                        val difference = mergeResult.mergedBalance - currentBalance
                                        EconomyAudit.info(plugin, "ECONOMY_VAULT_APPLY_STARTED", uuid, playerName,
                                            "provider" to econ.name,
                                            "operationId" to economyOperationId,
                                            "currentBalance" to currentBalance,
                                            "storedBalance" to mergeResult.storedBalance,
                                            "offlineDelta" to mergeResult.offlineDelta,
                                            "targetBalance" to mergeResult.mergedBalance,
                                            "difference" to difference)
                                        val isNegligible = kotlin.math.abs(difference) < 0.01
                                        val succeeded = when {
                                            isNegligible -> true
                                            difference > 0 -> econ.depositPlayer(player, difference).transactionSuccess()
                                            difference < 0 -> econ.withdrawPlayer(player, -difference).transactionSuccess()
                                            else -> true
                                        }
                                        val resultingBalance = econ.getBalance(player)
                                        val balanceMatches = kotlin.math.abs(resultingBalance - mergeResult.mergedBalance) < 0.01
                                        if (succeeded) {
                                            economyReadyPlayers.add(uuid)
                                            if (balanceMatches) {
                                                EconomyAudit.info(plugin, "ECONOMY_VAULT_APPLY_SUCCEEDED", uuid, playerName,
                                                    "provider" to econ.name,
                                                    "operationId" to economyOperationId,
                                                    "previousBalance" to currentBalance,
                                                    "offlineDelta" to mergeResult.offlineDelta,
                                                    "targetBalance" to mergeResult.mergedBalance,
                                                    "resultingBalance" to resultingBalance)
                                            } else {
                                                EconomyAudit.warning(plugin, "ECONOMY_VAULT_APPLY_ROUNDING_MISMATCH", uuid, playerName,
                                                    details = arrayOf(
                                                        "provider" to econ.name,
                                                        "operationId" to economyOperationId,
                                                        "previousBalance" to currentBalance,
                                                        "difference" to difference,
                                                        "targetBalance" to mergeResult.mergedBalance,
                                                        "resultingBalance" to resultingBalance
                                                    ))
                                            }
                                        } else {
                                            EconomyAudit.severe(plugin, "ECONOMY_VAULT_APPLY_FAILED", uuid, playerName,
                                                details = arrayOf(
                                                    "provider" to econ.name,
                                                    "operationId" to economyOperationId,
                                                    "transactionSucceeded" to succeeded,
                                                    "balanceMatches" to balanceMatches,
                                                    "previousBalance" to currentBalance,
                                                    "difference" to difference,
                                                    "targetBalance" to mergeResult.mergedBalance,
                                                    "resultingBalance" to resultingBalance,
                                                    "safetyAction" to "economy_saving_disabled_for_session"
                                                ))
                                        }
                                    })
                                }
                            } else {
                                EconomyAudit.severe(plugin, "ECONOMY_LOAD_ACCOUNT_READ_FAILED", uuid, playerName,
                                    details = arrayOf("provider" to econ.name, "operationId" to economyOperationId))
                            }
                        } else {
                            EconomyAudit.severe(plugin, "ECONOMY_LOAD_PROVIDER_MISSING", uuid, playerName,
                                details = arrayOf("operationId" to economyOperationId))
                        }
                    } catch (e: Exception) {
                        EconomyAudit.severe(plugin, "ECONOMY_LOAD_EXCEPTION", uuid, playerName, e,
                            "operationId" to economyOperationId)
                    }
                }

                // CraftGUI
                if (plugin.config.getBoolean("general.enableModules.shareCraftGui", false)) {
                    try {
                        val craftGuiData = plugin.databaseManager.craftGuiHandler.getData(uuid, playerName)
                        if (craftGuiData != null) {
                            Bukkit.getScheduler().runTask(plugin, Runnable {
                                val pref = getCraftGuiPreference(uuid) ?: return@Runnable
                                setBooleanPreference(pref, "setSoundEnabled", craftGuiData.soundEnabled)
                                setBooleanPreference(pref, "setShowResultItems", craftGuiData.showResultItems)
                                setBooleanPreference(pref, "setCraftableOnly", craftGuiData.craftableOnly)
                                setBooleanPreference(pref, "setStashEnabled", craftGuiData.stashEnabled)
                            })
                        }
                    } catch (e: Exception) {
                        plugin.logger.severe("Failed to load CraftGUI for $playerName: ${e.message}")
                    }
                }
                
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (!player.isOnline) return@Runnable
                    if (!plugin.config.getBoolean("general.disableSounds", false)) {
                        player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
                    }
                    loadedPlayers[player.uniqueId] = true
                    plugin.messageManager.sendMessage(player, "sync_complete")
                })
                plugin.logger.info("Successfully loaded data for ${player.name}")
            } catch (e: Exception) {
                plugin.logger.severe("Failed to load data for ${player.name}: ${e.message}")
                if (plugin.config.getBoolean("general.enableModules.shareEconomy", true)) {
                    EconomyAudit.severe(plugin, "ECONOMY_LOAD_PIPELINE_ABORTED", uuid, playerName, e,
                        "operationId" to economyOperationId)
                }
                e.printStackTrace()
            }
        }
    }
    
    fun removeLoadedStatus(uuid: UUID) {
        loadedPlayers.remove(uuid)
        economyReadyPlayers.remove(uuid)
        preloadedAdvancements.remove(uuid)
        advancementPreloadHandled.remove(uuid)
    }

    fun isEconomyReady(uuid: UUID): Boolean = economyReadyPlayers.contains(uuid)

    fun markEconomyReady(uuid: UUID) {
        economyReadyPlayers.add(uuid)
    }

    fun setSyncStatus(uuid: UUID, playerName: String, isComplete: Boolean) {
        // Per-module tables retain their legacy VARCHAR(5) true/false status.
        // Cross-server coordination uses saving/complete in sync_state.
        val statusStr = if (isComplete) "true" else "false"
        if (plugin.databaseManager.storageMode == DatabaseManager.StorageMode.HYBRID) {
            plugin.databaseManager.redisManager?.setSyncStatus(uuid, statusStr)
        }
        if (plugin.config.getBoolean("general.enableModules.shareInventory", true)) {
            plugin.databaseManager.inventoryHandler.setSyncStatus(uuid, playerName, statusStr)
        }
        if (plugin.config.getBoolean("general.enableModules.shareEnderChest", true)) {
            plugin.databaseManager.enderchestHandler.setSyncStatus(uuid, playerName, statusStr)
        }
        if (plugin.config.getBoolean("general.enableModules.shareExperience", true)) {
            plugin.databaseManager.experienceHandler.setSyncStatus(uuid, playerName, statusStr)
        }
        if (plugin.config.getBoolean("general.enableModules.shareHealth", true)) {
            plugin.databaseManager.healthHandler.setSyncStatus(uuid, playerName, statusStr)
        }
        if (plugin.config.getBoolean("general.enableModules.sharePotionEffects", true)) {
            plugin.databaseManager.potionEffectsHandler.setSyncStatus(uuid, playerName, statusStr)
        }
        if (plugin.config.getBoolean("general.enableModules.shareAdvancement", false)) {
            plugin.databaseManager.advancementHandler.setSyncStatus(uuid, playerName, statusStr)
        }
        if (plugin.config.getBoolean("general.enableModules.shareLocation", true)) {
            plugin.databaseManager.locationHandler.setSyncStatus(uuid, playerName, statusStr)
        }
        if (plugin.config.getBoolean("general.enableModules.shareEconomy", true)) {
            plugin.databaseManager.economyHandler.setSyncStatus(uuid, playerName, statusStr)
        }
        if (plugin.config.getBoolean("general.enableModules.shareCraftGui", false)) {
            plugin.databaseManager.craftGuiHandler.setSyncStatus(uuid, playerName, statusStr)
        }
    }

    fun getSyncStatus(uuid: UUID): String? {
        if (plugin.databaseManager.storageMode == DatabaseManager.StorageMode.HYBRID) {
            val redisStatus = plugin.databaseManager.redisManager?.getSyncStatus(uuid)?.let(::normalizeSyncStatus)
            if (redisStatus == "saving" || redisStatus == "failed") return redisStatus
            // A cached "complete" can belong to the previous handoff. Confirm it
            // against MySQL so a failed Redis write cannot expose stale data.
        }
        return plugin.databaseManager.getSyncState(uuid)?.status?.let(::normalizeSyncStatus)
    }

    private fun normalizeSyncStatus(status: String): String = when (status.lowercase()) {
        "true" -> "complete"
        "false" -> "saving"
        else -> status.lowercase()
    }

    private fun prepareSyncStatusRows(uuid: UUID, playerName: String) {
        if (plugin.config.getBoolean("general.enableModules.shareInventory", true) &&
            !plugin.databaseManager.inventoryHandler.hasAccount(uuid)) {
            plugin.databaseManager.inventoryHandler.createAccount(uuid, playerName)
        }
        if (plugin.config.getBoolean("general.enableModules.shareEnderChest", true) &&
            !plugin.databaseManager.enderchestHandler.hasAccount(uuid)) {
            plugin.databaseManager.enderchestHandler.createAccount(uuid, playerName)
        }
        if (plugin.config.getBoolean("general.enableModules.shareExperience", true) &&
            !plugin.databaseManager.experienceHandler.hasAccount(uuid)) {
            plugin.databaseManager.experienceHandler.createAccount(uuid, playerName)
        }
        if (plugin.config.getBoolean("general.enableModules.shareHealth", true) &&
            !plugin.databaseManager.healthHandler.hasAccount(uuid)) {
            plugin.databaseManager.healthHandler.createAccount(uuid, playerName)
        }
        if (plugin.config.getBoolean("general.enableModules.sharePotionEffects", true) &&
            !plugin.databaseManager.potionEffectsHandler.hasAccount(uuid)) {
            plugin.databaseManager.potionEffectsHandler.createAccount(uuid, playerName)
        }
        if (plugin.config.getBoolean("general.enableModules.shareAdvancement", false) &&
            !plugin.databaseManager.advancementHandler.hasAccount(uuid)) {
            plugin.databaseManager.advancementHandler.createAccount(uuid, playerName)
        }
        if (plugin.config.getBoolean("general.enableModules.shareLocation", true) &&
            !plugin.databaseManager.locationHandler.hasAccount(uuid)) {
            plugin.databaseManager.locationHandler.createAccount(uuid, playerName)
        }
        if (plugin.config.getBoolean("general.enableModules.shareEconomy", true) &&
            !plugin.databaseManager.economyHandler.hasAccount(uuid)) {
            plugin.databaseManager.economyHandler.createAccount(uuid, playerName)
        }
        if (plugin.config.getBoolean("general.enableModules.shareCraftGui", false) &&
            !plugin.databaseManager.craftGuiHandler.hasAccount(uuid)) {
            plugin.databaseManager.craftGuiHandler.createAccount(uuid, playerName)
        }
    }

    private fun getCraftGuiPreference(uuid: UUID): Any? {
        return try {
            val pluginManager = Bukkit.getPluginManager()
            if (!pluginManager.isPluginEnabled("CraftGUI")) {
                return null
            }
            val craftGuiPlugin = pluginManager.getPlugin("CraftGUI") ?: return null
            val api = craftGuiPlugin.javaClass.methods
                .firstOrNull { it.name == "getApi" && it.parameterCount == 0 }
                ?.invoke(craftGuiPlugin)
                ?: return null

            api.javaClass.methods
                .firstOrNull { it.name == "getUserPreference" && it.parameterCount == 1 }
                ?.invoke(api, uuid)
        } catch (e: ReflectiveOperationException) {
            plugin.logger.warning("Failed to access CraftGUI preference: ${e.message}")
            null
        } catch (e: LinkageError) {
            plugin.logger.warning("Failed to access CraftGUI preference: ${e.message}")
            null
        }
    }

    private fun getBooleanPreference(preference: Any, methodName: String, defaultValue: Boolean): Boolean {
        return try {
            preference.javaClass.methods
                .firstOrNull { it.name == methodName && it.parameterCount == 0 }
                ?.invoke(preference) as? Boolean ?: defaultValue
        } catch (e: ReflectiveOperationException) {
            defaultValue
        } catch (e: LinkageError) {
            defaultValue
        }
    }

    private fun setBooleanPreference(preference: Any, methodName: String, value: Boolean) {
        try {
            preference.javaClass.methods
                .firstOrNull { it.name == methodName && it.parameterCount == 1 }
                ?.invoke(preference, value)
        } catch (e: ReflectiveOperationException) {
            plugin.logger.warning("Failed to update CraftGUI preference $methodName: ${e.message}")
        } catch (e: LinkageError) {
            plugin.logger.warning("Failed to update CraftGUI preference $methodName: ${e.message}")
        }
    }
}
