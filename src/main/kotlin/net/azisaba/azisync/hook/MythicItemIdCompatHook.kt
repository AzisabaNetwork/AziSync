package net.azisaba.azisync.hook

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.util.ItemSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method
import java.util.logging.Level

/**
 * Bridge to MythicItemIdCompat, which keeps MythicMobs 4 (1.15.2) items working on MythicMobs 5.
 *
 * - While it is enabled, [ItemSerializer] adds `mythicmobs:type` to legacy `MYTHIC_TYPE` items it
 *   decodes (honouring its `excluded-mmids`), so MPDB data is migrated with working MM IDs.
 * - After AziSync applies an inventory or ender chest loaded from the database, the items are passed
 *   to MythicItemIdCompat again, and the player's inventory gets its automatic legacy-definition repair.
 *   MythicItemIdCompat's own join repair usually runs before AziSync's asynchronous load finishes,
 *   so without this call that repair would miss the loaded items.
 *
 * Everything goes through reflection: MythicItemIdCompat is optional and built for 1.21.11 only.
 */
object MythicItemIdCompatHook {
    private const val PLUGIN_NAME = "MythicItemIdCompat"

    private class Api(
        val plugin: Plugin,
        val preserveIds: Method?,
        val preserveId: Method?,
        val isExcluded: Method?,
        val repairInventory: Method?
    )

    @Volatile
    private var cached: Api? = null
    private var enabledByConfig = true
    private var errorLogs = 0

    /** Called from AziSync's runtime initialization (also on /azisync reload). */
    fun setup(plugin: AziSync) {
        enabledByConfig = plugin.config.getBoolean("hooks.mythicItemIdCompat.enabled", true)
        cached = null
        ItemSerializer.mythicIdExcluded = null
        val api = api() ?: run {
            if (enabledByConfig) plugin.logger.info("$PLUGIN_NAME not found; legacy MythicMobs items keep only their MYTHIC_TYPE tag.")
            return
        }
        val excluded = api.isExcluded
        ItemSerializer.mythicIdExcluded = if (excluded != null) {
            { id: String ->
                val current = api()
                if (current == null) true // plugin went away: leave the item as it is
                else try {
                    current.isExcluded?.invoke(current.plugin, id) as? Boolean ?: false
                } catch (_: Throwable) {
                    true
                }
            }
        } else {
            { _: String -> false } // older MythicItemIdCompat without exclusions API
        }
        plugin.logger.info("Hooked into $PLUGIN_NAME ${api.plugin.description.version}: legacy MythicMobs items get their MMID while loading and migrating.")
    }

    fun isAvailable(): Boolean = api() != null

    private fun api(): Api? {
        if (!enabledByConfig) return null
        val target = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME)
        if (target == null || !target.isEnabled) return null
        val current = cached
        if (current != null && current.plugin === target) return current
        val type = target.javaClass
        fun find(name: String, vararg params: Class<*>): Method? = try {
            type.getMethod(name, *params)
        } catch (_: NoSuchMethodException) {
            null
        }
        val api = Api(
            target,
            find("preserveMythicIds", Array<ItemStack>::class.java),
            find("preserveMythicId", ItemStack::class.java),
            find("isMythicIdExcluded", String::class.java),
            find("repairPlayerInventory", Player::class.java)
        )
        if (api.preserveId == null && api.preserveIds == null) return null
        cached = api
        return api
    }

    /** Gives MMIDs to legacy items in place. Main thread only. */
    fun preserve(items: Array<ItemStack?>?): Int {
        if (items == null) return 0
        val api = api() ?: return 0
        return try {
            if (api.preserveIds != null) {
                api.preserveIds.invoke(api.plugin, items) as? Int ?: 0
            } else {
                items.sumOf { if (it == null) 0 else (api.preserveId!!.invoke(api.plugin, it) as? Int ?: 0) }
            }
        } catch (t: Throwable) {
            logError("preserve MythicMobs IDs", t)
            0
        }
    }

    /** Runs MythicItemIdCompat's automatic inventory repair now. Main thread only. */
    fun repairInventory(player: Player) {
        val api = api() ?: return
        val method = api.repairInventory ?: return
        try {
            method.invoke(api.plugin, player)
        } catch (t: Throwable) {
            logError("repair the inventory of ${player.name}", t)
        }
    }

    private fun logError(action: String, t: Throwable) {
        if (errorLogs++ < 10) {
            Bukkit.getLogger().log(Level.WARNING, "[AziSync] $PLUGIN_NAME could not $action", t)
        }
    }
}
