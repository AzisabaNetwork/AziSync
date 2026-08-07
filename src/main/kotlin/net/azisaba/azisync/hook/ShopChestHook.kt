package net.azisaba.azisync.hook

import de.epiceric.shopchest.event.ShopBuySellEvent
import de.epiceric.shopchest.shop.Shop
import net.azisaba.azisync.AziSync
import net.azisaba.azisync.util.EconomyAudit
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
                takeMoney(vendor.uniqueId, price, "ShopChest:sell:vendor")
            }
        } else if (event.type == ShopBuySellEvent.Type.BUY) {
            if (vendor != null && !vendor.isOnline) {
                addMoney(vendor.uniqueId, price, "ShopChest:buy:vendor")
            }
        }
    }

    private fun addMoney(uuid: UUID, amount: Double, source: String) {
        EconomyAudit.info(plugin, "OFFLINE_DELTA_QUEUED", uuid,
            details = arrayOf("amount" to amount, "source" to source))
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            if (!plugin.databaseManager.economyHandler.addOfflineMoney(uuid, amount)) {
                EconomyAudit.severe(plugin, "OFFLINE_DELTA_HANDLER_FAILED", uuid,
                    details = arrayOf("amount" to amount, "source" to source))
            }
        })
    }

    private fun takeMoney(uuid: UUID, amount: Double, source: String) {
        EconomyAudit.info(plugin, "OFFLINE_DELTA_QUEUED", uuid,
            details = arrayOf("amount" to -amount, "source" to source))
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            if (!plugin.databaseManager.economyHandler.addOfflineMoney(uuid, -amount)) {
                EconomyAudit.severe(plugin, "OFFLINE_DELTA_HANDLER_FAILED", uuid,
                    details = arrayOf("amount" to -amount, "source" to source))
            }
        })
    }
}
