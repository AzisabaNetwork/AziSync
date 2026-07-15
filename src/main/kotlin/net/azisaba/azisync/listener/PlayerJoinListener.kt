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
        object : org.bukkit.scheduler.BukkitRunnable() {
            var count = 0
            override fun run() {
                val p = Bukkit.getPlayer(uuid)
                if (p == null || !p.isOnline || plugin.syncManager.isLoaded(p) || count > 60) {
                    this.cancel()
                    return
                }
                p.playSound(p.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f)
                count++
            }
        }.runTaskTimer(plugin, 0L, 20L)

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val player = Bukkit.getPlayer(uuid) ?: return@Runnable
            var waitCount = 0
            
            // Wait until sync_complete is true
            while (waitCount < 40) {
                val status = plugin.syncManager.getSyncStatus(uuid)
                if (status == null || status == "true") {
                    break
                }
                Thread.sleep(250)
                waitCount++
            }
            
            if (waitCount >= 40) {
                plugin.logger.warning("Data sync timeout for player ${player.name}")
                if (plugin.config.getBoolean("general.kickOnFailedSync", false)) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        player.kickPlayer("Data sync timeout. Please reconnect.")
                    })
                    return@Runnable
                }
            }
            
            if (player.isOnline) {
                plugin.syncManager.loadData(player)
            }
        })
    }
}
