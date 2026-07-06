package net.azisaba.azisync.hook

import me.badbones69.crazyauctions.api.events.AuctionBuyEvent
import me.badbones69.crazyauctions.api.events.AuctionWinBidEvent
import net.azisaba.azisync.AziSync
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

class CrazyAuctionsHook(private val plugin: AziSync) : Listener {

    @EventHandler
    fun onAuctionBuy(event: AuctionBuyEvent) {
        val sellerName = event.sellerName
        val price = event.price.toDouble()
        
        val player = Bukkit.getPlayer(sellerName)
        if (player == null || !player.isOnline) {
            addMoney(sellerName, price)
        }
    }

    @EventHandler
    fun onAuctionBidWin(event: AuctionWinBidEvent) {
        val sellerName = event.sellerName
        val bid = event.bid.toDouble()
        
        val sellerPlayer = Bukkit.getPlayer(sellerName)
        if (sellerPlayer == null || !sellerPlayer.isOnline) {
            addMoney(sellerName, bid)
        }
        
        val buyerPlayer = event.player
        if (buyerPlayer != null && !buyerPlayer.isOnline) {
            takeMoney(buyerPlayer.uniqueId, bid)
        }
    }

    private fun addMoney(playerName: String, amount: Double) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            @Suppress("DEPRECATION")
            val offlinePlayer = Bukkit.getOfflinePlayer(playerName)
            val uuid = offlinePlayer.uniqueId
            
            if (plugin.databaseManager.economyHandler.hasAccount(uuid)) {
                val currentOffline = plugin.databaseManager.economyHandler.getOfflineBalance(uuid) ?: 0.0
                plugin.databaseManager.economyHandler.setOfflineMoney(uuid, currentOffline + amount)
            }
        })
    }

    private fun takeMoney(uuid: java.util.UUID, amount: Double) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            if (plugin.databaseManager.economyHandler.hasAccount(uuid)) {
                val currentOffline = plugin.databaseManager.economyHandler.getOfflineBalance(uuid) ?: 0.0
                plugin.databaseManager.economyHandler.setOfflineMoney(uuid, currentOffline - amount)
            }
        })
    }
}