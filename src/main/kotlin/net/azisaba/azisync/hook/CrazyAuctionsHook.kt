package net.azisaba.azisync.hook

import me.badbones69.crazyauctions.api.events.AuctionBuyEvent
import me.badbones69.crazyauctions.api.events.AuctionWinBidEvent
import net.azisaba.azisync.AziSync
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
            addMoney(sellerUuid, price)
        }
    }

    @EventHandler
    fun onAuctionBidWin(event: AuctionWinBidEvent) {
        val bid = event.bid.toDouble()
        val sellerUuid = resolveUuid(event.sellerName) ?: return

        val sellerPlayer = Bukkit.getPlayer(sellerUuid)
        if (sellerPlayer == null || !sellerPlayer.isOnline) {
            addMoney(sellerUuid, bid)
        }

        val buyerUuid = winningBidderUuid(event)
        val buyerPlayer = buyerUuid?.let(Bukkit::getPlayer)
        if (buyerUuid != null && (buyerPlayer == null || !buyerPlayer.isOnline)) {
            takeMoney(buyerUuid, bid)
        }
    }

    private fun addMoney(uuid: UUID, amount: Double) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.databaseManager.economyHandler.addOfflineMoney(uuid, amount)
        })
    }

    private fun takeMoney(uuid: java.util.UUID, amount: Double) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.databaseManager.economyHandler.addOfflineMoney(uuid, -amount)
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
