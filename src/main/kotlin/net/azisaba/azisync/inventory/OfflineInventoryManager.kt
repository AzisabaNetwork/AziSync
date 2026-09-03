package net.azisaba.azisync.inventory

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.util.ItemSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class OfflineInvType { INVENTORY, ENDERCHEST, ARMOR }

data class OfflineInvSession(
    val targetUUID: UUID,
    val targetName: String,
    val type: OfflineInvType,
    val isOnline: Boolean
)

class OfflineInventoryManager(private val plugin: AziSync) {

    /** GUI Inventory → session の対応表 */
    val sessions = ConcurrentHashMap<Inventory, OfflineInvSession>()

    /**
     * オフラインプレイヤーのUUIDを取得する。
     * 1. Bukkit のキャッシュ / 過去ログイン履歴
     * 2. DB（azisync_inventory テーブル）からの逆引き
     * 3. どちらも見つからない場合は Bukkit.getOfflinePlayer(name).uniqueId を返す
     */
    fun resolveOfflinePlayerUuid(name: String): UUID {
        @Suppress("DEPRECATION")
        val offlinePlayer = Bukkit.getOfflinePlayer(name)
        if (offlinePlayer.hasPlayedBefore()) {
            return offlinePlayer.uniqueId
        }

        val tableName = plugin.config.getString("database.TablesNames.inventoryTableName", "azisync_inventory") ?: "azisync_inventory"
        try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = "SELECT `player_uuid` FROM `$tableName` WHERE `player_name` = ? LIMIT 1"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, name)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            return UUID.fromString(rs.getString("player_uuid"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("Error fetching UUID for $name: ${e.message}")
        }
        return offlinePlayer.uniqueId
    }

    // ─────────────────────────────────────────────
    //  /azisync inv <player>
    // ─────────────────────────────────────────────

    fun openInventoryGui(editor: Player, targetName: String) {
        val onlineTarget = Bukkit.getPlayer(targetName)

        if (onlineTarget != null && onlineTarget.isOnline) {
            val items: Array<ItemStack?> = onlineTarget.inventory.contents.copyOf()
            openGui(editor, 36, "§b[inv] §f${onlineTarget.name}", items,
                OfflineInvSession(onlineTarget.uniqueId, onlineTarget.name, OfflineInvType.INVENTORY, true))
            plugin.messageManager.sendMessage(editor, "inv_opened", mapOf("{player}" to onlineTarget.name))
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                val uuid = resolveOfflinePlayerUuid(targetName)
                @Suppress("DEPRECATION")
                val offline = Bukkit.getOfflinePlayer(uuid)
                val resolvedName = offline.name ?: targetName

                try {
                    val data = plugin.databaseManager.inventoryHandler.getData(uuid, resolvedName)
                    val items: Array<ItemStack?> = decodeOrEmpty(data?.inventory, 36)

                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        openGui(editor, 36, "§b[inv] §f$resolvedName", items,
                            OfflineInvSession(uuid, resolvedName, OfflineInvType.INVENTORY, false))
                        plugin.messageManager.sendMessage(editor, "inv_opened", mapOf("{player}" to resolvedName))
                    })
                } catch (e: Exception) {
                    plugin.logger.severe("Failed to load inventory for $resolvedName: ${e.message}")
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        plugin.messageManager.sendMessage(editor, "inv_error")
                    })
                }
            })
        }
    }

    // ─────────────────────────────────────────────
    //  /azisync ec <player>
    // ─────────────────────────────────────────────

    fun openEnderChestGui(editor: Player, targetName: String) {
        val onlineTarget = Bukkit.getPlayer(targetName)

        if (onlineTarget != null && onlineTarget.isOnline) {
            val items: Array<ItemStack?> = onlineTarget.enderChest.contents.copyOf()
            openGui(editor, 27, "§b[ec] §f${onlineTarget.name}", items,
                OfflineInvSession(onlineTarget.uniqueId, onlineTarget.name, OfflineInvType.ENDERCHEST, true))
            plugin.messageManager.sendMessage(editor, "ec_opened", mapOf("{player}" to onlineTarget.name))
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                val uuid = resolveOfflinePlayerUuid(targetName)
                @Suppress("DEPRECATION")
                val offline = Bukkit.getOfflinePlayer(uuid)
                val resolvedName = offline.name ?: targetName

                try {
                    val data = plugin.databaseManager.enderchestHandler.getData(uuid, resolvedName)
                    val items: Array<ItemStack?> = decodeOrEmpty(data?.enderchest, 27)

                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        openGui(editor, 27, "§b[ec] §f$resolvedName", items,
                            OfflineInvSession(uuid, resolvedName, OfflineInvType.ENDERCHEST, false))
                        plugin.messageManager.sendMessage(editor, "ec_opened", mapOf("{player}" to resolvedName))
                    })
                } catch (e: Exception) {
                    plugin.logger.severe("Failed to load enderchest for $resolvedName: ${e.message}")
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        plugin.messageManager.sendMessage(editor, "inv_error")
                    })
                }
            })
        }
    }

    // ─────────────────────────────────────────────
    //  /azisync armor <player>
    //  GUI スロット配置: [0]=Helmet [1]=Chestplate [2]=Leggings [3]=Boots
    //  armorContents 配置: [0]=Boots [1]=Leggings [2]=Chestplate [3]=Helmet
    // ─────────────────────────────────────────────

    fun openArmorGui(editor: Player, targetName: String) {
        val onlineTarget = Bukkit.getPlayer(targetName)

        if (onlineTarget != null && onlineTarget.isOnline) {
            val armor = onlineTarget.inventory.armorContents // [0]=boots [1]=leggings [2]=chest [3]=helm
            val gui = createArmorGui(onlineTarget.name, armor)
            sessions[gui] = OfflineInvSession(onlineTarget.uniqueId, onlineTarget.name, OfflineInvType.ARMOR, true)
            editor.openInventory(gui)
            plugin.messageManager.sendMessage(editor, "armor_opened", mapOf("{player}" to onlineTarget.name))
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                val uuid = resolveOfflinePlayerUuid(targetName)
                @Suppress("DEPRECATION")
                val offline = Bukkit.getOfflinePlayer(uuid)
                val resolvedName = offline.name ?: targetName

                try {
                    val data = plugin.databaseManager.inventoryHandler.getData(uuid, resolvedName)
                    val armorItems: Array<ItemStack?> = decodeOrEmpty(data?.armor, 4)

                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        val gui = createArmorGui(resolvedName, armorItems)
                        sessions[gui] = OfflineInvSession(uuid, resolvedName, OfflineInvType.ARMOR, false)
                        editor.openInventory(gui)
                        plugin.messageManager.sendMessage(editor, "armor_opened", mapOf("{player}" to resolvedName))
                    })
                } catch (e: Exception) {
                    plugin.logger.severe("Failed to load armor for $resolvedName: ${e.message}")
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        plugin.messageManager.sendMessage(editor, "inv_error")
                    })
                }
            })
        }
    }

    // ─────────────────────────────────────────────
    //  内部ユーティリティ
    // ─────────────────────────────────────────────

    /**
     * GUIインベントリを作成してセッションに登録し、エディターへ開く。
     */
    private fun openGui(editor: Player, size: Int, title: String, items: Array<ItemStack?>, session: OfflineInvSession) {
        val gui = Bukkit.createInventory(null, size, title)
        val padded = arrayOfNulls<ItemStack>(size)
        for (i in 0 until minOf(items.size, size)) padded[i] = items[i]
        gui.contents = padded
        sessions[gui] = session
        editor.openInventory(gui)
    }

    /**
     * 防具欄GUI（9スロット）を生成する。
     * armorContents の並び [0]=Boots [1]=Leggings [2]=Chestplate [3]=Helmet を
     * GUI上では [0]=Helmet [1]=Chestplate [2]=Leggings [3]=Boots に変換する。
     */
    private fun createArmorGui(playerName: String, armor: Array<ItemStack?>): Inventory {
        val gui = Bukkit.createInventory(null, 9, "§b[armor] §f$playerName")
        gui.setItem(0, armor.getOrNull(3)) // Helmet
        gui.setItem(1, armor.getOrNull(2)) // Chestplate
        gui.setItem(2, armor.getOrNull(1)) // Leggings
        gui.setItem(3, armor.getOrNull(0)) // Boots
        return gui
    }

    /**
     * Base64文字列をデコードして指定サイズの配列にする。
     * データが "none" または null の場合は空配列を返す。
     */
    private fun decodeOrEmpty(base64: String?, expectedSize: Int): Array<ItemStack?> {
        if (base64.isNullOrBlank() || base64 == "none") return arrayOfNulls(expectedSize)
        val decoded = ItemSerializer.fromBase64(base64)
        val padded = arrayOfNulls<ItemStack>(expectedSize)
        for (i in 0 until minOf(decoded.size, expectedSize)) padded[i] = decoded[i]
        return padded
    }
}
