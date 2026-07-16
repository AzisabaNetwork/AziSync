package net.azisaba.azisync.hook

import com.gamingmesh.jobs.Jobs
import net.azisaba.azisync.AziSync
import java.lang.reflect.Method
import java.util.UUID
class JobsHook(private val plugin: AziSync) {
    
    private var isHooked = false
    private var checkedIsSavingMethod = false
    private var isSavingMethod: Method? = null

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
            val method = getIsSavingMethod(jobsPlayer) ?: return false
            method.invoke(jobsPlayer) as? Boolean ?: false
        } catch (e: ReflectiveOperationException) {
            plugin.logger.warning("Failed to check Jobs saving state: ${e.message}")
            false
        } catch (e: LinkageError) {
            plugin.logger.warning("Failed to check Jobs saving state: ${e.message}")
            false
        }
    }

    private fun getIsSavingMethod(jobsPlayer: Any): Method? {
        if (!checkedIsSavingMethod) {
            isSavingMethod = jobsPlayer.javaClass.methods.firstOrNull {
                it.name == "isSaving" && it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
            }
            checkedIsSavingMethod = true

            if (isSavingMethod == null) {
                plugin.logger.warning("JobsPlayer#isSaving() is not available in this Jobs version. AziSync will not wait for Jobs saving tasks.")
            }
        }

        return isSavingMethod
    }
}
