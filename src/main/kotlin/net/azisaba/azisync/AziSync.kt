package net.azisaba.azisync

import net.azisaba.azisync.command.AziSyncCommand
import net.azisaba.azisync.database.DatabaseManager
import net.azisaba.azisync.hook.HookManager
import net.azisaba.azisync.listener.PlayerJoinListener
import net.azisaba.azisync.listener.PlayerProtectListener
import net.azisaba.azisync.listener.PlayerQuitListener
import net.azisaba.azisync.manager.InvseeManager
import net.azisaba.azisync.sync.SyncManager
import net.azisaba.azisync.task.DataSaveTask
import net.azisaba.azisync.util.MessageManager
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class AziSync : JavaPlugin() {

    lateinit var databaseManager: DatabaseManager
        private set
        
    lateinit var syncManager: SyncManager
        private set
        
    lateinit var messageManager: MessageManager
        private set
        
    lateinit var hookManager: HookManager
        private set

    lateinit var invseeManager: InvseeManager
        private set

    override fun onEnable() {
        saveDefaultConfig()

        try {
            databaseManager = DatabaseManager(this)
        } catch (e: Exception) {
            logger.severe("Failed to initialize database. Disabling AziSync.")
            e.printStackTrace()
            server.pluginManager.disablePlugin(this)
            return
        }

        syncManager = SyncManager(this)
        messageManager = MessageManager(this)
        invseeManager = InvseeManager(this)
        

        DataSaveTask(this).start()

        server.pluginManager.registerEvents(PlayerJoinListener(this), this)
        server.pluginManager.registerEvents(PlayerQuitListener(this), this)
        server.pluginManager.registerEvents(PlayerProtectListener(this), this)
        
        hookManager = HookManager(this)
        hookManager.registerHooks()
        
        val commandExecutor = AziSyncCommand(this)
        getCommand("azisync")?.apply {
            setExecutor(commandExecutor)
            tabCompleter = commandExecutor
        }

        server.onlinePlayers.forEach {
            syncManager.markLoaded(it)
        }

        logger.info("AziSync has been enabled.")
    }

    override fun onDisable() {
        if (::syncManager.isInitialized) {
            server.onlinePlayers.forEach {
                syncManager.saveDataNow(it, true)
            }
        }

        server.scheduler.cancelTasks(this)
        
        if (::databaseManager.isInitialized) {
            databaseManager.close()
        }
        logger.info("AziSync has been disabled.")
    }

    fun saveAziSyncData(player: Player) {
        syncManager.saveData(player)
    }
}
