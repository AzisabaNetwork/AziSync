package net.azisaba.azisync

import net.azisaba.azisync.command.AziSyncCommand
import net.azisaba.azisync.database.DatabaseManager
import net.azisaba.azisync.listener.PlayerJoinListener
import net.azisaba.azisync.listener.PlayerQuitListener
import net.azisaba.azisync.sync.SyncManager
import net.azisaba.azisync.task.DataSaveTask
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class AziSync : JavaPlugin() {

    lateinit var databaseManager: DatabaseManager
        private set
        
    lateinit var syncManager: SyncManager
        private set
        
    lateinit var messageManager: net.azisaba.azisync.util.MessageManager
        private set
        
    lateinit var hookManager: net.azisaba.azisync.hook.HookManager
        private set

    override fun onEnable() {
        saveDefaultConfig()
        
        databaseManager = DatabaseManager(this)
        syncManager = SyncManager(this)
        messageManager = net.azisaba.azisync.util.MessageManager(this)
        

        DataSaveTask(this).start()

        server.pluginManager.registerEvents(PlayerJoinListener(this), this)
        server.pluginManager.registerEvents(PlayerQuitListener(this), this)
        server.pluginManager.registerEvents(net.azisaba.azisync.listener.PlayerProtectListener(this), this)
        
        hookManager = net.azisaba.azisync.hook.HookManager(this)
        hookManager.registerHooks()
        
        val commandExecutor = AziSyncCommand(this)
        getCommand("azisync")?.apply {
            setExecutor(commandExecutor)
            tabCompleter = commandExecutor
        }
        logger.info("AziSync has been enabled.")
    }

    override fun onDisable() {
        server.onlinePlayers.forEach {
            syncManager.saveData(it, true)
        }
        
        if (::databaseManager.isInitialized) {
            databaseManager.close()
        }
        logger.info("AziSync has been disabled.")
    }

    fun saveAziSyncData(player: Player) {
        syncManager.saveData(player)
    }
}