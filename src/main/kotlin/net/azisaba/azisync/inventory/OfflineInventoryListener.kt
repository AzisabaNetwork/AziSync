package net.azisaba.azisync.inventory

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.util.ItemSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

class OfflineInventoryListener(private val plugin: AziSync) : Listener {

    init {
        // オンラインプレイヤーのインベントリ変更を管理者のGUIへリアルタイム反映するタスク (4 ticks = 0.2秒間隔)
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            tickSyncPlayerToGui()
        }, 4L, 4L)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val topInv = event.view.topInventory
        val session = plugin.offlineInventoryManager.sessions[topInv] ?: return

        if (session.type == OfflineInvType.INVENTORY) {
            // GUI 上のセパレータスロットをクリック不可にする
            if (event.rawSlot in OfflineInventoryManager.SEPARATOR_SLOTS) {
                event.isCancelled = true
                return
            }
        }

        // オンラインプレイヤーのGUI操作なら、確定後 (1 tick後) に対象プレイヤーへ即時反映
        if (session.isOnline) {
            scheduleGuiToPlayerSync(topInv, session)
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val topInv = event.view.topInventory
        val session = plugin.offlineInventoryManager.sessions[topInv] ?: return

        if (session.type == OfflineInvType.INVENTORY) {
            for (slot in event.rawSlots) {
                if (slot in OfflineInventoryManager.SEPARATOR_SLOTS) {
                    event.isCancelled = true
                    return
                }
            }
        }

        // オンラインプレイヤーのGUI操作なら、確定後 (1 tick後) に対象プレイヤーへ即時反映
        if (session.isOnline) {
            scheduleGuiToPlayerSync(topInv, session)
        }
    }

    /**
     * 管理者がGUIを操作した内容を1 tick後に対象プレイヤーのインベントリへ反映する。
     */
    private fun scheduleGuiToPlayerSync(gui: Inventory, session: OfflineInvSession) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!plugin.offlineInventoryManager.sessions.containsKey(gui)) return@Runnable
            val target = Bukkit.getPlayer(session.targetUUID) ?: return@Runnable
            if (!target.isOnline) return@Runnable

            when (session.type) {
                OfflineInvType.INVENTORY -> {
                    val items = gui.contents
                    val storageArray = arrayOfNulls<ItemStack>(36)
                    for (i in 0 until 9) storageArray[i] = items.getOrNull(27 + i)
                    for (i in 0 until 27) storageArray[9 + i] = items.getOrNull(i)

                    val armorArray = arrayOfNulls<ItemStack>(4)
                    armorArray[3] = items.getOrNull(OfflineInventoryManager.HELMET_SLOT)
                    armorArray[2] = items.getOrNull(OfflineInventoryManager.CHESTPLATE_SLOT)
                    armorArray[1] = items.getOrNull(OfflineInventoryManager.LEGGINGS_SLOT)
                    armorArray[0] = items.getOrNull(OfflineInventoryManager.BOOTS_SLOT)

                    val offhandItem = items.getOrNull(OfflineInventoryManager.OFFHAND_SLOT)

                    target.inventory.storageContents = storageArray
                    target.inventory.setArmorContents(armorArray)
                    target.inventory.setItemInOffHand(offhandItem ?: ItemStack(Material.AIR))
                    target.updateInventory()
                }
                OfflineInvType.ENDERCHEST -> {
                    val items = gui.contents
                    val ecArray = arrayOfNulls<ItemStack>(27)
                    for (i in 0 until minOf(items.size, 27)) ecArray[i] = items[i]
                    target.enderChest.contents = ecArray
                }
                OfflineInvType.ARMOR -> {
                    val items = gui.contents
                    val armorArray = arrayOfNulls<ItemStack>(4)
                    armorArray[3] = items.getOrNull(0)
                    armorArray[2] = items.getOrNull(1)
                    armorArray[1] = items.getOrNull(2)
                    armorArray[0] = items.getOrNull(3)
                    target.inventory.setArmorContents(armorArray)
                }
            }
        })
    }

    /**
     * オンラインの対象プレイヤーのインベントリ変化を管理者の開いているGUIへ定期的に同期する。
     */
    private fun tickSyncPlayerToGui() {
        val sessions = plugin.offlineInventoryManager.sessions
        if (sessions.isEmpty()) return

        for ((gui, session) in sessions) {
            if (!session.isOnline) continue
            val target = Bukkit.getPlayer(session.targetUUID) ?: continue
            if (!target.isOnline) continue

            // 閲覧者のいずれかがマウスカーソルでアイテムを掴んでいる最中は、カーソルアイテム消失を防ぐため同期スキップ
            val anyHoldingCursor = gui.viewers.any { viewer ->
                val cursor = viewer.itemOnCursor
                cursor.type != Material.AIR && cursor.amount > 0
            }
            if (anyHoldingCursor) continue

            when (session.type) {
                OfflineInvType.INVENTORY -> {
                    syncPlayerInventoryToGui(target, gui)
                }
                OfflineInvType.ENDERCHEST -> {
                    syncPlayerEnderChestToGui(target, gui)
                }
                OfflineInvType.ARMOR -> {
                    syncPlayerArmorToGui(target, gui)
                }
            }
        }
    }

    private fun syncPlayerInventoryToGui(target: Player, gui: Inventory) {
        val storage = target.inventory.storageContents
        val armor = target.inventory.armorContents
        val offhand = target.inventory.itemInOffHand

        // 0..26: メインストレージ (スロット 9..35)
        for (i in 0 until 27) {
            val item = storage.getOrNull(9 + i)
            if (!isItemEqual(gui.getItem(i), item)) {
                gui.setItem(i, item?.clone())
            }
        }

        // 27..35: ホットバー (スロット 0..8)
        for (i in 0 until 9) {
            val item = storage.getOrNull(i)
            if (!isItemEqual(gui.getItem(27 + i), item)) {
                gui.setItem(27 + i, item?.clone())
            }
        }

        // 36..39: 防具
        val helm = armor.getOrNull(3)
        if (!isItemEqual(gui.getItem(OfflineInventoryManager.HELMET_SLOT), helm)) {
            gui.setItem(OfflineInventoryManager.HELMET_SLOT, helm?.clone())
        }
        val chest = armor.getOrNull(2)
        if (!isItemEqual(gui.getItem(OfflineInventoryManager.CHESTPLATE_SLOT), chest)) {
            gui.setItem(OfflineInventoryManager.CHESTPLATE_SLOT, chest?.clone())
        }
        val legs = armor.getOrNull(1)
        if (!isItemEqual(gui.getItem(OfflineInventoryManager.LEGGINGS_SLOT), legs)) {
            gui.setItem(OfflineInventoryManager.LEGGINGS_SLOT, legs?.clone())
        }
        val boots = armor.getOrNull(0)
        if (!isItemEqual(gui.getItem(OfflineInventoryManager.BOOTS_SLOT), boots)) {
            gui.setItem(OfflineInventoryManager.BOOTS_SLOT, boots?.clone())
        }

        // 41: オフハンド
        if (!isItemEqual(gui.getItem(OfflineInventoryManager.OFFHAND_SLOT), offhand)) {
            gui.setItem(OfflineInventoryManager.OFFHAND_SLOT, offhand?.clone())
        }
    }

    private fun syncPlayerEnderChestToGui(target: Player, gui: Inventory) {
        val contents = target.enderChest.contents
        for (i in 0 until minOf(contents.size, gui.size)) {
            val item = contents[i]
            if (!isItemEqual(gui.getItem(i), item)) {
                gui.setItem(i, item?.clone())
            }
        }
    }

    private fun syncPlayerArmorToGui(target: Player, gui: Inventory) {
        val armor = target.inventory.armorContents
        val expected = arrayOf(
            armor.getOrNull(3), // [0] Helmet
            armor.getOrNull(2), // [1] Chestplate
            armor.getOrNull(1), // [2] Leggings
            armor.getOrNull(0)  // [3] Boots
        )
        for (i in 0 until 4) {
            val item = expected[i]
            if (!isItemEqual(gui.getItem(i), item)) {
                gui.setItem(i, item?.clone())
            }
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val topInv = event.view.topInventory
        val session = plugin.offlineInventoryManager.sessions.remove(topInv) ?: return

        // GUI の中身をスナップショット
        val items: Array<ItemStack?> = topInv.contents.copyOf()

        // 変更検知（Dirty check）: 一切変更がなければ保存・同期をスキップ
        if (!hasChanged(session.initialContents, items, session.type)) {
            plugin.logger.fine("No changes detected in ${session.targetName}'s inventory, skipping save.")
            return
        }

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

    private fun hasChanged(initial: Array<ItemStack?>, current: Array<ItemStack?>, type: OfflineInvType): Boolean {
        val size = minOf(initial.size, current.size)
        for (i in 0 until size) {
            if (type == OfflineInvType.INVENTORY && i in OfflineInventoryManager.SEPARATOR_SLOTS) {
                continue
            }
            val a = initial[i]
            val b = current[i]
            if (!isItemEqual(a, b)) {
                return true
            }
        }
        return false
    }

    private fun isItemEqual(a: ItemStack?, b: ItemStack?): Boolean {
        val aEmpty = a == null || a.type == Material.AIR || a.amount <= 0
        val bEmpty = b == null || b.type == Material.AIR || b.amount <= 0
        if (aEmpty && bEmpty) return true
        if (aEmpty != bEmpty) return false
        return a!!.isSimilar(b!!) && a.amount == b.amount
    }

    // ─────────────────────────────────────────────
    //  インベントリ (45スロットGUI) の保存
    // ─────────────────────────────────────────────

    private fun saveInventory(session: OfflineInvSession, items: Array<ItemStack?>) {
        // メインストレージ (36スロット: 0..8 ホットバー, 9..35 インベントリ)
        val storageArray = arrayOfNulls<ItemStack>(36)
        // GUI 27..35 -> ホットバー (0..8)
        for (i in 0 until 9) {
            storageArray[i] = items.getOrNull(27 + i)
        }
        // GUI 0..26 -> メインインベントリ (9..35)
        for (i in 0 until 27) {
            storageArray[9 + i] = items.getOrNull(i)
        }

        // 防具 (4スロット: [0]=Boots, [1]=Leggings, [2]=Chestplate, [3]=Helmet)
        val armorArray = arrayOfNulls<ItemStack>(4)
        armorArray[3] = items.getOrNull(OfflineInventoryManager.HELMET_SLOT)     // 36
        armorArray[2] = items.getOrNull(OfflineInventoryManager.CHESTPLATE_SLOT) // 37
        armorArray[1] = items.getOrNull(OfflineInventoryManager.LEGGINGS_SLOT)   // 38
        armorArray[0] = items.getOrNull(OfflineInventoryManager.BOOTS_SLOT)      // 39

        // オフハンド (1スロット)
        val offhandItem = items.getOrNull(OfflineInventoryManager.OFFHAND_SLOT)  // 41

        // オンラインプレイヤーならインベントリへ個別に安全反映
        if (session.isOnline) {
            val target = Bukkit.getPlayer(session.targetUUID)
            if (target != null && target.isOnline) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    target.inventory.storageContents = storageArray
                    target.inventory.setArmorContents(armorArray)
                    target.inventory.setItemInOffHand(offhandItem ?: ItemStack(Material.AIR))
                    target.updateInventory()
                })
            }
        }

        // DB 保存用 fullContents (41要素: 0..35 storage, 36..39 armor, 40 offhand)
        val fullContents = arrayOfNulls<ItemStack>(41)
        for (i in 0 until 36) {
            fullContents[i] = storageArray[i]
        }
        for (i in 0 until 4) {
            fullContents[36 + i] = armorArray[i]
        }
        fullContents[40] = offhandItem

        val invBase64 = ItemSerializer.toBase64(fullContents)
        val armorBase64 = ItemSerializer.toBase64(armorArray)

        // 既存データから hotbar_slot / gamemode を保持してDBへ書き込む
        val existing = plugin.databaseManager.inventoryHandler.getData(session.targetUUID, session.targetName)
        plugin.databaseManager.inventoryHandler.setData(
            session.targetUUID, session.targetName,
            invBase64,
            armorBase64,
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

