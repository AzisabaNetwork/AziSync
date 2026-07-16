package net.azisaba.azisync.sync

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.database.DatabaseManager
import net.azisaba.azisync.util.AdvancementSerializer
import net.azisaba.azisync.util.EffectSerializer
import net.azisaba.azisync.util.ItemSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SyncManager(private val plugin: AziSync) {
    
    private val loadedPlayers = ConcurrentHashMap<UUID, Boolean>()
    private val saveChains = ConcurrentHashMap<UUID, CompletableFuture<Void>>()
    private val databaseExecutor: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "AziSync-Database").apply { isDaemon = true }
    }

    fun isLoaded(player: Player): Boolean {
        return loadedPlayers[player.uniqueId] ?: false
    }

    fun markLoaded(player: Player) {
        loadedPlayers[player.uniqueId] = true
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
        @Suppress("DEPRECATION")
        val maxHealth = if (shareHealth) player.maxHealth else null
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
        val balance = if (shareEconomy && econ != null) econ.getBalance(player) else null

        // CraftGUI
        val shareCraftGui = plugin.config.getBoolean("general.enableModules.shareCraftGui", false)
        val craftGuiPref = if (shareCraftGui) getCraftGuiPreference(uuid) else null

        val saveTask = Runnable {
            try {
                if (!plugin.databaseManager.isAvailable()) {
                    return@Runnable
                }

                val syncVersion = if (syncComplete) {
                    plugin.databaseManager.beginSync(uuid, playerName)
                } else {
                    null
                }

                var waitCount = 0
                while (syncComplete && plugin.hookManager.jobsHook.isPlayerSaving(uuid) && waitCount < 20) {
                    Thread.sleep(250)
                    waitCount++
                }
                if (waitCount >= 20) {
                    plugin.logger.warning("Jobs saving task for $playerName timed out after 5 seconds. Saving AziSync data anyway.")
                } else if (waitCount > 0) {
                    plugin.logger.info("Waited ${waitCount * 250}ms for Jobs to finish saving $playerName's data.")
                }

                // Save Inventory
                if (shareInventory || shareArmor || shareGameMode) {
                    val current = plugin.databaseManager.inventoryHandler.getData(uuid, playerName)
                    plugin.databaseManager.inventoryHandler.setData(
                        uuid, playerName,
                        inventoryBase64 ?: current?.inventory ?: "none",
                        armorBase64 ?: current?.armor ?: "none",
                        if (shareInventory) heldItemSlot else current?.hotbarSlot ?: 0,
                        if (shareGameMode) gameModeValue else current?.gamemode ?: 0,
                        syncStatus
                    )
                }

                // Save EnderChest
                if (shareEnderChest && enderchestBase64 != null) {
                    plugin.databaseManager.enderchestHandler.setData(uuid, playerName, enderchestBase64, syncStatus)
                }

                // Save Experience
                if (shareExperience) {
                    plugin.databaseManager.experienceHandler.setData(
                        uuid, playerName, exp, expToLevel, totalExperience, level, syncStatus
                    )
                }

                // Save Health/Food/Air
                if (shareHealth || shareFood || shareAir) {
                    val current = plugin.databaseManager.healthHandler.getData(uuid, playerName)
                    plugin.databaseManager.healthHandler.setData(
                        uuid, playerName,
                        health ?: current?.health ?: 20.0,
                        healthScale ?: current?.healthScale ?: 20.0,
                        maxHealth ?: current?.maxHealth ?: 20.0,
                        foodLevel ?: current?.food ?: 20,
                        saturation ?: current?.saturation ?: "5.0",
                        remainingAir ?: current?.air ?: 300,
                        maximumAir ?: current?.maxAir ?: 300,
                        syncStatus
                    )
                }

                // Save Potion Effects
                if (sharePotionEffects && effectsBase64 != null) {
                    plugin.databaseManager.potionEffectsHandler.setData(uuid, playerName, effectsBase64, syncStatus)
                }

                // Save Advancements
                if (shareAdvancement && advancementsBase64 != null) {
                    plugin.databaseManager.advancementHandler.setData(uuid, playerName, advancementsBase64, syncStatus)
                }

                // Save Location
                if (shareLocation || shareBedSpawn) {
                    val current = plugin.databaseManager.locationHandler.getData(uuid, playerName)
                    plugin.databaseManager.locationHandler.setData(
                        uuid, playerName,
                        locWorld ?: current?.world ?: "world",
                        locX ?: current?.x ?: 0.0,
                        locY ?: current?.y ?: 0.0,
                        locZ ?: current?.z ?: 0.0,
                        locYaw ?: current?.yaw ?: 0f,
                        locPitch ?: current?.pitch ?: 0f,
                        bedSpawn ?: current?.bedSpawn ?: "none",
                        syncStatus
                    )
                }

                // Save Economy
                if (shareEconomy && balance != null) {
                    plugin.databaseManager.economyHandler.setData(uuid, playerName, balance, syncStatus)
                }

                // Save CraftGUI
                if (shareCraftGui && craftGuiPref != null) {
                    plugin.databaseManager.craftGuiHandler.setData(
                        uuid, playerName,
                        getBooleanPreference(craftGuiPref, "isSoundEnabled", true),
                        getBooleanPreference(craftGuiPref, "isShowResultItems", true),
                        getBooleanPreference(craftGuiPref, "isCraftableOnly", false),
                        getBooleanPreference(craftGuiPref, "isStashEnabled", false),
                        syncStatus
                    )
                }

                if (syncVersion != null) {
                    if (!plugin.databaseManager.completeSync(uuid, syncVersion)) {
                        plugin.logger.warning("Skipped stale sync completion for $playerName")
                    }
                    if (plugin.databaseManager.storageMode == DatabaseManager.StorageMode.HYBRID) {
                        plugin.databaseManager.redisManager?.setSyncStatus(uuid, "true")
                    }
                }
                
                plugin.logger.info("Successfully saved data for $playerName")
            } catch (e: Exception) {
                plugin.logger.severe("Failed to save data for $playerName: ${e.message}")
                e.printStackTrace()
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
        databaseExecutor.shutdown()
        if (!databaseExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
            databaseExecutor.shutdownNow()
        }
    }

    fun isShutdown(): Boolean = databaseExecutor.isShutdown

    fun loadData(player: Player) {
        if (!plugin.databaseManager.isAvailable()) {
            plugin.logger.warning("Skipping load for ${player.name}: database is not available.")
            return
        }

        val uuid = player.uniqueId
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
                    val invData = plugin.databaseManager.inventoryHandler.getData(uuid, playerName)
                    if (invData != null) {
                        val contents = if (loadInventory && invData.inventory != "none") ItemSerializer.fromBase64(invData.inventory) else null
                        val armor = if (loadArmor && invData.armor != "none") ItemSerializer.fromBase64(invData.armor) else null
                        
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            if (contents != null) {
                                player.inventory.contents = contents
                                player.inventory.heldItemSlot = invData.hotbarSlot
                            }
                            if (armor != null) player.inventory.setArmorContents(armor)
                            if (loadGameMode) {
                                val gm = GameMode.getByValue(invData.gamemode)
                                if (gm != null) player.gameMode = gm
                            }
                        })
                    }
                }

                // EnderChest
                if (plugin.config.getBoolean("general.enableModules.shareEnderChest", true)) {
                    val ecData = plugin.databaseManager.enderchestHandler.getData(uuid, playerName)
                    if (ecData != null && ecData.enderchest != "none") {
                        val contents = ItemSerializer.fromBase64(ecData.enderchest)
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            player.enderChest.contents = contents
                        })
                    }
                }

                // Experience
                if (plugin.config.getBoolean("general.enableModules.shareExperience", true)) {
                    val expData = plugin.databaseManager.experienceHandler.getData(uuid, playerName)
                    if (expData != null) {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            player.exp = expData.exp
                            player.level = expData.expLvl
                            player.totalExperience = expData.totalExp
                        })
                    }
                }

                // Health/Food/Air
                if (loadHealth || loadFood || loadAir) {
                    val healthData = plugin.databaseManager.healthHandler.getData(uuid, playerName)
                    if (healthData != null) {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            if (loadHealth) {
                                @Suppress("DEPRECATION")
                                player.maxHealth = healthData.maxHealth
                                player.healthScale = healthData.healthScale
                                player.health = healthData.health.coerceIn(0.0, healthData.maxHealth)
                            }
                            if (loadFood) {
                                player.foodLevel = healthData.food
                                player.saturation = healthData.saturation.toFloatOrNull() ?: 5.0f
                            }
                            if (loadAir) {
                                player.maximumAir = healthData.maxAir
                                player.remainingAir = healthData.air
                            }
                        })
                    }
                }

                // Potion Effects
                if (plugin.config.getBoolean("general.enableModules.sharePotionEffects", true)) {
                    val potionData = plugin.databaseManager.potionEffectsHandler.getData(uuid, playerName)
                    if (potionData != null && potionData.potionEffects != "none") {
                        val effects = EffectSerializer.fromBase64(potionData.potionEffects)
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
                            player.addPotionEffects(effects)
                        })
                    }
                }

                // Advancements
                if (plugin.config.getBoolean("general.enableModules.shareAdvancement", false)) {
                    val advancementData = plugin.databaseManager.advancementHandler.getData(uuid, playerName)
                    if (advancementData != null && advancementData.advancements != "none") {
                        val advancements = AdvancementSerializer.fromBase64(advancementData.advancements)
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            AdvancementSerializer.apply(player, advancements)
                        })
                    }
                }

                // Location
                if (loadLocation || loadBedSpawn) {
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
                }

                // Economy
                if (plugin.config.getBoolean("general.enableModules.shareEconomy", true)) {
                    val econ = plugin.hookManager.economyHook.getEconomy()
                    if (econ != null) {
                        val econData = plugin.databaseManager.economyHandler.getData(uuid, playerName)
                        if (econData != null) {
                            val offlineMoney = plugin.databaseManager.economyHandler.consumeOfflineMoney(uuid) ?: 0.0
                            Bukkit.getScheduler().runTask(plugin, Runnable {
                                if (offlineMoney != 0.0) {
                                    if (offlineMoney > 0) {
                                        econ.depositPlayer(player, offlineMoney)
                                    } else {
                                        econ.withdrawPlayer(player, -offlineMoney)
                                    }
                                    plugin.logger.info("Applied offline economy changes for ${player.name}: $offlineMoney")
                                }

                                val currentBalance = econ.getBalance(player)
                                val difference = econData.money - currentBalance
                                if (difference > 0) {
                                    econ.depositPlayer(player, difference)
                                } else if (difference < 0) {
                                    econ.withdrawPlayer(player, -difference)
                                }
                            })
                        }
                    }
                }

                // CraftGUI
                if (plugin.config.getBoolean("general.enableModules.shareCraftGui", false)) {
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
                e.printStackTrace()
            }
        }
    }
    
    fun removeLoadedStatus(uuid: UUID) {
        loadedPlayers.remove(uuid)
    }

    fun setSyncStatus(uuid: UUID, playerName: String, isComplete: Boolean) {
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
        return plugin.databaseManager.getSyncState(uuid)?.status
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
