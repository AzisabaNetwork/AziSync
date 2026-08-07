package net.azisaba.azisync.listener

import net.azisaba.azisync.AziSync
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
            var syncFailed = false
            val pollMillis = plugin.config.getLong("general.syncWait.pollMillis", 50L).coerceIn(10L, 250L)
            val timeoutMillis = plugin.config.getLong("general.syncWait.timeoutMillis", 10000L).coerceAtLeast(0L)
            val handoffGraceMillis = plugin.config.getLong("general.syncWait.handoffGraceMillis", 100L).coerceAtLeast(0L)

            // Give the source server's dedicated sync-state writer a short window
            // to publish "saving" before accepting an older "complete" state.
            if (handoffGraceMillis > 0) Thread.sleep(handoffGraceMillis)
            val waitStarted = System.nanoTime()
            
            while (true) {
                if (!plugin.isEnabled || !plugin.databaseManager.isAvailable()) {
                    return@Runnable
                }
                when (val status = plugin.syncManager.getSyncStatus(uuid)) {
                    null, "complete" -> break
                    "failed" -> {
                        syncFailed = true
                        break
                    }
                    "saving" -> Unit
                    else -> {
                        plugin.logger.warning("Unknown sync state '$status' for player $playerName")
                        syncFailed = true
                        break
                    }
                }
                if (java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStarted) >= timeoutMillis) {
                    syncFailed = true
                    break
                }
                Thread.sleep(pollMillis)
            }
            
            if (syncFailed) {
                plugin.logger.warning("Data sync failed or timed out for player $playerName")
                if (plugin.config.getBoolean("general.kickOnFailedSync", false)) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        Bukkit.getPlayer(uuid)?.kickPlayer("Data sync timeout. Please reconnect.")
                    })
                    return@Runnable
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
