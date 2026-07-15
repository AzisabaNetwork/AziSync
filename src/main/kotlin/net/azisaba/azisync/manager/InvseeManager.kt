package net.azisaba.azisync.manager

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.util.ItemSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InvseeManager(private val plugin: AziSync) : Listener {

    // Maps viewing player's UUID to the target player's UUID
    private val offlineInvseeSessions = ConcurrentHashMap<UUID, UUID>()
    private val offlineEcseeSessions = ConcurrentHashMap<UUID, UUID>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun getOfflinePlayerUuid(name: String): UUID? {
        // Try getting from cache first
        @Suppress("DEPRECATION")
        val offlinePlayer = Bukkit.getOfflinePlayer(name)
        if (offlinePlayer.hasPlayedBefore()) {
            return offlinePlayer.uniqueId
        }

        val tableName = plugin.config.getString("database.TablesNames.inventoryTableName", "azisync_inventory")!!
        var uuid: UUID? = null
        try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = "SELECT `player_uuid` FROM `$tableName` WHERE `player_name` = ? LIMIT 1"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, name)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            uuid = UUID.fromString(rs.getString("player_uuid"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("Error fetching UUID for $name: ${e.message}")
        }
        return uuid
    }

    fun openInvsee(viewer: Player, targetName: String) {
        val targetPlayer = Bukkit.getPlayer(targetName)
        if (targetPlayer != null && targetPlayer.isOnline) {
            viewer.openInventory(targetPlayer.inventory)
            plugin.messageManager.sendMessage(viewer, "invsee_opened", mapOf("{player}" to targetPlayer.name))
            return
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val targetUuid = getOfflinePlayerUuid(targetName)
            if (targetUuid == null) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    plugin.messageManager.sendMessage(viewer, "player_not_found")
                })
                return@Runnable
            }

            val data = plugin.databaseManager.inventoryHandler.getData(targetUuid, targetName)
            if (data == null || data.inventory == "none") {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    plugin.messageManager.sendMessage(viewer, "player_not_found")
                })
                return@Runnable
            }

            val items = try {
                ItemSerializer.fromBase64(data.inventory)
            } catch (e: Exception) {
                arrayOfNulls<ItemStack>(36)
            }

            val armorItems = try {
                ItemSerializer.fromBase64(data.armor)
            } catch (e: Exception) {
                arrayOfNulls<ItemStack>(4)
            }

            Bukkit.getScheduler().runTask(plugin, Runnable {
                val gui = Bukkit.createInventory(null, 54, "$targetName's Inventory")
                for (i in 0 until 36) {
                    if (i < items.size) {
                        gui.setItem(i, items[i])
                    }
                }
                for (i in 0 until 4) {
                    if (i < armorItems.size) {
                        gui.setItem(45 + i, armorItems[i])
                    }
                }

                offlineInvseeSessions[viewer.uniqueId] = targetUuid
                viewer.openInventory(gui)
                plugin.messageManager.sendMessage(viewer, "invsee_opened", mapOf("{player}" to targetName))
            })
        })
    }

    fun openEcsee(viewer: Player, targetName: String) {
        val targetPlayer = Bukkit.getPlayer(targetName)
        if (targetPlayer != null && targetPlayer.isOnline) {
            viewer.openInventory(targetPlayer.enderChest)
            plugin.messageManager.sendMessage(viewer, "invsee_opened", mapOf("{player}" to targetPlayer.name))
            return
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val targetUuid = getOfflinePlayerUuid(targetName)
            if (targetUuid == null) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    plugin.messageManager.sendMessage(viewer, "player_not_found")
                })
                return@Runnable
            }

            val data = plugin.databaseManager.enderchestHandler.getData(targetUuid, targetName)
            if (data == null || data.enderchest == "none") {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    plugin.messageManager.sendMessage(viewer, "player_not_found")
                })
                return@Runnable
            }

            val items = try {
                ItemSerializer.fromBase64(data.enderchest)
            } catch (e: Exception) {
                arrayOfNulls<ItemStack>(27)
            }

            Bukkit.getScheduler().runTask(plugin, Runnable {
                val gui = Bukkit.createInventory(null, 27, "$targetName's EnderChest")
                for (i in 0 until 27) {
                    if (i < items.size) {
                        gui.setItem(i, items[i])
                    }
                }

                offlineEcseeSessions[viewer.uniqueId] = targetUuid
                viewer.openInventory(gui)
                plugin.messageManager.sendMessage(viewer, "invsee_opened", mapOf("{player}" to targetName))
            })
        })
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        
        if (offlineInvseeSessions.containsKey(player.uniqueId)) {
            val targetUuid = offlineInvseeSessions.remove(player.uniqueId)!!
            val gui = event.inventory
            
            val items = arrayOfNulls<ItemStack>(36)
            for (i in 0 until 36) {
                items[i] = gui.getItem(i)
            }
            
            val armorItems = arrayOfNulls<ItemStack>(4)
            for (i in 0 until 4) {
                armorItems[i] = gui.getItem(45 + i)
            }
            
            val title = event.view.title
            val targetName = title.removeSuffix("'s Inventory")

            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                val invBase64 = ItemSerializer.toBase64(items)
                val armorBase64 = ItemSerializer.toBase64(armorItems)
                
                val currentData = plugin.databaseManager.inventoryHandler.getData(targetUuid, targetName)
                if (currentData != null) {
                    plugin.databaseManager.inventoryHandler.setData(
                        targetUuid, 
                        targetName, 
                        invBase64, 
                        armorBase64, 
                        currentData.hotbarSlot, 
                        currentData.gamemode, 
                        currentData.syncComplete
                    )
                    plugin.logger.info("Admin ${player.name} updated offline inventory of $targetName")
                }
            })
        }
        
        if (offlineEcseeSessions.containsKey(player.uniqueId)) {
            val targetUuid = offlineEcseeSessions.remove(player.uniqueId)!!
            val gui = event.inventory
            
            val items = arrayOfNulls<ItemStack>(27)
            for (i in 0 until 27) {
                items[i] = gui.getItem(i)
            }
            
            val title = event.view.title
            val targetName = title.removeSuffix("'s EnderChest")

            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                val ecBase64 = ItemSerializer.toBase64(items)
                
                val currentData = plugin.databaseManager.enderchestHandler.getData(targetUuid, targetName)
                if (currentData != null) {
                    plugin.databaseManager.enderchestHandler.setData(
                        targetUuid, 
                        targetName, 
                        ecBase64, 
                        currentData.syncComplete
                    )
                    plugin.logger.info("Admin ${player.name} updated offline enderchest of $targetName")
                }
            })
        }
    }
}
