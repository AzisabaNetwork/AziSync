package net.azisaba.azisync.hook

import com.gamingmesh.jobs.Jobs
import net.azisaba.azisync.AziSync
import java.util.UUID
class JobsHook(private val plugin: AziSync) {
    
    private var isHooked = false

    fun init() {
        if (plugin.server.pluginManager.getPlugin("Jobs") != null) {
            isHooked = true
            plugin.logger.info("Jobs hook initialized. AziSync will wait for Jobs saving tasks before syncing data.")
        }
    }

    fun isPlayerSaving(uuid: UUID): Boolean {
        if (!isHooked) return false
        
        return try {
            val jobsPlayer = Jobs.getPlayerManager().getJobsPlayer(uuid) ?: return false
            jobsPlayer.isSaving
        } catch (_: Exception) {
            false
        }
    }
}