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
        val price = event.newPrice
        
        if (event.type == ShopBuySellEvent.Type.SELL) {
            if (vendor != null && !vendor.isOnline) {
                takeMoney(vendor.uniqueId, price)
            }
        } else if (event.type == ShopBuySellEvent.Type.BUY) {
            if (vendor != null && !vendor.isOnline) {
                addMoney(vendor.uniqueId, price)
            }
        }
    }

    private fun addMoney(uuid: UUID, amount: Double) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.databaseManager.economyHandler.addOfflineMoney(uuid, amount)
        })
    }

    private fun takeMoney(uuid: UUID, amount: Double) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.databaseManager.economyHandler.addOfflineMoney(uuid, -amount)
        })
    }
}
