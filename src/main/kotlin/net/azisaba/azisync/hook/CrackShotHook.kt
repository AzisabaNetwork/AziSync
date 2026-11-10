package net.azisaba.azisync.hook

import net.azisaba.azisync.util.ItemSerializer
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * Gives CrackShot weapons from 1.15.2 the `crackshot:weapon_id` tag that Azisaba's CrackShot checks first.
 *
 * Those weapons carry no tag: CrackShot recognises them only when the part of the display name before "«"
 * equals the configured `Item_Name` code for code. A 1.15.2 name converted to a text component and back can
 * come out with different (but equivalent) color codes, so the weapon stops working as a gun. The weapon is
 * found again by its name without color codes, and only when exactly one weapon has that name and the item's
 * material matches its `Item_Type`.
 */
object CrackShotHook {
    private const val PDC = "PublicBukkitValues"
    private const val WEAPON_ID = "crackshot:weapon_id"

    private class Index(val source: Map<*, *>, val size: Int, val byName: Map<String, String?>)

    @Volatile
    private var cache: Index? = null

    /** Tags every untagged CrackShot weapon in the array in place. Main thread only. Returns the number tagged. */
    fun repair(items: Array<ItemStack?>?): Int {
        if (items == null || !ItemSerializer.canEditItemNbt()) return 0
        val index = index() ?: return 0
        var repaired = 0
        for (i in items.indices) {
            val item = items[i] ?: continue
            val fixed = repairItem(item, index) ?: continue
            items[i] = fixed
            repaired++
        }
        return repaired
    }

    private fun repairItem(item: ItemStack, index: Index): ItemStack? {
        if (item.type == Material.AIR || !item.hasItemMeta()) return null
        val meta = item.itemMeta ?: return null
        if (!meta.hasDisplayName()) return null
        val parent = index.byName[pureName(meta.displayName)] ?: return null
        if (!materialMatches(item, parent)) return null

        val nbt = ItemSerializer.itemToNbt(item) ?: return null
        @Suppress("UNCHECKED_CAST")
        val components = nbt["components"] as? MutableMap<String, Any?> ?: LinkedHashMap<String, Any?>().also { nbt["components"] = it }
        @Suppress("UNCHECKED_CAST")
        val customData = components["minecraft:custom_data"] as? MutableMap<String, Any?>
            ?: LinkedHashMap<String, Any?>().also { components["minecraft:custom_data"] = it }
        @Suppress("UNCHECKED_CAST")
        val values = customData[PDC] as? MutableMap<String, Any?>
            ?: LinkedHashMap<String, Any?>().also { customData[PDC] = it }
        val current = values[WEAPON_ID] as? String
        if (current != null && current in index.byName.values) return null // already a working tag
        values[WEAPON_ID] = parent
        val fixed = ItemSerializer.itemFromNbt(nbt) ?: return null
        Bukkit.getLogger().info("[AziSync] Tagged CrackShot weapon '$parent' (${ChatColor.stripColor(meta.displayName)})")
        return fixed
    }

    /** CrackShot's weapon names without color codes -> weapon, or null when CrackShot is not enabled. */
    private fun index(): Index? {
        val plugin = Bukkit.getPluginManager().getPlugin("CrackShot")?.takeIf { it.isEnabled } ?: return null
        val parents = try {
            val data = plugin.javaClass.getField("data").get(plugin)
            data.javaClass.getField("parentlist").get(data) as? Map<*, *>
        } catch (_: Throwable) {
            null
        } ?: return null
        cache?.let { if (it.source === parents && it.size == parents.size) return it }
        val byName = HashMap<String, String?>()
        for ((name, parent) in parents) {
            val key = pureName(name?.toString() ?: continue)
            if (key.isEmpty()) continue
            val id = parent?.toString() ?: continue
            // the same name for two weapons: never guess
            byName[key] = if (byName.containsKey(key) && byName[key] != id) null else id
        }
        return Index(parents, parents.size, byName).also { cache = it }
    }

    /** The weapon part of a CrackShot item name ("«ammo»" removed) without color codes. */
    internal fun pureName(displayName: String): String {
        var name = displayName
        val ammo = name.lastIndexOf('«')
        if (ammo >= 0) name = name.substring(0, ammo)
        val attachment = name.indexOf('▶')
        if (attachment >= 0) name = name.substring(0, attachment)
        return (ChatColor.stripColor(name) ?: name).trim()
    }

    private fun materialMatches(item: ItemStack, parent: String): Boolean {
        val type = try {
            val plugin = Bukkit.getPluginManager().getPlugin("CrackShot") ?: return false
            val data = plugin.javaClass.getField("data").get(plugin)
            data.javaClass.getMethod("getString", String::class.java).invoke(data, "$parent.Item_Information.Item_Type") as? String
        } catch (_: Throwable) {
            null
        } ?: return true
        val name = type.substringBefore('~').substringBefore(':').trim()
        if (name.isEmpty() || name.all { it.isDigit() }) return true // legacy numeric id: cannot compare
        val material = Material.matchMaterial(name) ?: return true
        return material == item.type
    }
}
