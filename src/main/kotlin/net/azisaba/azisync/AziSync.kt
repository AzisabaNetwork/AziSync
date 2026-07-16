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
import org.bukkit.event.HandlerList
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
        if (!initializeRuntime()) {
            server.pluginManager.disablePlugin(this)
            return
        }

        val commandExecutor = AziSyncCommand(this)
        getCommand("azisync")?.apply {
            setExecutor(commandExecutor)
            tabCompleter = commandExecutor
        }

        logger.info("AziSync has been enabled.")
    }

    private fun initializeRuntime(): Boolean {
        try {
            databaseManager = DatabaseManager(this)
        } catch (e: Exception) {
            logger.severe("Failed to initialize database.")
            e.printStackTrace()
            return false
        }

        syncManager = SyncManager(this)
        messageManager = MessageManager(this)
        server.pluginManager.registerEvents(PlayerJoinListener(this), this)
        server.pluginManager.registerEvents(PlayerQuitListener(this), this)
        server.pluginManager.registerEvents(PlayerProtectListener(this), this)
        
        hookManager = HookManager(this)
        hookManager.registerHooks()

        invseeManager = InvseeManager(this)
        DataSaveTask(this).start()

        server.onlinePlayers.forEach {
            syncManager.markLoaded(it)
        }

        return true
    }

    override fun onDisable() {
        stopRuntime()
        logger.info("AziSync has been disabled.")
    }

    fun reloadRuntime(): Boolean {
        if (!flushOnlinePlayerData()) {
            logger.warning("AziSync reload was cancelled because player data could not be saved safely.")
            return false
        }
        stopRuntime(saveOnlinePlayerData = false)
        reloadConfig()
        if (!initializeRuntime()) {
            server.pluginManager.disablePlugin(this)
            return false
        }
        return true
    }

    private fun stopRuntime(saveOnlinePlayerData: Boolean = true) {
        if (::syncManager.isInitialized && !syncManager.isShutdown()) {
            if (saveOnlinePlayerData) {
                flushOnlinePlayerData()
            }
            server.scheduler.cancelTasks(this)
            syncManager.shutdown()
        } else {
            server.scheduler.cancelTasks(this)
        }

        HandlerList.unregisterAll(this)
        
        if (::databaseManager.isInitialized) {
            databaseManager.close()
        }
    }

    private fun flushOnlinePlayerData(): Boolean {
        if (!::syncManager.isInitialized || syncManager.isShutdown()) return true
        server.onlinePlayers.forEach {
            syncManager.saveDataNow(it, true)
        }
        return syncManager.flushPendingSaves(10)
    }

    fun saveAziSyncData(player: Player) {
        syncManager.saveData(player)
    }
}
