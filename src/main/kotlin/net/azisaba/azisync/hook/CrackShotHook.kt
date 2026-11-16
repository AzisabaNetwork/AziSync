package net.azisaba.azisync.hook

import net.azisaba.crackshot.api.CrackShotApi
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack

/**
 * Gives CrackShot weapons from 1.15.2 the `crackshot:weapon_id` tag through CrackShot's API
 * ([CrackShotApi.tagLegacyWeapon]). CrackShot finds the weapon by its name without color codes, and only when
 * exactly one weapon has that name and the item's material matches its `Item_Type`.
 *
 * Does nothing when CrackShot is not enabled or is a version without the API (e.g. on a 1.15.2 server).
 */
object CrackShotHook {
    /** Tags every untagged CrackShot weapon in the array in place. Main thread only. Returns the number tagged. */
    fun repair(items: Array<ItemStack?>?): Int {
        if (items == null) return 0
        val api = api() ?: return 0
        var repaired = 0
        for (item in items) {
            val weaponId = api.tagLegacyWeapon(item ?: continue) ?: continue
            Bukkit.getLogger().info("[AziSync] Tagged CrackShot weapon '$weaponId'")
            repaired++
        }
        return repaired
    }

    private fun api(): CrackShotApi? {
        val plugin = Bukkit.getPluginManager().getPlugin("CrackShot") ?: return null
        if (!plugin.isEnabled) return null
        return try {
            CrackShotApi.getOrNull()
        } catch (_: LinkageError) {
            null
        }
    }
}
