package net.azisaba.azisync.hook

import me.badbones69.crazyauctions.api.events.AuctionBuyEvent
import me.badbones69.crazyauctions.api.events.AuctionWinBidEvent
import net.azisaba.azisync.AziSync
import net.azisaba.azisync.util.EconomyAudit
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.UUID

class CrazyAuctionsHook(private val plugin: AziSync) : Listener {

    @EventHandler
    fun onAuctionBuy(event: AuctionBuyEvent) {
        val price = event.price.toDouble()
        val sellerUuid = resolveUuid(event.sellerName) ?: return

        val player = Bukkit.getPlayer(sellerUuid)
        if (player == null || !player.isOnline) {
            addMoney(sellerUuid, price, "CrazyAuctions:buy:seller")
        }
    }

    @EventHandler
    fun onAuctionBidWin(event: AuctionWinBidEvent) {
        val bid = event.bid.toDouble()
        val sellerUuid = resolveUuid(event.sellerName) ?: return

        val sellerPlayer = Bukkit.getPlayer(sellerUuid)
        if (sellerPlayer == null || !sellerPlayer.isOnline) {
            addMoney(sellerUuid, bid, "CrazyAuctions:bid_win:seller")
        }

        val buyerUuid = winningBidderUuid(event)
        val buyerPlayer = buyerUuid?.let(Bukkit::getPlayer)
        if (buyerUuid != null && (buyerPlayer == null || !buyerPlayer.isOnline)) {
            takeMoney(buyerUuid, bid, "CrazyAuctions:bid_win:buyer")
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

    private fun takeMoney(uuid: java.util.UUID, amount: Double, source: String) {
        EconomyAudit.info(plugin, "OFFLINE_DELTA_QUEUED", uuid,
            details = arrayOf("amount" to -amount, "source" to source))
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            if (!plugin.databaseManager.economyHandler.addOfflineMoney(uuid, -amount)) {
                EconomyAudit.severe(plugin, "OFFLINE_DELTA_HANDLER_FAILED", uuid,
                    details = arrayOf("amount" to -amount, "source" to source))
            }
        })
    }

    private fun resolveUuid(value: String): UUID? {
        return runCatching { UUID.fromString(value) }.getOrElse {
            @Suppress("DEPRECATION")
            Bukkit.getOfflinePlayer(value).uniqueId
        }
    }

    /**
     * CrazyAuctions MPDB exposes the UUID so that an offline winning bidder
     * can be charged. Reflection retains compatibility with older releases
     * whose event only exposed an online Player.
     */
    private fun winningBidderUuid(event: AuctionWinBidEvent): UUID? {
        return runCatching {
            event.javaClass.getMethod("getPlayerUuid").invoke(event) as? UUID
        }.getOrNull() ?: event.player?.uniqueId
    }
}
