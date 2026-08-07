package net.azisaba.azisync.hook

import com.Acrobot.ChestShop.Events.TransactionEvent
import net.azisaba.azisync.AziSync
import net.azisaba.azisync.util.EconomyAudit
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
            addMoney(account.uuid, event.exactPrice, "ChestShop:buy:owner")
        } else if (type == TransactionEvent.TransactionType.SELL) {
            takeMoney(account.uuid, event.exactPrice, "ChestShop:sell:owner")
        }
    }

    private fun addMoney(uuid: java.util.UUID, amount: BigDecimal, source: String) {
        EconomyAudit.info(plugin, "OFFLINE_DELTA_QUEUED", uuid,
            details = arrayOf("amount" to amount, "source" to source))
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            if (!plugin.databaseManager.economyHandler.addOfflineMoney(uuid, amount.toDouble())) {
                EconomyAudit.severe(plugin, "OFFLINE_DELTA_HANDLER_FAILED", uuid,
                    details = arrayOf("amount" to amount, "source" to source))
            }
        })
    }

    private fun takeMoney(uuid: java.util.UUID, amount: BigDecimal, source: String) {
        EconomyAudit.info(plugin, "OFFLINE_DELTA_QUEUED", uuid,
            details = arrayOf("amount" to amount.negate(), "source" to source))
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            if (!plugin.databaseManager.economyHandler.addOfflineMoney(uuid, -amount.toDouble())) {
                EconomyAudit.severe(plugin, "OFFLINE_DELTA_HANDLER_FAILED", uuid,
                    details = arrayOf("amount" to amount.negate(), "source" to source))
            }
        })
    }
}
