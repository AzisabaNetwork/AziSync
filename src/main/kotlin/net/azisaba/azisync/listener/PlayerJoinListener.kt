package net.azisaba.azisync.listener

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.sync.SyncManager
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class PlayerJoinListener(private val plugin: AziSync) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        val playerName = event.player.name
        val loadDelayTicks = plugin.config.getLong("general.loadDelayTicks", 5L).coerceAtLeast(0L)
        object : org.bukkit.scheduler.BukkitRunnable() {
            var count = 0
            override fun run() {
                val p = Bukkit.getPlayer(uuid)
                if (p == null || !p.isOnline || plugin.syncManager.isLoaded(p) || count > 60) {
                    this.cancel()
                    return
                }
                count++
            }
        }.runTaskTimer(plugin, 0L, 20L)

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            when (plugin.syncManager.awaitHandoff(uuid, playerName)) {
                SyncManager.HandoffResult.READY -> Unit
                SyncManager.HandoffResult.UNAVAILABLE -> return@Runnable
                SyncManager.HandoffResult.TIMEOUT -> {
                    // The previous server has not finished saving. Loading now would apply stale
                    // data that a later save would write back over the newer copy, so never load.
                    plugin.logger.warning("Data sync timed out for player $playerName; refusing to load stale data.")
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        Bukkit.getPlayer(uuid)?.kickPlayer("Data sync timeout. Please reconnect.")
                    })
                    return@Runnable
                }
                SyncManager.HandoffResult.FAILED -> {
                    plugin.logger.warning("Data sync failed for player $playerName (previous server could not save).")
                    if (plugin.config.getBoolean("general.kickOnFailedSync", false)) {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            Bukkit.getPlayer(uuid)?.kickPlayer("Data sync failed. Please reconnect.")
                        })
                        return@Runnable
                    }
                }
            }
            
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (!plugin.isEnabled || !plugin.databaseManager.isAvailable()) {
                    return@Runnable
                }
                val delayedPlayer = Bukkit.getPlayer(uuid) ?: return@Runnable
                if (delayedPlayer.isOnline && !plugin.syncManager.isLoaded(delayedPlayer)) {
                    plugin.syncManager.loadData(delayedPlayer)
                }
            }, loadDelayTicks)
        })
    }
}
