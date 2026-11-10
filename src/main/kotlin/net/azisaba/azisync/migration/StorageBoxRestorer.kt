package net.azisaba.azisync.migration

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.util.ItemSerializer
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Restores the Storage Boxes of players from their MPDB (1.15.2) data, without touching anything else.
 *
 * AziSync 1.0.8 and older rebuilt every 1.15.2 item with NBT by hand during the MPDB migration, so Storage Boxes
 * lost their plugin data (content type, amount, the content item's NBT and their ID) and kept only their name and
 * lore. StorageBox can recover some of them from the lore, but never the content item's NBT (Mythic items and other
 * named contents become plain items, or the box cannot be recovered at all).
 *
 * Each broken box is matched with its MPDB copy through the ID in its lore (`ID: <uuid>`), and replaced by the MPDB
 * box converted with DataConverter. The amount and auto collect/buy settings of the current box are kept, so the
 * player keeps whatever they collected or extracted since the migration. Boxes that are not broken, and every other
 * item, are left untouched; boxes that no longer exist are never recreated.
 */
class StorageBoxRestorer(private val plugin: AziSync) {

    private class SourceBox(
        val uuid: String,
        val item: ItemStack,
        val type: String?,
        val amount: Long?,
        val hasContent: Boolean,
        val signature: String,
        val where: String
    )

    private class CurrentBox(
        val uuid: String,
        /** storagebox data (type) is present in custom_data or its PDC */
        val hasBoxData: Boolean,
        val type: String?,
        val amount: Long?,
        val autoCollect: Boolean?,
        val autoBuy: Boolean?,
        /** the 1.15.2 content tag (storageBoxTag) is present */
        val hasContent: Boolean,
        /** the box's current content item has its own name or lore (set again by the player) */
        val contentCustomized: Boolean
    )

    private class Report(val apply: Boolean) {
        var players = 0
        var playersWithBoxes = 0
        var sourceBoxes = 0
        var sourceWithoutId = 0
        var restoredBroken = 0
        var restoredContent = 0
        var healthy = 0
        var overwrittenDuplicates = 0
        var duplicateMismatch = 0
        var unmatchedSource = 0
        var brokenWithoutSource = 0
        var ambiguous = 0
        var failed = 0
        var skippedOnline = 0
        var skippedRaw = 0
        var conflicts = 0
        val lines = ArrayList<String>()

        @Synchronized
        fun line(text: String) {
            lines.add(text)
        }
    }

    fun run(sender: CommandSender, target: String, apply: Boolean) {
        if (!ItemSerializer.canEditItemNbt()) {
            sender.sendMessage("§6[AziSync] §cこのサーバーではアイテムのNBTを読み書きできません (Paper 1.16+ が必要です)。")
            return
        }
        if (target.equals("hand", ignoreCase = true) && sender !is Player) {
            sender.sendMessage("§6[AziSync] §chand はプレイヤーのみ実行できます。")
            return
        }
        val migrator = plugin.mpdbMigrator
        if (!migrator.isMigrating.compareAndSet(false, true)) {
            plugin.messageManager.sendMessage(sender, "migrate_already_running")
            return
        }
        val report = Report(apply)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                val sourceInfo = migrator.resolveSourceInfo()
                sender.sendMessage(
                    "§6[AziSync] §eStorageBox を MPDB から${if (apply) "復元" else "確認（書き込みなし）"}します: §f$target §7(${sourceInfo.description})"
                )
                migrator.getSourceConnection(sourceInfo).use { conn ->
                    val invTable = migrator.findSourceTable(conn, "inventory", sourceInfo)
                    val ecTable = migrator.findSourceTable(conn, "enderchest", sourceInfo)
                    if (invTable == null && ecTable == null) {
                        sender.sendMessage("§6[AziSync] §cMPDB の inventory / enderchest テーブルが見つかりません。")
                        return@Runnable
                    }
                    when {
                        target.equals("all", ignoreCase = true) -> {
                            val players = listPlayers(conn, invTable, ecTable)
                            sender.sendMessage("§7[AziSync] MPDB のプレイヤー: ${players.size}人")
                            for ((uuid, name) in players) {
                                processPlayer(conn, invTable, ecTable, uuid, name, report)
                                if (report.players % 500 == 0) {
                                    sender.sendMessage("§7[AziSync] ${report.players}/${players.size}人を処理しました")
                                }
                            }
                        }
                        target.equals("hand", ignoreCase = true) -> repairHand(conn, invTable, ecTable, sender as Player, report)
                        else -> {
                            val online = Bukkit.getPlayerExact(target)
                            val uuid = online?.uniqueId ?: plugin.offlineInventoryManager.resolveOfflinePlayerUuid(target)
                            processPlayer(conn, invTable, ecTable, uuid, online?.name ?: target, report)
                        }
                    }
                }
                sendReport(sender, report)
            } catch (e: Exception) {
                plugin.logger.severe("Storage Box restore failed: ${e.message}")
                e.printStackTrace()
                plugin.messageManager.sendMessage(sender, "migrate_failed", mapOf("{error}" to (e.message ?: e.toString())))
            } finally {
                migrator.isMigrating.set(false)
            }
        })
    }

    // ---------------------------------------------------------------- players

    private fun processPlayer(conn: Connection, invTable: String?, ecTable: String?, uuid: UUID, name: String, report: Report) {
        report.players++
        val sources = loadSourceBoxes(conn, invTable, ecTable, uuid, name, report)
        if (sources.isEmpty()) return
        report.playersWithBoxes++
        val label = "$name ($uuid)"

        val online = Bukkit.getPlayer(uuid)
        if (online != null && online.isOnline) {
            val seen = Bukkit.getScheduler().callSyncMethod(plugin) { patchOnline(online, sources, report, label) }
                .get(30, TimeUnit.SECONDS)
            reportUnmatched(sources, seen, report, label)
            return
        }
        if (isBusyElsewhere(uuid)) {
            report.skippedOnline++
            report.line("$label: 他のサーバーでオンライン（または保存中）のためスキップ。ログアウト後に再実行してください")
            return
        }
        val seen = HashSet<String>()
        val lockTimeout = plugin.config.getInt("general.syncWait.lockTimeoutSeconds", 10).coerceAtLeast(1)
        try {
            plugin.databaseManager.acquireSyncLock(uuid, lockTimeout).use {
                if (Bukkit.getPlayer(uuid) != null || isBusyElsewhere(uuid)) {
                    report.skippedOnline++
                    report.line("$label: 処理中にログインしたためスキップ")
                    return
                }
                val duplicates = duplicateIds(*readOfflineItems(uuid))
                patchOfflineInventory(uuid, sources, report, label, seen, duplicates)
                patchOfflineEnderChest(uuid, sources, report, label, seen, duplicates)
            }
        } catch (e: Exception) {
            report.failed++
            report.line("$label: ${e.message}")
            return
        }
        reportUnmatched(sources, seen, report, label)
    }

    private fun isBusyElsewhere(uuid: UUID): Boolean {
        val state = plugin.databaseManager.getSyncState(uuid) ?: return false
        val staleMillis = plugin.config.getLong("general.syncWait.staleMillis", 30000L)
        return (state.status == "active" || state.status == "saving") && state.ageMillis < staleMillis
    }

    private fun reportUnmatched(sources: Map<String, List<SourceBox>>, seen: Set<String>, report: Report, label: String) {
        for ((id, boxes) in sources) {
            if (id in seen) continue
            report.unmatchedSource += boxes.size
            report.line("$label: MPDBのStorageBox $id (${boxes.first().type}, ${boxes.first().where}) は現在のインベントリ/エンダーチェストにありません（チェスト等にある場合は hand で修復できます）")
        }
    }

    private fun patchOnline(player: Player, sources: Map<String, List<SourceBox>>, report: Report, label: String): Set<String> {
        val seen = HashSet<String>()
        var changed = false
        val inventory = player.inventory
        val contents = inventory.contents
        val ender = player.enderChest
        val enderContents = ender.contents
        val duplicates = duplicateIds(contents, enderContents)
        for (slot in patchItems(contents, sources, report, label, "inventory", seen, duplicates)) {
            inventory.setItem(slot, contents[slot])
            changed = true
        }
        for (slot in patchItems(enderContents, sources, report, label, "enderchest", seen, duplicates)) {
            ender.setItem(slot, enderContents[slot])
            changed = true
        }
        if (changed) {
            player.updateInventory()
            plugin.syncManager.saveData(player)
        }
        return seen
    }

    private fun patchOfflineInventory(uuid: UUID, sources: Map<String, List<SourceBox>>, report: Report, label: String, seen: MutableSet<String>, duplicates: Set<String>) {
        val table = plugin.config.getString("database.TablesNames.inventoryTableName", "azisync_inventory") ?: "azisync_inventory"
        val row = plugin.databaseManager.getConnection().use { conn ->
            conn.prepareStatement("SELECT `inventory`, `armor` FROM `$table` WHERE `player_uuid` = ? LIMIT 1").use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("inventory") to rs.getString("armor") else null }
            }
        } ?: return
        val (rawInventory, rawArmor) = row
        val inventory = patchColumn(rawInventory, 36, sources, report, label, "inventory", seen, duplicates)
        val armor = patchColumn(rawArmor, 4, sources, report, label, "armor", seen, duplicates)
        if (!report.apply || (inventory == null && armor == null)) return
        val updated = plugin.databaseManager.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE `$table` SET `inventory` = ?, `armor` = ? WHERE `player_uuid` = ? AND `inventory` = ? AND `armor` = ?"
            ).use { stmt ->
                stmt.setString(1, inventory ?: rawInventory)
                stmt.setString(2, armor ?: rawArmor)
                stmt.setString(3, uuid.toString())
                stmt.setString(4, rawInventory)
                stmt.setString(5, rawArmor)
                stmt.executeUpdate()
            }
        }
        if (updated == 0) {
            report.conflicts++
            report.line("$label: inventory が処理中に更新されたため書き込みませんでした。再実行してください")
        }
    }

    private fun patchOfflineEnderChest(uuid: UUID, sources: Map<String, List<SourceBox>>, report: Report, label: String, seen: MutableSet<String>, duplicates: Set<String>) {
        val table = plugin.config.getString("database.TablesNames.enderChestTableName", "azisync_enderchest") ?: "azisync_enderchest"
        val raw = plugin.databaseManager.getConnection().use { conn ->
            conn.prepareStatement("SELECT `enderchest` FROM `$table` WHERE `player_uuid` = ? LIMIT 1").use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("enderchest") else null }
            }
        } ?: return
        val patched = patchColumn(raw, 27, sources, report, label, "enderchest", seen, duplicates) ?: return
        if (!report.apply) return
        val updated = plugin.databaseManager.getConnection().use { conn ->
            conn.prepareStatement("UPDATE `$table` SET `enderchest` = ? WHERE `player_uuid` = ? AND `enderchest` = ?").use { stmt ->
                stmt.setString(1, patched)
                stmt.setString(2, uuid.toString())
                stmt.setString(3, raw)
                stmt.executeUpdate()
            }
        }
        if (updated == 0) {
            report.conflicts++
            report.line("$label: enderchest が処理中に更新されたため書き込みませんでした。再実行してください")
        }
    }

    /** IDs of the Storage Boxes that appear more than once across the given containers. */
    private fun duplicateIds(vararg containers: Array<ItemStack?>?): Set<String> {
        val counts = HashMap<String, Int>()
        for (container in containers) {
            container?.forEach { item ->
                val id = item?.let { inspect(it) }?.uuid ?: return@forEach
                counts[id] = (counts[id] ?: 0) + 1
            }
        }
        return counts.filterValues { it > 1 }.keys
    }

    /** The player's stored inventory, armor and ender chest (for counting duplicates only). */
    private fun readOfflineItems(uuid: UUID): Array<Array<ItemStack?>?> {
        val invTable = plugin.config.getString("database.TablesNames.inventoryTableName", "azisync_inventory") ?: "azisync_inventory"
        val ecTable = plugin.config.getString("database.TablesNames.enderChestTableName", "azisync_enderchest") ?: "azisync_enderchest"
        fun decode(raw: String?, size: Int): Array<ItemStack?>? =
            if (raw.isNullOrBlank() || raw == "none") null else try { ItemSerializer.fromBase64(raw, size) } catch (_: Exception) { null }
        return plugin.databaseManager.getConnection().use { conn ->
            val inv = conn.prepareStatement("SELECT `inventory`, `armor` FROM `$invTable` WHERE `player_uuid` = ? LIMIT 1").use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("inventory") to rs.getString("armor") else null }
            }
            val ec = conn.prepareStatement("SELECT `enderchest` FROM `$ecTable` WHERE `player_uuid` = ? LIMIT 1").use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("enderchest") else null }
            }
            arrayOf(decode(inv?.first, 36), decode(inv?.second, 4), decode(ec, 27))
        }
    }

    /** Returns the re-encoded column when a box was restored, null when nothing changed. */
    private fun patchColumn(
        raw: String?, size: Int, sources: Map<String, List<SourceBox>>, report: Report, label: String,
        column: String, seen: MutableSet<String>, duplicates: Set<String>
    ): String? {
        if (raw.isNullOrBlank() || raw == "none") return null
        if (ItemSerializer.rawItemCompounds(raw) != null) {
            // Still the MPDB data the migration kept unconverted; it is converted on join (with the plugin
            // data preserved). Re-encoding it here would convert every other item of the column too.
            val items = try { ItemSerializer.fromBase64(raw, size) } catch (_: Exception) { return null }
            if (items.any { it != null && inspect(it)?.hasBoxData == false }) {
                report.skippedRaw++
                report.line("$label $column: 未変換のMPDBデータのため書き換えません（参加時に変換されます）")
            }
            items.forEach { item -> item?.let { inspect(it) }?.let { seen.add(it.uuid) } }
            return null
        }
        val items = try {
            ItemSerializer.fromBase64(raw, size, strict = true)
        } catch (e: Exception) {
            report.failed++
            report.line("$label $column: 現在のデータを読めないためスキップ (${e.message})")
            return null
        }
        val changed = patchItems(items, sources, report, label, column, seen, duplicates)
        if (changed.isEmpty() || !report.apply) return null
        return ItemSerializer.toBase64(items)
    }

    /**
     * Replaces the broken boxes of `items` in place (only when applying) and returns the changed slots.
     * Boxes whose ID is in `duplicates` (found more than once in the player's data) are overwritten with the MPDB
     * box even when they look healthy, as long as they hold the same content type (each keeps its own amount).
     * Every box ID found is added to `seen`, broken or not.
     */
    private fun patchItems(
        items: Array<ItemStack?>, sources: Map<String, List<SourceBox>>, report: Report, label: String,
        container: String, seen: MutableSet<String>, duplicates: Set<String> = emptySet()
    ): List<Int> {
        val changed = ArrayList<Int>()
        for (slot in items.indices) {
            val item = items[slot] ?: continue
            val current = inspect(item) ?: continue
            seen.add(current.uuid)
            val candidates = sources[current.uuid]
            if (candidates == null) {
                if (!current.hasBoxData) {
                    report.brokenWithoutSource++
                    report.line("$label $container[$slot]: 壊れたStorageBox ${current.uuid} はMPDBに見つかりません")
                }
                continue
            }
            val reason = when {
                !current.hasBoxData -> "NBT消失"
                !current.hasContent && candidates.first().hasContent && !current.contentCustomized &&
                    sameType(current.type, candidates.first().type) -> "中身のNBT消失"
                current.uuid in duplicates && sameType(current.type, candidates.first().type) -> DUPLICATE
                else -> null
            }
            if (reason == null) {
                if (current.uuid in duplicates) {
                    report.duplicateMismatch++
                    report.line("$label $container[$slot]: 重複ID ${current.uuid} ですが種類が MPDB (${candidates.first().type}) と違う (${current.type}) ため上書きしません")
                } else {
                    report.healthy++
                }
                continue
            }
            if (candidates.map { it.signature }.distinct().size > 1 ||
                (current.amount == null && candidates.map { it.amount }.distinct().size > 1)
            ) {
                report.ambiguous++
                report.line("$label $container[$slot]: MPDBに同じID ${current.uuid} で中身の違うStorageBoxが複数あるためスキップ")
                continue
            }
            val source = candidates.first()
            val restored = buildRestored(source, current, item)
            if (restored == null) {
                report.failed++
                report.line("$label $container[$slot]: ${current.uuid} の復元アイテムを作れませんでした")
                continue
            }
            when (reason) {
                "NBT消失" -> report.restoredBroken++
                DUPLICATE -> report.overwrittenDuplicates++
                else -> report.restoredContent++
            }
            val amount = current.amount ?: source.amount ?: 0L
            report.line("$label $container[$slot]: ${current.uuid} を復元 [$reason] 種類=${source.type} 数=$amount (MPDB: ${source.amount})")
            if (report.apply) {
                items[slot] = restored
                changed.add(slot)
            }
        }
        return changed
    }

    private fun buildRestored(source: SourceBox, current: CurrentBox, currentItem: ItemStack): ItemStack? {
        val nbt = ItemSerializer.itemToNbt(source.item) ?: return null
        val data = ItemSerializer.customData(nbt) ?: return null
        if (data["storageBoxType"] == null) return null
        val amount = current.amount ?: source.amount ?: 0L
        data["storageBoxAmount"] = amount
        current.autoCollect?.let { data["storageBoxAutoCollect"] = (if (it) 1 else 0).toByte() }
        current.autoBuy?.let { data["storageBoxAutoBuy"] = (if (it) 1 else 0).toByte() }
        data["randomUUID"] = current.uuid
        if (nbt.containsKey("count")) nbt["count"] = currentItem.amount
        val item = ItemSerializer.itemFromNbt(nbt) ?: return null
        // StorageBox reads the amount from "<n>" in the name when storageBoxAmount is 0, so the name must not
        // keep the MPDB amount. StorageBox rewrites name and lore the next time it touches the box.
        val meta = item.itemMeta
        if (meta != null) {
            if (meta.hasDisplayName()) {
                meta.setDisplayName(meta.displayName.replace(AMOUNT_IN_NAME, "<$amount>"))
            }
            if (meta.hasLore()) {
                meta.lore = meta.lore?.map { line ->
                    if (ChatColor.stripColor(line)?.trim()?.startsWith("数:") == true) "${ChatColor.GRAY}数: $amount" else line
                }
            }
            item.itemMeta = meta
        }
        return item
    }

    // ---------------------------------------------------------------- hand

    private fun repairHand(conn: Connection, invTable: String?, ecTable: String?, player: Player, report: Report) {
        val held = Bukkit.getScheduler().callSyncMethod(plugin) { player.inventory.itemInMainHand.clone() }.get(30, TimeUnit.SECONDS)
        val current = inspect(held)
        if (current == null) {
            player.sendMessage("§6[AziSync] §cメインハンドに StorageBox (IDのあるもの) を持ってください。")
            return
        }
        player.sendMessage("§7[AziSync] MPDB全体から ${current.uuid} を探しています...")
        val sources = HashMap<String, MutableList<SourceBox>>()
        for ((table, columns) in listOf(invTable to listOf("inventory" to 36, "armor" to 4), ecTable to listOf("enderchest" to 27))) {
            if (table == null) continue
            val select = "SELECT player_uuid, player_name, ${columns.joinToString { "`${it.first}`" }} FROM `$table`"
            conn.prepareStatement(select).use { stmt ->
                stmt.fetchSize = 200
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val owner = "${rs.getString("player_name")} (${rs.getString("player_uuid")})"
                        for ((column, size) in columns) {
                            collectBoxes(rs.getString(column), size, "$owner $column", report, current.uuid, sources)
                        }
                    }
                }
            }
        }
        if (sources.isEmpty()) {
            player.sendMessage("§6[AziSync] §cMPDBに ${current.uuid} のStorageBoxは見つかりませんでした。")
            return
        }
        report.playersWithBoxes++
        Bukkit.getScheduler().callSyncMethod(plugin) {
            val items = arrayOf<ItemStack?>(player.inventory.itemInMainHand)
            val changed = patchItems(items, sources, report, player.name, "hand", HashSet())
            if (changed.isNotEmpty()) {
                player.inventory.setItemInMainHand(items[0])
                player.updateInventory()
            }
        }.get(30, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- MPDB side

    private fun listPlayers(conn: Connection, invTable: String?, ecTable: String?): List<Pair<UUID, String>> {
        val players = LinkedHashMap<UUID, String>()
        for (table in listOfNotNull(invTable, ecTable)) {
            conn.prepareStatement("SELECT player_uuid, player_name FROM `$table`").use { stmt ->
                stmt.fetchSize = 500
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val uuid = MPDBMigrator.normalizeUuid(rs.getString("player_uuid"))?.let {
                            try { UUID.fromString(it) } catch (_: IllegalArgumentException) { null }
                        } ?: continue
                        players.putIfAbsent(uuid, rs.getString("player_name") ?: "?")
                    }
                }
            }
        }
        return players.map { it.key to it.value }
    }

    private fun loadSourceBoxes(
        conn: Connection, invTable: String?, ecTable: String?, uuid: UUID, name: String, report: Report
    ): Map<String, List<SourceBox>> {
        val sources = HashMap<String, MutableList<SourceBox>>()
        val ids = listOf(uuid.toString(), uuid.toString().replace("-", ""))
        fun query(table: String, columns: List<Pair<String, Int>>) {
            val select = "SELECT ${columns.joinToString { "`${it.first}`" }} FROM `$table` WHERE player_uuid IN (?, ?)"
            conn.prepareStatement(select).use { stmt ->
                stmt.setString(1, ids[0])
                stmt.setString(2, ids[1])
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        for ((column, size) in columns) collectBoxes(rs.getString(column), size, column, report, null, sources)
                    }
                }
            }
        }
        invTable?.let { query(it, listOf("inventory" to 36, "armor" to 4)) }
        ecTable?.let { query(it, listOf("enderchest" to 27)) }
        return sources
    }

    /** Adds the Storage Boxes of one MPDB column (all of them, or only the one with `onlyId`). */
    private fun collectBoxes(
        raw: String?, size: Int, where: String, report: Report, onlyId: String?,
        out: MutableMap<String, MutableList<SourceBox>>
    ) {
        if (raw.isNullOrBlank() || raw == "none") return
        val compounds = ItemSerializer.rawItemCompounds(raw)
        if (compounds != null) {
            for (compound in compounds) {
                val tag = compound["tag"] as? Map<*, *> ?: continue
                if (tag["storageBoxType"] == null) continue
                val id = (tag["randomUUID"] as? String)?.trim()?.lowercase()
                if (id == null) {
                    if (onlyId == null) report.sourceWithoutId++
                    continue
                }
                if (onlyId != null && id != onlyId) continue
                val item = ItemSerializer.convertRawItem(compound, ItemSerializer.DecodeStats())
                if (item == null) {
                    report.failed++
                    report.line("$where: MPDBのStorageBox $id を変換できませんでした")
                    continue
                }
                addSource(out, id, item, tag, where, report)
            }
            return
        }
        // BukkitObjectOutputStream data: the items are already converted by Bukkit.
        val items = try { ItemSerializer.fromBase64(raw, size) } catch (_: Exception) { return }
        for (item in items) {
            if (item == null) continue
            val data = ItemSerializer.itemToNbt(item)?.let { ItemSerializer.customData(it) } ?: continue
            if (data["storageBoxType"] == null) continue
            val id = (data["randomUUID"] as? String)?.trim()?.lowercase()
            if (id == null) {
                if (onlyId == null) report.sourceWithoutId++
                continue
            }
            if (onlyId != null && id != onlyId) continue
            addSource(out, id, item, data, where, report)
        }
    }

    private fun addSource(
        out: MutableMap<String, MutableList<SourceBox>>, id: String, item: ItemStack, tag: Map<*, *>,
        where: String, report: Report
    ) {
        val content = tag["storageBoxTag"] as? Map<*, *>
        val type = tag["storageBoxType"]?.toString()
        report.sourceBoxes++
        out.getOrPut(id) { ArrayList() }.add(
            SourceBox(
                uuid = id,
                item = item,
                type = type,
                amount = (tag["storageBoxAmount"] as? Number)?.toLong(),
                hasContent = content != null && content.isNotEmpty(),
                signature = normalizeType(type) + "|" + stableString(content),
                where = where
            )
        )
    }

    // ---------------------------------------------------------------- current items

    private fun inspect(item: ItemStack): CurrentBox? {
        if (item.type.name.endsWith("AIR")) return null
        val meta = item.itemMeta ?: return null
        if (!meta.hasDisplayName()) return null
        val name = ChatColor.stripColor(meta.displayName) ?: return null
        if (!name.startsWith("Storage Box")) return null
        val lore = if (meta.hasLore()) meta.lore.orEmpty().mapNotNull { ChatColor.stripColor(it)?.trim() } else emptyList()

        val data = ItemSerializer.itemToNbt(item)?.let { ItemSerializer.customData(it) }
        val values = data?.get("PublicBukkitValues") as? Map<*, *>

        val uuid = ((data?.get("randomUUID") ?: values?.get("storagebox:uuid")) as? String)
            ?: loreValue(lore, "ID:")
        val id = uuid?.trim()?.lowercase()?.takeIf { isUuid(it) } ?: return null

        val type = (data?.get("storageBoxType") ?: values?.get("storagebox:type")) as? String
        val amount = ((values?.get("storagebox:amount") ?: data?.get("storageBoxAmount")) as? Number)?.toLong()
            ?: loreValue(lore, "数:")?.toLongOrNull()
            ?: AMOUNT_IN_NAME.find(name)?.value?.trim('<', '>')?.toLongOrNull()
        val autoCollect = ((values?.get("storagebox:autocollect") ?: data?.get("storageBoxAutoCollect")) as? Number)
            ?.let { it.toInt() != 0 } ?: loreValue(lore, "自動回収:")?.toBooleanStrictOrNull()
        val autoBuy = ((values?.get("storagebox:autobuy") ?: data?.get("storageBoxAutoBuy")) as? Number)
            ?.let { it.toInt() != 0 } ?: loreValue(lore, "自動購入:")?.toBooleanStrictOrNull()
        val content = data?.get("storageBoxTag") as? Map<*, *>
        return CurrentBox(
            uuid = id,
            hasBoxData = type != null,
            type = type,
            amount = amount,
            autoCollect = autoCollect,
            autoBuy = autoBuy,
            hasContent = content != null && content.isNotEmpty(),
            contentCustomized = isCustomizedContent(values?.get("storagebox:component_data") as? String)
        )
    }

    private fun isCustomizedContent(serialized: String?): Boolean {
        if (serialized.isNullOrBlank()) return false
        return try {
            val yaml = YamlConfiguration()
            yaml.loadFromString(serialized)
            val meta = yaml.getItemStack("item")?.itemMeta ?: return false
            meta.hasDisplayName() || meta.hasLore()
        } catch (_: Exception) {
            true // unknown: do not replace it
        }
    }

    private fun loreValue(lore: List<String>, prefix: String): String? =
        lore.firstOrNull { it.startsWith(prefix) }?.substring(prefix.length)?.trim()

    private fun String.toBooleanStrictOrNull(): Boolean? = when (lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun isUuid(s: String): Boolean = try {
        UUID.fromString(s); s.length == 36
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun sameType(a: String?, b: String?): Boolean = a != null && b != null && normalizeType(a) == normalizeType(b)

    private fun normalizeType(raw: String?): String {
        if (raw == null) return ""
        val upper = raw.trim().uppercase().removePrefix("MINECRAFT:")
        return RENAMED_MATERIALS[upper] ?: upper
    }

    private fun stableString(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries.sortedBy { it.key.toString() }
            .joinToString(",", "{", "}") { "${it.key}=${stableString(it.value)}" }
        is List<*> -> value.joinToString(",", "[", "]") { stableString(it) }
        is ByteArray -> value.contentToString()
        is IntArray -> value.contentToString()
        is LongArray -> value.contentToString()
        else -> "${value.javaClass.simpleName}:$value"
    }

    // ---------------------------------------------------------------- report

    private fun sendReport(sender: CommandSender, report: Report) {
        val title = if (report.apply) "StorageBox 復元結果" else "StorageBox 確認結果（変更なし）"
        sender.sendMessage("§6[AziSync] §a=== $title ===")
        sender.sendMessage("§7・プレイヤー: ${report.players}人 / MPDBにStorageBoxがあるプレイヤー: ${report.playersWithBoxes}人 / MPDBのStorageBox: ${report.sourceBoxes}個 (ID無し ${report.sourceWithoutId}個)")
        val verb = if (report.apply) "復元した" else "復元できる"
        sender.sendMessage("§7・${verb}StorageBox: §aNBT消失 ${report.restoredBroken}個§7 / §a中身のNBT消失 ${report.restoredContent}個§7 / §a重複ID上書き ${report.overwrittenDuplicates}個§7 / 正常 ${report.healthy}個")
        if (report.duplicateMismatch > 0) sender.sendMessage("§7・重複IDだが種類が違うため上書きしなかった: §c${report.duplicateMismatch}個")
        sender.sendMessage("§7・MPDBにあるが現在見つからない: ${report.unmatchedSource}個 / 壊れているがMPDBに無い: ${report.brokenWithoutSource}個")
        val problems = report.ambiguous + report.failed + report.skippedOnline + report.skippedRaw + report.conflicts
        sender.sendMessage(
            "§7・スキップ: ${if (problems > 0) "§c" else "§a"}重複ID ${report.ambiguous} / 失敗 ${report.failed} / オンライン中 ${report.skippedOnline} / 未変換データ ${report.skippedRaw} / 競合 ${report.conflicts}"
        )
        val file = writeReportFile(report)
        if (file != null) sender.sendMessage("§7・詳細: §f${file.path}")
        if (!report.apply && report.restoredBroken + report.restoredContent + report.overwrittenDuplicates > 0) {
            sender.sendMessage("§e  実行するには末尾に §6confirm §eを付けて再実行してください。")
        }
    }

    private fun writeReportFile(report: Report): File? = try {
        val dir = File(plugin.dataFolder, "migration-reports").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
        val file = File(dir, "storagebox-${if (report.apply) "restore" else "check"}-$stamp.txt")
        val lines = synchronized(report) { report.lines.toList() }
        file.writeText(
            buildString {
                append("Storage Box ").append(if (report.apply) "restore" else "check (no changes)").append(' ').append(stamp).append('\n')
                append("players=${report.players} withBoxes=${report.playersWithBoxes} sourceBoxes=${report.sourceBoxes} sourceWithoutId=${report.sourceWithoutId}\n")
                append("restoredBroken=${report.restoredBroken} restoredContent=${report.restoredContent} overwrittenDuplicates=${report.overwrittenDuplicates} duplicateMismatch=${report.duplicateMismatch} healthy=${report.healthy}\n")
                append("unmatchedSource=${report.unmatchedSource} brokenWithoutSource=${report.brokenWithoutSource}\n")
                append("ambiguous=${report.ambiguous} failed=${report.failed} skippedOnline=${report.skippedOnline} skippedRaw=${report.skippedRaw} conflicts=${report.conflicts}\n\n")
                lines.forEach { append(it).append('\n') }
            },
            Charsets.UTF_8
        )
        file
    } catch (e: Exception) {
        plugin.logger.warning("Could not write the Storage Box report: ${e.message}")
        null
    }

    companion object {
        private const val DUPLICATE = "重複ID上書き"

        private val AMOUNT_IN_NAME =Regex("<\\d+>(?=[^<>]*$)")

        /** 1.15.2 material names that were renamed since (StorageBox resolves them the same way). */
        private val RENAMED_MATERIALS = mapOf(
            "GRASS" to "SHORT_GRASS",
            "GRASS_PATH" to "DIRT_PATH",
            "SCUTE" to "TURTLE_SCUTE",
            "CHAIN" to "IRON_CHAIN"
        )
    }
}
