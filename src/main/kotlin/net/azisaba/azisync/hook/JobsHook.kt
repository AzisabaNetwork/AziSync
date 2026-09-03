package net.azisaba.azisync.hook

import com.gamingmesh.jobs.Jobs
import com.gamingmesh.jobs.container.JobsPlayer
import net.azisaba.azisync.AziSync
import java.util.UUID

class JobsHook(private val plugin: AziSync) {

    private var isHooked = false

    fun init() {
        if (plugin.server.pluginManager.isPluginEnabled("Jobs")) {
            isHooked = true
            plugin.logger.info("Jobs hook initialized. AziSync will wait for Jobs saving tasks before syncing data.")
        }
    }

    fun isPlayerSaving(uuid: UUID): Boolean {
        if (!isHooked) return false

        return try {
            val jobsPlayer: JobsPlayer? = Jobs.getPlayerManager().getJobsPlayer(uuid)
            jobsPlayer?.isSaving ?: false
        } catch (t: Throwable) {
            plugin.logger.warning("Failed to check Jobs saving state: ${t.message}")
            false
        }
    }
}