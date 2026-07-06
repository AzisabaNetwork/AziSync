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
                // Changed to pitch 1.0 and frequency every 1 second to be less annoying
                p.playSound(p.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f)
                count++
            }
        }.runTaskTimer(plugin, 0L, 20L) // Changed to 20L (1 second)

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, Runnable {
            val player = Bukkit.getPlayer(uuid)
            if (player != null && player.isOnline) {
                plugin.syncManager.loadData(player)
            }
        }, 10L)
    }
}