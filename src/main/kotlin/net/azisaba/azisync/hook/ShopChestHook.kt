package net.azisaba.azisync.hook

import de.epiceric.shopchest.event.ShopBuySellEvent
import de.epiceric.shopchest.shop.Shop
import net.azisaba.azisync.AziSync
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.UUID

class ShopChestHook(private val plugin: AziSync) : Listener {

    @EventHandler
    fun onShopTransaction(event: ShopBuySellEvent) {
        if (event.shop.shopType == Shop.ShopType.ADMIN) {
            return
        }
        
        val vendor = event.shop.vendor
        
        if (event.type == ShopBuySellEvent.Type.SELL) {
            if (vendor != null && !vendor.isOnline) {
                takeMoney(vendor.uniqueId, event.shop.sellPrice)
            }
        } else if (event.type == ShopBuySellEvent.Type.BUY) {
            if (vendor != null && !vendor.isOnline) {
                addMoney(vendor.uniqueId, event.shop.buyPrice)
            }
        }
    }

    private fun addMoney(uuid: UUID, amount: Double) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            if (plugin.databaseManager.economyHandler.hasAccount(uuid)) {
                val currentOffline = plugin.databaseManager.economyHandler.getOfflineBalance(uuid) ?: 0.0
                plugin.databaseManager.economyHandler.setOfflineMoney(uuid, currentOffline + amount)
            }
        })
    }

    private fun takeMoney(uuid: UUID, amount: Double) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            if (plugin.databaseManager.economyHandler.hasAccount(uuid)) {
                val currentOffline = plugin.databaseManager.economyHandler.getOfflineBalance(uuid) ?: 0.0
                plugin.databaseManager.economyHandler.setOfflineMoney(uuid, currentOffline - amount)
            }
        })
    }
}