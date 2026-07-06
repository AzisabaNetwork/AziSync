package net.azisaba.azisync.command

import net.azisaba.azisync.AziSync
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class AziSyncCommand(private val plugin: AziSync) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("azisync.admin")) {
            plugin.messageManager.sendMessage(sender, "no_permission")
            return true
        }

        if (args.isEmpty() || args[0].lowercase() == "help") {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "reload" -> {
                plugin.reloadConfig()
                plugin.messageManager.loadLanguages()
                plugin.messageManager.sendMessage(sender, "reload_success")
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
            else -> {
                plugin.messageManager.sendMessage(sender, "unknown_command")
                sendHelp(sender)
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String>? {
        if (!sender.hasPermission("azisync.admin")) return mutableListOf()
        
        if (args.size == 1) {
            val subcommands = listOf("help", "reload", "saveall", "save", "load")
            return subcommands.filter { it.startsWith(args[0].lowercase()) }.toMutableList()
        } else if (args.size == 2 && (args[0].lowercase() == "save" || args[0].lowercase() == "load")) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(args[1].lowercase()) }.toMutableList()
        }
        return mutableListOf()
    }

    private fun sendHelp(sender: CommandSender) {
        plugin.messageManager.sendMessage(sender, "help_header")
        plugin.messageManager.sendMessage(sender, "help_reload")
        plugin.messageManager.sendMessage(sender, "help_saveall")
        plugin.messageManager.sendMessage(sender, "help_save")
        plugin.messageManager.sendMessage(sender, "help_load")
    }
}