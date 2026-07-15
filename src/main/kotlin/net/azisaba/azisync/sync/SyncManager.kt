package net.azisaba.azisync.sync

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.util.AdvancementSerializer
import net.azisaba.azisync.util.EffectSerializer
import net.azisaba.azisync.util.ItemSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SyncManager(private val plugin: AziSync) {
    
    private val loadedPlayers = ConcurrentHashMap<UUID, Boolean>()

    fun isLoaded(player: Player): Boolean {
        return loadedPlayers[player.uniqueId] ?: false
    }

    fun saveData(player: Player, syncComplete: Boolean = false) {
        val uuid = player.uniqueId
        val playerName = player.name
        val syncStatus = if (syncComplete) "true" else "false"

        // Inventory
        val shareInventory = plugin.config.getBoolean("general.enableModules.shareInventory", true)
        val inventoryBase64 = if (shareInventory) ItemSerializer.toBase64(player.inventory.contents) else null
        val armorBase64 = if (shareInventory) ItemSerializer.toBase64(player.inventory.armorContents) else null
        val heldItemSlot = if (shareInventory) player.inventory.heldItemSlot else 0
        @Suppress("DEPRECATION")
        val gameModeValue = if (shareInventory) player.gameMode.value else 0
        
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
        val health = if (shareHealth) player.health else 20.0
        val healthScale = if (shareHealth) player.healthScale else 20.0
        @Suppress("DEPRECATION")
        val maxHealth = if (shareHealth) player.maxHealth else 20.0
        val foodLevel = if (shareHealth) player.foodLevel else 20
        val saturation = if (shareHealth) player.saturation.toString() else "5.0"
        val remainingAir = if (shareHealth) player.remainingAir else 300
        val maximumAir = if (shareHealth) player.maximumAir else 300
        
        // Potion Effects
        val sharePotionEffects = plugin.config.getBoolean("general.enableModules.sharePotionEffects", true)
        val effectsBase64 = if (sharePotionEffects) EffectSerializer.toBase64(player.activePotionEffects) else null

        // Advancements
        val shareAdvancement = plugin.config.getBoolean("general.enableModules.shareAdvancement", false)
        val advancementsBase64 = if (shareAdvancement) AdvancementSerializer.toBase64(player) else null
        
        // Location
        val shareLocation = plugin.config.getBoolean("general.enableModules.shareLocation", true)
        val locWorld = if (shareLocation) player.location.world?.name ?: "world" else "world"
        val locX = if (shareLocation) player.location.x else 0.0
        val locY = if (shareLocation) player.location.y else 0.0
        val locZ = if (shareLocation) player.location.z else 0.0
        val locYaw = if (shareLocation) player.location.yaw else 0f
        val locPitch = if (shareLocation) player.location.pitch else 0f
        val bedSpawn = if (shareLocation) {
            player.bedSpawnLocation?.let { "${it.world?.name},${it.x},${it.y},${it.z}" } ?: "none"
        } else "none"
        
        // Economy
        val shareEconomy = plugin.config.getBoolean("general.enableModules.shareEconomy", true)
        val econ = plugin.hookManager.economyHook.getEconomy()
        val balance = if (shareEconomy && econ != null) econ.getBalance(player) else null

        // CraftGUI
        val shareCraftGui = plugin.config.getBoolean("general.enableModules.shareCraftGui", false)
        val craftGuiPref = if (shareCraftGui) getCraftGuiPreference(uuid) else null

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                if (syncComplete) {
                    setSyncStatus(uuid, playerName, false)
                }
                
                var waitCount = 0
                while (plugin.hookManager.jobsHook.isPlayerSaving(uuid) && waitCount < 20) {
                    Thread.sleep(250)
                    waitCount++
                }
                if (waitCount >= 20) {
                    plugin.logger.warning("Jobs saving task for $playerName timed out after 5 seconds. Saving AziSync data anyway.")
                } else if (waitCount > 0) {
                    plugin.logger.info("Waited ${waitCount * 250}ms for Jobs to finish saving $playerName's data.")
                }

                // Save Inventory
                if (shareInventory && inventoryBase64 != null && armorBase64 != null) {
                    plugin.databaseManager.inventoryHandler.setData(
                        uuid, playerName, inventoryBase64, armorBase64, heldItemSlot, gameModeValue, syncStatus
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
                if (shareHealth) {
                    plugin.databaseManager.healthHandler.setData(
                        uuid, playerName, health, healthScale, maxHealth, foodLevel, saturation, remainingAir, maximumAir, syncStatus
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
                if (shareLocation) {
                    plugin.databaseManager.locationHandler.setData(
                        uuid, playerName, locWorld, locX, locY, locZ, locYaw, locPitch, bedSpawn, syncStatus
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
                
                plugin.logger.info("Successfully saved data for $playerName")
            } catch (e: Exception) {
                plugin.logger.severe("Failed to save data for $playerName: ${e.message}")
                e.printStackTrace()
            }
        })
    }

    fun loadData(player: Player) {
        val uuid = player.uniqueId
        val playerName = player.name
        if (!plugin.config.getBoolean("general.disableSounds", false)) {
            player.playSound(player.location, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                // Inventory
                if (plugin.config.getBoolean("general.enableModules.shareInventory", true)) {
                    val invData = plugin.databaseManager.inventoryHandler.getData(uuid, playerName)
                    if (invData != null && invData.inventory != "none") {
                        val contents = ItemSerializer.fromBase64(invData.inventory)
                        val armor = ItemSerializer.fromBase64(invData.armor)
                        
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            player.inventory.contents = contents
                            player.inventory.setArmorContents(armor)
                            player.inventory.heldItemSlot = invData.hotbarSlot
                            
                            val gm = GameMode.getByValue(invData.gamemode)
                            if (gm != null) {
                                player.gameMode = gm
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
                if (plugin.config.getBoolean("general.enableModules.shareHealth", true)) {
                    val healthData = plugin.databaseManager.healthHandler.getData(uuid, playerName)
                    if (healthData != null) {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            @Suppress("DEPRECATION")
                            player.maxHealth = healthData.maxHealth
                            player.healthScale = healthData.healthScale
                            player.health = healthData.health.coerceIn(0.0, healthData.maxHealth)
                            player.foodLevel = healthData.food
                            player.saturation = healthData.saturation.toFloatOrNull() ?: 5.0f
                            player.maximumAir = healthData.maxAir
                            player.remainingAir = healthData.air
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
                if (plugin.config.getBoolean("general.enableModules.shareLocation", true)) {
                    val locData = plugin.databaseManager.locationHandler.getData(uuid, playerName)
                    if (locData != null) {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            val world = Bukkit.getWorld(locData.world)
                            if (world != null) {
                                player.teleport(Location(world, locData.x, locData.y, locData.z, locData.yaw, locData.pitch))
                            }
                            if (locData.bedSpawn != "none") {
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
                            Bukkit.getScheduler().runTask(plugin, Runnable {
                                val offlineMoney = econData.offlineMoney
                                if (offlineMoney != 0.0) {
                                    if (offlineMoney > 0) {
                                        econ.depositPlayer(player, offlineMoney)
                                    } else {
                                        econ.withdrawPlayer(player, -offlineMoney)
                                    }
                                    plugin.databaseManager.economyHandler.setOfflineMoney(player.uniqueId, 0.0)
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
                
                loadedPlayers[player.uniqueId] = true
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (!plugin.config.getBoolean("general.disableSounds", false)) {
                        player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
                    }
                    plugin.messageManager.sendMessage(player, "sync_complete")
                })
                plugin.logger.info("Successfully loaded data for ${player.name}")
            } catch (e: Exception) {
                plugin.logger.severe("Failed to load data for ${player.name}: ${e.message}")
                e.printStackTrace()
            }
        })
    }
    
    fun removeLoadedStatus(uuid: UUID) {
        loadedPlayers.remove(uuid)
    }

    fun setSyncStatus(uuid: UUID, playerName: String, isComplete: Boolean) {
        val statusStr = if (isComplete) "true" else "false"
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
        if (plugin.config.getBoolean("general.enableModules.shareInventory", true)) {
            return plugin.databaseManager.inventoryHandler.getSyncStatus(uuid)
        }
        if (plugin.config.getBoolean("general.enableModules.shareEnderChest", true)) {
            return plugin.databaseManager.enderchestHandler.getSyncStatus(uuid)
        }
        if (plugin.config.getBoolean("general.enableModules.shareExperience", true)) {
            return plugin.databaseManager.experienceHandler.getSyncStatus(uuid)
        }
        if (plugin.config.getBoolean("general.enableModules.shareHealth", true)) {
            return plugin.databaseManager.healthHandler.getSyncStatus(uuid)
        }
        if (plugin.config.getBoolean("general.enableModules.sharePotionEffects", true)) {
            return plugin.databaseManager.potionEffectsHandler.getSyncStatus(uuid)
        }
        if (plugin.config.getBoolean("general.enableModules.shareAdvancement", false)) {
            return plugin.databaseManager.advancementHandler.getSyncStatus(uuid)
        }
        if (plugin.config.getBoolean("general.enableModules.shareLocation", true)) {
            return plugin.databaseManager.locationHandler.getSyncStatus(uuid)
        }
        if (plugin.config.getBoolean("general.enableModules.shareEconomy", true)) {
            return plugin.databaseManager.economyHandler.getSyncStatus(uuid)
        }
        if (plugin.config.getBoolean("general.enableModules.shareCraftGui", false)) {
            return plugin.databaseManager.craftGuiHandler.getSyncStatus(uuid)
        }
        return null
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
