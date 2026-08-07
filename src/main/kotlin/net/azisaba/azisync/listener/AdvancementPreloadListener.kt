package net.azisaba.azisync.listener

import net.azisaba.azisync.AziSync
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerLoginEvent

class AdvancementPreloadListener(private val plugin: AziSync) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAsyncPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        if (!plugin.config.getBoolean("general.enableModules.shareAdvancement", false)) return
        plugin.syncManager.preloadAdvancements(event.uniqueId, event.name)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerLogin(event: PlayerLoginEvent) {
        if (event.result != PlayerLoginEvent.Result.ALLOWED) return
        if (!plugin.config.getBoolean("general.enableModules.shareAdvancement", false)) return
        plugin.syncManager.applyPreloadedAdvancements(event.player)
    }
}
