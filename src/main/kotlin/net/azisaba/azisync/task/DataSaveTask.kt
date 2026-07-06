package net.azisaba.azisync.task

import net.azisaba.azisync.AziSync
import org.bukkit.Bukkit

class DataSaveTask(private val plugin: AziSync) : Runnable {
    
    private val interval = plugin.config.getLong("general.saveDataTask.interval", 5) * 60 * 20L

    fun start() {
        if (plugin.config.getBoolean("general.saveDataTask.enabled", true)) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this, interval, interval)
            plugin.logger.info("DataSaveTask started with interval: ${interval / 20 / 60} minutes.")
        } else {
            plugin.logger.info("DataSaveTask is disabled.")
        }
    }

    override fun run() {
        val hideLog = plugin.config.getBoolean("general.saveDataTask.hideLogMessages", false)
        val players = Bukkit.getOnlinePlayers()
        
        if (players.isNotEmpty()) {
            if (!hideLog) plugin.logger.info("Saving data for ${players.size} online players...")
            val startTime = System.currentTimeMillis()
            
            for (player in players) {
                plugin.syncManager.saveData(player)
            }
            
            if (!hideLog) {
                plugin.logger.info("Data save completed in ${System.currentTimeMillis() - startTime}ms.")
            }
        }
    }
}