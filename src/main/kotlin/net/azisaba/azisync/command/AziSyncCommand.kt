package net.azisaba.azisync.command

import net.azisaba.azisync.AziSync
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

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
                    if (args.size >= 3 && args[2].lowercase() == "confirm") {
                        plugin.mpdbMigrator.migrate(sender)
                    } else {
                        plugin.mpdbMigrator.scan(sender)
                    }
                } else if (args.size == 2 && args[1].lowercase() == "confirm") {
                    plugin.mpdbMigrator.migrate(sender)
                } else {
                    plugin.mpdbMigrator.scan(sender)
                }
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
            val subcommands = listOf("help", "reload", "saveall", "save", "load", "inv", "ec", "armor", "history", "rollback", "migrate")
            return subcommands.filter { it.startsWith(args[0].lowercase()) }.toMutableList()
        } else if (args.size == 2) {
            if (args[0].lowercase() == "migrate") {
                return listOf("mpdb").filter { it.startsWith(args[1].lowercase()) }.toMutableList()
            }
            if (args[0].lowercase() in listOf("save", "load", "inv", "ec", "armor", "history", "rollback")) {
                return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(args[1].lowercase()) }.toMutableList()
            }
        } else if (args.size == 3 && args[0].lowercase() == "migrate" && args[1].lowercase() == "mpdb") {
            return listOf("confirm").filter { it.startsWith(args[2].lowercase()) }.toMutableList()
        }
        return mutableListOf()
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
    }
}
