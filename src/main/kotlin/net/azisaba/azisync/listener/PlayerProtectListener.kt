package net.azisaba.azisync.listener

import net.azisaba.azisync.AziSync
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerPickupItemEvent
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerProtectListener(private val plugin: AziSync) : Listener {

    private val messageCooldown = ConcurrentHashMap<UUID, Long>()
    private val soundCooldown = ConcurrentHashMap<UUID, Long>()

    private fun handleBlock(player: Player, event: org.bukkit.event.Cancellable) {
        if (!plugin.syncManager.isLoaded(player)) {
            event.isCancelled = true
            
            val now = System.currentTimeMillis()
            val lastSoundTime = soundCooldown[player.uniqueId] ?: 0L
            if (!plugin.config.getBoolean("general.disableSounds", false) && now - lastSoundTime > 500) {
                player.playSound(player.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f)
                soundCooldown[player.uniqueId] = now
            }

            val lastTime = messageCooldown[player.uniqueId] ?: 0L
            if (now - lastTime > 3000) {
                plugin.messageManager.sendMessage(player, "action_blocked_loading")
                messageCooldown[player.uniqueId] = now
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        handleBlock(event.player, event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        handleBlock(event.player, event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        handleBlock(event.player, event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        handleBlock(event.player, event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        handleBlock(player, event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        handleBlock(event.player, event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerPickupItem(event: PlayerPickupItemEvent) {
        handleBlock(event.player, event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!plugin.syncManager.isLoaded(player)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        handleBlock(player, event)
    }
}
