package net.azisaba.azisync.command

import net.azisaba.azisync.AziSync
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID

class AziSyncCommand(private val plugin: AziSync) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("azisync.admin")) {
            plugin.messageManager.sendMessage(sender, "no_permission")
            return true
        }

        if (command.name.lowercase() in listOf("inv")) {
            if (sender !is Player) {
                sender.sendMessage("This command can only be run by a player.")
                return true
            }
            if (args.isEmpty()) {
                plugin.messageManager.sendMessage(sender, "inv_usage")
                return true
            }
            plugin.offlineInventoryManager.openInventoryGui(sender, args[0])
            return true
        }

        if (args.isEmpty() || args[0].lowercase() == "help") {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "reload" -> {
                if (plugin.reloadRuntime()) {
                    plugin.messageManager.sendMessage(sender, "reload_success")
                } else {
                    sender.sendMessage("AziSync reload was cancelled because current player data could not be saved safely. The previous configuration remains active.")
                }
            }
            "saveall" -> {
                plugin.messageManager.sendMessage(sender, "save_initiated")
                plugin.server.onlinePlayers.forEach { plugin.syncManager.saveData(it) }
            }
            "save" -> {
                if (args.size < 2) {
                    sendHelp(sender)
                    return true
                }
                val targetName = args[1]
                val target = Bukkit.getPlayer(targetName)
                if (target == null || !target.isOnline) {
                    plugin.messageManager.sendMessage(sender, "player_not_found")
                    return true
                }
                plugin.messageManager.sendMessage(sender, "save_player_success", mapOf("{player}" to target.name))
                plugin.syncManager.saveData(target)
            }
            "load" -> {
                if (args.size < 2) {
                    sendHelp(sender)
                    return true
                }
                val targetName = args[1]
                val target = Bukkit.getPlayer(targetName)
                if (target == null || !target.isOnline) {
                    plugin.messageManager.sendMessage(sender, "player_not_found")
                    return true
                }
                plugin.messageManager.sendMessage(sender, "load_player_success", mapOf("{player}" to target.name))
                plugin.syncManager.loadData(target)
            }
            "inv", "invsee" -> {
                if (sender !is Player) {
                    sender.sendMessage("This command can only be run by a player.")
                    return true
                }
                if (args.size < 2) {
                    sendHelp(sender)
                    return true
                }
                plugin.offlineInventoryManager.openInventoryGui(sender, args[1])
            }
            "ec" -> {
                if (sender !is Player) {
                    sender.sendMessage("This command can only be run by a player.")
                    return true
                }
                if (args.size < 2) {
                    sendHelp(sender)
                    return true
                }
                plugin.offlineInventoryManager.openEnderChestGui(sender, args[1])
            }
            "armor" -> {
                if (sender !is Player) {
                    sender.sendMessage("This command can only be run by a player.")
                    return true
                }
                if (args.size < 2) {
                    sendHelp(sender)
                    return true
                }
                plugin.offlineInventoryManager.openArmorGui(sender, args[1])
            }
            "migrate" -> {
                if (args.size >= 2 && args[1].lowercase() == "mpdb") {
                    if (args.size >= 3) {
                        if (args[2].lowercase() == "confirm") {
                            plugin.mpdbMigrator.migrate(sender)
                        } else {
                            val module = args[2].lowercase()
                            if (args.size >= 4 && args[3].lowercase() == "confirm") {
                                plugin.mpdbMigrator.migrate(sender, module)
                            } else {
                                plugin.mpdbMigrator.scan(sender, module)
                            }
                        }
                    } else {
                        plugin.mpdbMigrator.scan(sender)
                    }
                } else if (args.size == 2 && args[1].lowercase() == "confirm") {
                    plugin.mpdbMigrator.migrate(sender)
                } else {
                    plugin.mpdbMigrator.scan(sender)
                }
            }
            "debug" -> {
                if (args.size < 2) {
                    sender.sendMessage("§6[AziSync] §cUsage: /azisync debug [fix] <player>")
                    return true
                }
                val isFix = args[1].lowercase() == "fix"
                val targetName = if (isFix) {
                    if (args.size < 3) {
                        sender.sendMessage("§6[AziSync] §cUsage: /azisync debug fix <player>")
                        return true
                    }
                    args[2]
                } else {
                    args[1]
                }
                executeDebug(sender, targetName, isFix)
            }
            else -> {
                plugin.messageManager.sendMessage(sender, "unknown_command")
                sendHelp(sender)
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String>? {
        if (!sender.hasPermission("azisync.admin")) return mutableListOf()

        if (command.name.lowercase() in listOf("inv", "invsee")) {
            if (args.size == 1) {
                return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(args[0].lowercase()) }.toMutableList()
            }
            return mutableListOf()
        }

        if (args.size == 1) {
            val subcommands = listOf("help", "reload", "saveall", "save", "load", "inv", "ec", "armor", "history", "rollback", "migrate", "debug")
            return subcommands.filter { it.startsWith(args[0].lowercase()) }.toMutableList()
        } else if (args.size == 2) {
            if (args[0].lowercase() == "migrate") {
                return listOf("mpdb").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
            }
            if (args[0].lowercase() == "debug") {
                val list = mutableListOf("fix")
                list.addAll(Bukkit.getOnlinePlayers().map { it.name })
                return list.filter { it.lowercase().startsWith(args[1].lowercase()) }.toMutableList()
            }
            if (args[0].lowercase() in listOf("save", "load", "inv", "ec", "armor", "history", "rollback")) {
                return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(args[1].lowercase()) }.toMutableList()
            }
        } else if (args.size == 3) {
            if (args[0].lowercase() == "debug" && args[1].lowercase() == "fix") {
                return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(args[2].lowercase()) }.toMutableList()
            }
            if (args[0].lowercase() == "migrate" && args[1].lowercase() == "mpdb") {
                val moduleNames = listOf("confirm", "economy", "inventory", "enderchest", "experience", "potionEffects", "advancement", "healthFoodAir", "location")
                return moduleNames.filter { it.lowercase().startsWith(args[2].lowercase()) }.toMutableList()
            }
        } else if (args.size == 4 && args[0].lowercase() == "migrate" && args[1].lowercase() == "mpdb") {
            return listOf("confirm").filter { it.startsWith(args[3].lowercase()) }.toMutableList()
        }
        return mutableListOf()
    }

    private fun executeDebug(sender: CommandSender, targetName: String, isFix: Boolean) {
        sender.sendMessage("§6[AziSync Debug] §eInspecting player '$targetName' (fixMode=$isFix)...")
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                val onlinePlayer = Bukkit.getPlayer(targetName)
                @Suppress("DEPRECATION")
                val offlinePlayer = Bukkit.getOfflinePlayer(targetName)
                val effectiveUuid = onlinePlayer?.uniqueId ?: offlinePlayer.uniqueId
                val hyphenatedUuid = effectiveUuid.toString().lowercase()
                val unhyphenatedUuid = hyphenatedUuid.replace("-", "")
                val offlineModeUuid = UUID.nameUUIDFromBytes("OfflinePlayer:$targetName".toByteArray(Charsets.UTF_8)).toString().lowercase()

                val econ = plugin.hookManager.economyHook.getEconomy()
                val vaultBalance = if (onlinePlayer != null && econ != null) {
                    econ.getBalance(onlinePlayer)
                } else if (econ != null) {
                    @Suppress("DEPRECATION")
                    econ.getBalance(offlinePlayer)
                } else null

                sender.sendMessage("§e=== Player Identity ===")
                sender.sendMessage("§7Name: §f$targetName §7| Online: §f${onlinePlayer != null} §7| Ready: §f${plugin.syncManager.isEconomyReady(effectiveUuid)}")
                sender.sendMessage("§7Online/Bukkit UUID: §b$hyphenatedUuid")
                sender.sendMessage("§7Unhyphenated UUID: §b$unhyphenatedUuid")
                sender.sendMessage("§7Offline Mode UUID:   §b$offlineModeUuid")
                sender.sendMessage("§7Vault Balance: §a${vaultBalance ?: "N/A"} §7(Provider: ${econ?.name ?: "none"})")

                // 1. AziSync DB (azisync_economy)
                val ecoTable = plugin.config.getString("database.TablesNames.economyTableName", "azisync_economy")!!
                var maxAziSyncMoney = 0.0
                var foundAziSyncRows = 0
                sender.sendMessage("§e=== AziSync DB ($ecoTable) ===")
                plugin.databaseManager.getConnection().use { conn ->
                    val sql = "SELECT `id`, `player_uuid`, `player_name`, `money`, `offline_money`, `sync_complete`, `last_seen` FROM `$ecoTable` WHERE `player_uuid` IN (?, ?, ?) OR LOWER(`player_name`) = LOWER(?)"
                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setString(1, hyphenatedUuid)
                        stmt.setString(2, unhyphenatedUuid)
                        stmt.setString(3, offlineModeUuid)
                        stmt.setString(4, targetName)
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                foundAziSyncRows++
                                val id = rs.getLong("id")
                                val u = rs.getString("player_uuid")
                                val n = rs.getString("player_name")
                                val m = rs.getDouble("money")
                                val om = rs.getDouble("offline_money")
                                val sc = rs.getString("sync_complete")
                                val ls = rs.getString("last_seen")
                                if (m > maxAziSyncMoney) maxAziSyncMoney = m
                                sender.sendMessage("§7[#$id] uuid=§f$u§7, name=§f$n§7, money=§a$m§7, offline=§e$om§7, sync=§f$sc§7, seen=§f$ls")
                            }
                        }
                    }
                }
                if (foundAziSyncRows == 0) {
                    sender.sendMessage("§cNo matching rows in $ecoTable")
                }

                // 2. MPDB Source DB (mpdb_economy)
                val sourceInfo = plugin.mpdbMigrator.resolveSourceInfo()
                var maxMpdbMoney = 0.0
                var foundMpdbRows = 0
                sender.sendMessage("§e=== MPDB Source DB (${sourceInfo.description}) ===")
                try {
                    plugin.mpdbMigrator.getSourceConnection(sourceInfo).use { sourceConn ->
                        val mpdbTable = plugin.config.getString("migration.mpdb.tables.economy", "mpdb_economy")!!
                        val hasOfflineMoney = runCatching {
                            sourceConn.metaData.getColumns(null, null, mpdbTable, "offline_money").use { it.next() }
                        }.getOrDefault(false)
                        val selectSql = if (hasOfflineMoney) {
                            "SELECT `player_uuid`, `player_name`, `money`, `offline_money`, `sync_complete`, `last_seen` FROM `$mpdbTable` WHERE `player_uuid` IN (?, ?, ?) OR LOWER(`player_name`) = LOWER(?)"
                        } else {
                            "SELECT `player_uuid`, `player_name`, `money`, `sync_complete`, `last_seen` FROM `$mpdbTable` WHERE `player_uuid` IN (?, ?, ?) OR LOWER(`player_name`) = LOWER(?)"
                        }
                        sourceConn.prepareStatement(selectSql).use { stmt ->
                            stmt.setString(1, hyphenatedUuid)
                            stmt.setString(2, unhyphenatedUuid)
                            stmt.setString(3, offlineModeUuid)
                            stmt.setString(4, targetName)
                            stmt.executeQuery().use { rs ->
                                while (rs.next()) {
                                    foundMpdbRows++
                                    val u = rs.getString("player_uuid")
                                    val n = rs.getString("player_name")
                                    val m = rs.getDouble("money")
                                    val om = if (hasOfflineMoney) rs.getDouble("offline_money") else 0.0
                                    val sc = rs.getString("sync_complete")
                                    val ls = rs.getString("last_seen")
                                    if (m > maxMpdbMoney) maxMpdbMoney = m
                                    sender.sendMessage("§7[MPDB] uuid=§f$u§7, name=§f$n§7, money=§a$m§7, offline=§e$om§7, sync=§f$sc§7, seen=§f$ls")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    sender.sendMessage("§cCould not query MPDB source database: ${e.message}")
                }
                if (foundMpdbRows == 0) {
                    sender.sendMessage("§cNo matching rows in MPDB source table")
                }

                val targetRecoveryMoney = maxOf(maxAziSyncMoney, maxMpdbMoney)
                if (isFix) {
                    if (targetRecoveryMoney <= 0.0) {
                        sender.sendMessage("§c[AziSync Fix] No positive balance found in either database to recover.")
                        return@Runnable
                    }
                    sender.sendMessage("§6[AziSync Fix] §eRecovering balance §a$targetRecoveryMoney §efor $targetName (UUID: $hyphenatedUuid)...")
                    // Update or Insert into azisync_economy with canonical hyphenated UUID
                    plugin.databaseManager.economyHandler.setData(effectiveUuid, targetName, targetRecoveryMoney, "true")
                    // Delete duplicate/legacy rows in azisync_economy if any
                    plugin.databaseManager.getConnection().use { conn ->
                        val delSql = "DELETE FROM `$ecoTable` WHERE (`player_uuid` = ? OR `player_uuid` = ? OR LOWER(`player_name`) = LOWER(?)) AND `player_uuid` != ?"
                        conn.prepareStatement(delSql).use { stmt ->
                            stmt.setString(1, unhyphenatedUuid)
                            stmt.setString(2, offlineModeUuid)
                            stmt.setString(3, targetName)
                            stmt.setString(4, hyphenatedUuid)
                            stmt.executeUpdate()
                        }
                    }
                    // Apply to Vault on main thread
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        if (econ != null && onlinePlayer != null && onlinePlayer.isOnline) {
                            val current = econ.getBalance(onlinePlayer)
                            val diff = targetRecoveryMoney - current
                            if (diff > 0) econ.depositPlayer(onlinePlayer, diff)
                            else if (diff < 0) econ.withdrawPlayer(onlinePlayer, -diff)
                        }
                        plugin.syncManager.markEconomyReady(effectiveUuid)
                        sender.sendMessage("§6[AziSync Fix] §aSuccessfully recovered balance §e$targetRecoveryMoney §afor §f$targetName§a!")
                    })
                } else {
                    if (targetRecoveryMoney > (vaultBalance ?: 0.0)) {
                        sender.sendMessage("§a[Recommendation] Found balance §e$targetRecoveryMoney §ain DB. Run §f/azisync debug fix $targetName §ato immediately restore!")
                    }
                }
            } catch (e: Exception) {
                sender.sendMessage("§cError during debug inspection: ${e.message}")
                e.printStackTrace()
            }
        })
    }

    private fun sendHelp(sender: CommandSender) {
        plugin.messageManager.sendMessage(sender, "help_header")
        plugin.messageManager.sendMessage(sender, "help_reload")
        plugin.messageManager.sendMessage(sender, "help_saveall")
        plugin.messageManager.sendMessage(sender, "help_save")
        plugin.messageManager.sendMessage(sender, "help_load")
        plugin.messageManager.sendMessage(sender, "help_invsee")
        plugin.messageManager.sendMessage(sender, "help_ecsee")
        plugin.messageManager.sendMessage(sender, "help_armor")
        plugin.messageManager.sendMessage(sender, "help_history")
        plugin.messageManager.sendMessage(sender, "help_rollback")
        plugin.messageManager.sendMessage(sender, "help_migrate")
        sender.sendMessage("§7/azisync debug [fix] <player> §e- Inspect or fix player database & Vault data")
    }
}
