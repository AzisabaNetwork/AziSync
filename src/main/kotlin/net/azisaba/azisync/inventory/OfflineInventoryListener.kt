package net.azisaba.azisync.inventory

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.util.ItemSerializer
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack

class OfflineInventoryListener(private val plugin: AziSync) : Listener {

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val topInv = event.view.topInventory
        val session = plugin.offlineInventoryManager.sessions.remove(topInv) ?: return

        // GUI の中身をスナップショット（クローズ後に参照するため）
        val items: Array<ItemStack?> = topInv.contents.copyOf()

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                when (session.type) {
                    OfflineInvType.INVENTORY  -> saveInventory(session, items)
                    OfflineInvType.ENDERCHEST -> saveEnderChest(session, items)
                    OfflineInvType.ARMOR      -> saveArmor(session, items)
                }
            } catch (e: Exception) {
                plugin.logger.severe("Failed to save offline inventory for ${session.targetName}: ${e.message}")
                e.printStackTrace()
            }
        })
    }

    // ─────────────────────────────────────────────
    //  インベントリ (36スロット) の保存
    // ─────────────────────────────────────────────

    private fun saveInventory(session: OfflineInvSession, items: Array<ItemStack?>) {
        val invArray = arrayOfNulls<ItemStack>(36)
        for (i in 0 until minOf(items.size, 36)) invArray[i] = items[i]
        val invBase64 = ItemSerializer.toBase64(invArray)

        // オンラインプレイヤーならインベントリへ直接反映
        if (session.isOnline) {
            val target = Bukkit.getPlayer(session.targetUUID)
            if (target != null && target.isOnline) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    target.inventory.contents = invArray
                    target.updateInventory()
                })
            }
        }

        // 既存データから armor / hotbar_slot / gamemode を保持してDBへ書き込む
        val existing = plugin.databaseManager.inventoryHandler.getData(session.targetUUID, session.targetName)
        plugin.databaseManager.inventoryHandler.setData(
            session.targetUUID, session.targetName,
            invBase64,
            existing?.armor ?: "none",
            existing?.hotbarSlot ?: 0,
            existing?.gamemode ?: 0,
            "true"
        )
        plugin.logger.info("Saved inventory for ${session.targetName} (online=${session.isOnline})")
    }

    // ─────────────────────────────────────────────
    //  エンダーチェスト (27スロット) の保存
    // ─────────────────────────────────────────────

    private fun saveEnderChest(session: OfflineInvSession, items: Array<ItemStack?>) {
        val ecArray = arrayOfNulls<ItemStack>(27)
        for (i in 0 until minOf(items.size, 27)) ecArray[i] = items[i]
        val ecBase64 = ItemSerializer.toBase64(ecArray)

        if (session.isOnline) {
            val target = Bukkit.getPlayer(session.targetUUID)
            if (target != null && target.isOnline) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    target.enderChest.contents = ecArray
                })
            }
        }

        plugin.databaseManager.enderchestHandler.setData(
            session.targetUUID, session.targetName, ecBase64, "true"
        )
        plugin.logger.info("Saved enderchest for ${session.targetName} (online=${session.isOnline})")
    }

    // ─────────────────────────────────────────────
    //  防具欄 (9スロットGUI → 4スロットDB) の保存
    //  GUI配置: [0]=Helmet [1]=Chestplate [2]=Leggings [3]=Boots
    //  armorContents: [0]=Boots [1]=Leggings [2]=Chestplate [3]=Helmet
    // ─────────────────────────────────────────────

    private fun saveArmor(session: OfflineInvSession, items: Array<ItemStack?>) {
        val armorArray = arrayOfNulls<ItemStack>(4)
        armorArray[3] = items.getOrNull(0) // Helmet
        armorArray[2] = items.getOrNull(1) // Chestplate
        armorArray[1] = items.getOrNull(2) // Leggings
        armorArray[0] = items.getOrNull(3) // Boots
        val armorBase64 = ItemSerializer.toBase64(armorArray)

        if (session.isOnline) {
            val target = Bukkit.getPlayer(session.targetUUID)
            if (target != null && target.isOnline) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    target.inventory.setArmorContents(armorArray)
                })
            }
        }

        // 既存データから inventory / hotbar_slot / gamemode を保持してDBへ書き込む
        val existing = plugin.databaseManager.inventoryHandler.getData(session.targetUUID, session.targetName)
        plugin.databaseManager.inventoryHandler.setData(
            session.targetUUID, session.targetName,
            existing?.inventory ?: "none",
            armorBase64,
            existing?.hotbarSlot ?: 0,
            existing?.gamemode ?: 0,
            "true"
        )
        plugin.logger.info("Saved armor for ${session.targetName} (online=${session.isOnline})")
    }
}
