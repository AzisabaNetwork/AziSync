package net.azisaba.azisync.hook

import com.Acrobot.ChestShop.Events.TransactionEvent
import net.azisaba.azisync.AziSync
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.math.BigDecimal

class ChestShopHook(private val plugin: AziSync) : Listener {

    @EventHandler
    fun onTransaction(event: TransactionEvent) {
        if (event.isCancelled) return

        val type = event.transactionType
        val account = event.ownerAccount
        val player = Bukkit.getPlayer(account.uuid)

        if (player != null && player.isOnline) return

        if (type == TransactionEvent.TransactionType.BUY) {
            addMoney(account.uuid, event.exactPrice)
        } else if (type == TransactionEvent.TransactionType.SELL) {
            takeMoney(account.uuid, event.exactPrice)
        }
    }

    private fun addMoney(uuid: java.util.UUID, amount: BigDecimal) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.databaseManager.economyHandler.addOfflineMoney(uuid, amount.toDouble())
        })
    }

    private fun takeMoney(uuid: java.util.UUID, amount: BigDecimal) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.databaseManager.economyHandler.addOfflineMoney(uuid, -amount.toDouble())
        })
    }
}
