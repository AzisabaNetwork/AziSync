package net.azisaba.azisync.listener

import net.azisaba.azisync.AziSync
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

class PlayerQuitListener(private val plugin: AziSync) : Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val uuid = player.uniqueId
        val playerName = player.name

        plugin.syncManager.saveData(player, true)
        
        plugin.syncManager.removeLoadedStatus(uuid)
    }
}