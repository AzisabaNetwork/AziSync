package net.azisaba.azisync.util

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.inventory.meta.Repairable
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.lang.reflect.Method
import java.util.UUID
import java.util.logging.Level
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object ItemSerializer {

    var legacyDataVersion: Int = 2230

    private var paperDeserializeMethod: Method? = null
    private var paperDeserializeChecked = false

    private val LEGACY_MATERIAL_MAP = mapOf(
        "GOLD_SWORD" to "GOLDEN_SWORD",
        "GOLD_AXE" to "GOLDEN_AXE",
        "GOLD_PICKAXE" to "GOLDEN_PICKAXE",
        "GOLD_SPADE" to "GOLDEN_SHOVEL",
        "GOLD_HOE" to "GOLDEN_HOE",
        "GOLD_HELMET" to "GOLDEN_HELMET",
        "GOLD_CHESTPLATE" to "GOLDEN_CHESTPLATE",
        "GOLD_LEGGINGS" to "GOLDEN_LEGGINGS",
        "GOLD_BOOTS" to "GOLDEN_BOOTS",
        "WOOD_SWORD" to "WOODEN_SWORD",
        "WOOD_AXE" to "WOODEN_AXE",
        "WOOD_PICKAXE" to "WOODEN_PICKAXE",
        "WOOD_SPADE" to "WOODEN_SHOVEL",
        "WOOD_HOE" to "WOODEN_HOE",
        "IRON_SPADE" to "IRON_SHOVEL",
        "DIAMOND_SPADE" to "DIAMOND_SHOVEL",
        "CARROT_ITEM" to "CARROT",
        "POTATO_ITEM" to "POTATO"
    )

    private val LEGACY_ENCHANT_MAP = mapOf(
        0 to "PROTECTION_ENVIRONMENTAL",
        1 to "PROTECTION_FIRE",
        2 to "PROTECTION_FALL",
        3 to "PROTECTION_EXPLOSIONS",
        4 to "PROTECTION_PROJECTILE",
        5 to "OXYGEN",
        6 to "WATER_WORKER",
        7 to "THORNS",
        8 to "DEPTH_STRIDER",
        9 to "FROST_WALKER",
        16 to "DAMAGE_ALL",
        17 to "DAMAGE_UNDEAD",
        18 to "DAMAGE_ARTHROPODS",
        19 to "KNOCKBACK",
        20 to "FIRE_ASPECT",
        21 to "LOOT_BONUS_MOBS",
        22 to "SWEEPING_EDGE",
        32 to "DIG_SPEED",
        33 to "SILK_TOUCH",
        34 to "DURABILITY",
        35 to "LOOT_BONUS_BLOCKS",
        48 to "ARROW_DAMAGE",
        49 to "ARROW_KNOCKBACK",
        50 to "ARROW_FIRE",
        51 to "ARROW_INFINITE",
        61 to "LUCK",
        62 to "LURE",
        70 to "MENDING",
        71 to "VANISHING_CURSE"
    )

    private val MODERN_TO_LEGACY_ENCHANT = mapOf(
        "protection" to "PROTECTION_ENVIRONMENTAL",
        "fire_protection" to "PROTECTION_FIRE",
        "feather_falling" to "PROTECTION_FALL",
        "blast_protection" to "PROTECTION_EXPLOSIONS",
        "projectile_protection" to "PROTECTION_PROJECTILE",
        "respiration" to "OXYGEN",
        "aqua_affinity" to "WATER_WORKER",
        "thorns" to "THORNS",
        "depth_strider" to "DEPTH_STRIDER",
        "frost_walker" to "FROST_WALKER",
        "sharpness" to "DAMAGE_ALL",
        "smite" to "DAMAGE_UNDEAD",
        "bane_of_arthropods" to "DAMAGE_ARTHROPODS",
        "knockback" to "KNOCKBACK",
        "fire_aspect" to "FIRE_ASPECT",
        "looting" to "LOOT_BONUS_MOBS",
        "sweeping" to "SWEEPING_EDGE",
        "sweeping_edge" to "SWEEPING_EDGE",
        "efficiency" to "DIG_SPEED",
        "silk_touch" to "SILK_TOUCH",
        "unbreaking" to "DURABILITY",
        "fortune" to "LOOT_BONUS_BLOCKS",
        "power" to "ARROW_DAMAGE",
        "punch" to "ARROW_KNOCKBACK",
        "flame" to "ARROW_FIRE",
        "infinity" to "ARROW_INFINITE",
        "luck_of_the_sea" to "LUCK",
        "lure" to "LURE",
        "mending" to "MENDING",
        "curse_of_vanishing" to "VANISHING_CURSE",
        "curse_of_binding" to "BINDING_CURSE"
    )

    fun toBase64(items: Array<ItemStack?>): String {
        try {
            val outputStream = ByteArrayOutputStream()
            val dataOutput = BukkitObjectOutputStream(outputStream)

            dataOutput.writeInt(items.size)
            for (item in items) {
                dataOutput.writeObject(item)
            }

            dataOutput.close()
            return Base64Coder.encodeLines(outputStream.toByteArray())
        } catch (e: Exception) {
            throw IllegalStateException("Unable to save item stacks.", e)
        }
    }

    fun fromBase64(data: String): Array<ItemStack?> {
        return fromBase64(data, 36)
    }

    fun fromBase64(data: String, defaultSize: Int): Array<ItemStack?> {
        if (data.isBlank() || data == "none") {
            return arrayOfNulls(defaultSize)
        }

        val bytes = try {
            Base64Coder.decodeLines(data)
        } catch (_: Throwable) {
            try {
                java.util.Base64.getDecoder().decode(data.trim())
            } catch (e: Throwable) {
                throw IllegalStateException("Unable to decode Base64 string.", e)
            }
        }

        if (bytes.isEmpty()) {
            return arrayOfNulls(defaultSize)
        }

        // 1. Check for BukkitObjectInputStream format (magic header: 0xAC 0xED)
        if (bytes.size >= 2 && bytes[0] == 0xAC.toByte() && bytes[1] == 0xED.toByte()) {
            try {
                val inputStream = ByteArrayInputStream(bytes)
                val dataInput = BukkitObjectInputStream(inputStream)

                val size = dataInput.readInt()
                val items = arrayOfNulls<ItemStack>(size)

                for (i in 0 until size) {
                    items[i] = dataInput.readObject() as? ItemStack
                }

                dataInput.close()
                return items
            } catch (e: Exception) {
                throw IllegalStateException("Unable to decode BukkitObjectInputStream class type.", e)
            }
        }

        // 2. Check for NBT format (0x09 0x00 0x00 0x0A for uncompressed TAG_List, or 0x1F 0x8B for GZIP NBT)
        if ((bytes.size >= 4 && bytes[0] == 0x09.toByte() && bytes[1] == 0x00.toByte() && bytes[2] == 0x00.toByte() && bytes[3] == 0x0A.toByte()) ||
            (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) ||
            (bytes.size >= 1 && (bytes[0] == 0x0A.toByte() || bytes[0] == 0x09.toByte()))
        ) {
            try {
                val rawPayload = readNbt(bytes)
                return fromNbtList(rawPayload, defaultSize)
            } catch (e: Exception) {
                Bukkit.getLogger().log(Level.SEVERE, "[AziSync] Error deserializing NBT item list: ${e.message}", e)
                throw IllegalStateException("Unable to decode NBT item data.", e)
            }
        }

        // 3. Fallback: try BukkitObjectInputStream first, then NBT
        try {
            val inputStream = ByteArrayInputStream(bytes)
            val dataInput = BukkitObjectInputStream(inputStream)
            val size = dataInput.readInt()
            val items = arrayOfNulls<ItemStack>(size)
            for (i in 0 until size) {
                items[i] = dataInput.readObject() as? ItemStack
            }
            dataInput.close()
            return items
        } catch (_: Throwable) {
            try {
                val rawPayload = readNbt(bytes)
                return fromNbtList(rawPayload, defaultSize)
            } catch (e: Throwable) {
                throw IllegalStateException("Unable to decode class type (neither BukkitObjectInputStream nor NBT).", e)
            }
        }
    }

    private fun fromNbtList(rawPayload: Any?, defaultSize: Int): Array<ItemStack?> {
        @Suppress("UNCHECKED_CAST")
        val list: List<Map<String, Any?>> = when (rawPayload) {
            is List<*> -> rawPayload.filterIsInstance<Map<String, Any?>>()
            is Map<*, *> -> {
                val innerList = (rawPayload["Inventory"] ?: rawPayload["items"] ?: rawPayload["inventory"] ?: rawPayload["Items"]) as? List<*>
                innerList?.filterIsInstance<Map<String, Any?>>() ?: listOf(rawPayload as Map<String, Any?>)
            }
            else -> return arrayOfNulls(defaultSize)
        }

        if (list.isEmpty()) {
            return arrayOfNulls(defaultSize)
        }

        val hasSlot = list.any { it.containsKey("Slot") }
        if (!hasSlot) {
            val size = maxOf(defaultSize, list.size)
            val items = arrayOfNulls<ItemStack>(size)
            for (i in list.indices) {
                items[i] = parseItem(list[i])
            }
            return items
        }

        val rawSlots = list.mapNotNull { (it["Slot"] as? Number)?.toInt() }
        val hasArmorSlots = rawSlots.any { it in 100..103 }
        val hasOffhandSlots = rawSlots.any { it == -106 || it == 150 || it == 40 }
        val hasExtendedSlots = rawSlots.any { it >= 36 }

        val targetSize = when {
            defaultSize == 4 -> 4
            defaultSize == 27 -> 27
            hasArmorSlots || hasOffhandSlots || hasExtendedSlots || defaultSize >= 41 -> 41
            else -> 36
        }

        val result = arrayOfNulls<ItemStack>(targetSize)

        for (compound in list) {
            val rawSlot = (compound["Slot"] as? Number)?.toInt() ?: continue
            val targetSlot = when (targetSize) {
                4 -> if (rawSlot in 100..103) rawSlot - 100 else rawSlot
                27 -> rawSlot
                41 -> when {
                    rawSlot in 0..35 -> rawSlot
                    rawSlot == 100 -> 36 // boots
                    rawSlot == 101 -> 37 // leggings
                    rawSlot == 102 -> 38 // chestplate
                    rawSlot == 103 -> 39 // helmet
                    rawSlot == -106 || rawSlot == 150 || rawSlot == 40 -> 40 // offhand
                    rawSlot in 36..40 -> rawSlot
                    else -> -1
                }
                else -> if (rawSlot in 0 until targetSize) rawSlot else -1
            }

            if (targetSlot in 0 until targetSize) {
                try {
                    val item = parseItem(compound)
                    if (item != null && !isAir(item.type)) {
                        result[targetSlot] = item
                    }
                } catch (e: Throwable) {
                    Bukkit.getLogger().log(Level.WARNING, "[AziSync] Failed to deserialize item at slot $rawSlot: ${e.message}")
                }
            }
        }

        return result
    }

    private fun isAir(material: Material?): Boolean {
        if (material == null) return true
        val name = material.name
        return name == "AIR" || name.endsWith("_AIR")
    }

    private fun parseItem(compound: Map<String, Any?>): ItemStack? {
        val id = compound["id"]?.toString() ?: return null
        if (id.isEmpty() || id == "minecraft:air" || id == "air") return null

        // 1. If data is modern Paper component format without legacy "tag", attempt Paper deserializeBytes
        val hasLegacyTag = compound.containsKey("tag")
        if (!hasLegacyTag) {
            try {
                val deserializeMethod = getPaperDeserializeBytesMethod()
                if (deserializeMethod != null) {
                    val nbtBytes = writeSingleItemNbt(compound)
                    val item = deserializeMethod.invoke(null, nbtBytes) as? ItemStack
                    if (item != null && !isAir(item.type)) {
                        enrichItemMeta(item, compound)
                        return item
                    }
                }
            } catch (_: Throwable) {
                // Silently fallback without spamming logs
            }
        }

        // 2. Pure Bukkit API construction (handles both 1.15.2 legacy NBT and modern formats completely)
        return buildItemViaBukkit(compound)
    }

    private fun getPaperDeserializeBytesMethod(): Method? {
        if (!paperDeserializeChecked) {
            paperDeserializeChecked = true
            try {
                paperDeserializeMethod = ItemStack::class.java.getMethod("deserializeBytes", ByteArray::class.java)
            } catch (_: Throwable) {
                paperDeserializeMethod = null
            }
        }
        return paperDeserializeMethod
    }

    private fun getServerDataVersion(): Int {
        try {
            val unsafe = Bukkit.getUnsafe()
            val m = unsafe.javaClass.getMethod("getDataVersion")
            val ver = m.invoke(unsafe) as? Number
            if (ver != null && ver.toInt() > 0) return ver.toInt()
        } catch (_: Throwable) {}
        return 3955 // Fallback to 1.21 data version
    }

    private fun writeSingleItemNbt(compound: Map<String, Any?>): ByteArray {
        val rootMap = LinkedHashMap<String, Any?>()
        for ((k, v) in compound) {
            if (k != "Slot") {
                rootMap[k] = v
            }
        }
        val count = ((compound["Count"] ?: compound["count"]) as? Number)?.toInt() ?: 1
        val explicitDataVersion = (compound["DataVersion"] as? Number)?.toInt()
        val hasTag = compound.containsKey("tag")
        val hasComponents = compound.containsKey("components")

        val dataVersion = when {
            explicitDataVersion != null && explicitDataVersion > 0 -> explicitDataVersion
            hasTag || !hasComponents -> legacyDataVersion // 2230 for 1.15.2
            else -> getServerDataVersion()
        }

        rootMap["DataVersion"] = dataVersion

        if (dataVersion < 3818) { // Pre-1.20.5 (component system transition)
            rootMap["Count"] = count.toByte()
            rootMap.remove("count")
        } else {
            rootMap["count"] = count
        }

        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            val dos = DataOutputStream(gzip)
            dos.writeByte(10) // TAG_Compound
            dos.writeUTF("") // unnamed
            for ((k, v) in rootMap) {
                val type = getTagType(v)
                if (type == 0.toByte()) continue
                dos.writeByte(type.toInt())
                dos.writeUTF(k)
                writePayload(v, dos)
            }
            dos.writeByte(0) // TAG_End
        }
        return baos.toByteArray()
    }

    private fun buildItemViaBukkit(compound: Map<String, Any?>): ItemStack? {
        val id = compound["id"]?.toString() ?: return null
        val mat = resolveMaterial(id) ?: return null
        val count = ((compound["Count"] ?: compound["count"]) as? Number)?.toInt() ?: 1
        val item = ItemStack(mat, maxOf(1, minOf(64, count)))
        val meta = item.itemMeta ?: return item

        val tag = (compound["tag"] ?: compound["components"]) as? Map<*, *>

        // Durability / Damage
        val damage = ((tag?.get("Damage") ?: tag?.get("damage")) ?: compound["Damage"]) as? Number
        if (damage != null && damage.toInt() > 0 && meta is Damageable) {
            meta.damage = damage.toInt()
        }

        if (tag != null) {
            // Display: Name & Lore (supports legacy display and modern component keys)
            val display = (tag["display"] ?: tag["Display"]) as? Map<*, *>
            val name = (display?.get("Name") ?: display?.get("name") ?: tag["minecraft:custom_name"] ?: tag["custom_name"])?.toString()
            if (name != null) {
                meta.setDisplayName(extractTextFromJson(name))
            }
            val rawLore = display?.get("Lore") ?: display?.get("lore") ?: tag["minecraft:lore"] ?: tag["lore"]
            val lore = rawLore as? List<*>
            if (lore != null) {
                meta.lore = lore.mapNotNull { it?.toString() }.map { extractTextFromJson(it) }
            }

            // CustomModelData
            val cmd = (tag["CustomModelData"] ?: tag["custom_model_data"] ?: tag["minecraft:custom_model_data"]) as? Number
            if (cmd != null) {
                try {
                    val method = meta.javaClass.methods.firstOrNull { it.name == "setCustomModelData" && it.parameterCount == 1 }
                    method?.invoke(meta, cmd.toInt())
                } catch (_: Throwable) {}
            }

            // Unbreakable
            val unb = tag["Unbreakable"] ?: tag["unbreakable"] ?: tag["minecraft:unbreakable"]
            if (unb != null) {
                val isUnb = when (unb) {
                    is Number -> unb.toInt() != 0
                    is Boolean -> unb
                    else -> false
                }
                if (isUnb) {
                    try {
                        meta.isUnbreakable = true
                    } catch (_: Throwable) {}
                }
            }

            // AttributeModifiers
            applyAttributeModifiers(meta, tag, compound)

            // HideFlags / ItemFlags
            val hideFlags = (tag["HideFlags"] ?: tag["hide_flags"]) as? Number
            if (hideFlags != null && hideFlags.toInt() != 0) {
                val flags = hideFlags.toInt()
                if ((flags and 1) != 0) safeAddFlag(meta, "HIDE_ENCHANTS")
                if ((flags and 2) != 0) safeAddFlag(meta, "HIDE_ATTRIBUTES")
                if ((flags and 4) != 0) safeAddFlag(meta, "HIDE_UNBREAKABLE")
                if ((flags and 8) != 0) safeAddFlag(meta, "HIDE_DESTROYS")
                if ((flags and 16) != 0) safeAddFlag(meta, "HIDE_PLACED_ON")
                if ((flags and 32) != 0) safeAddFlag(meta, "HIDE_POTION_EFFECTS")
                if ((flags and 64) != 0) safeAddFlag(meta, "HIDE_DYE")
            }

            // Enchantments
            val enchs = tag["Enchantments"] ?: tag["ench"] ?: tag["minecraft:enchantments"] ?: compound["minecraft:enchantments"]
            applyEnchantments(meta, enchs, isStored = false)

            // StoredEnchantments (for Enchanted Books)
            val stored = tag["StoredEnchantments"] ?: tag["stored_enchantments"] ?: tag["minecraft:stored_enchantments"] ?: compound["minecraft:stored_enchantments"]
            if (meta is EnchantmentStorageMeta) {
                applyEnchantments(meta, stored, isStored = true)
            }

            // RepairCost
            val rc = (tag["RepairCost"] ?: tag["repair_cost"]) as? Number
            if (rc != null && meta is Repairable) {
                meta.repairCost = rc.toInt()
            }

            // Leather armor color
            val colorInt = (display?.get("color") ?: tag["color"]) as? Number
            if (colorInt != null && meta is LeatherArmorMeta) {
                try {
                    meta.setColor(Color.fromRGB(colorInt.toInt()))
                } catch (_: Throwable) {}
            }

            // Skull owner & textures (safe reflection without triggering Mojang HTTP 429 lookups)
            val skull = tag["SkullOwner"] ?: tag["skull_owner"] ?: tag["minecraft:profile"]
            if (meta is SkullMeta && skull != null) {
                applySkull(meta, skull)
            }

            // PotionMeta
            if (meta is org.bukkit.inventory.meta.PotionMeta) {
                val potionType = (tag["Potion"] ?: tag["potion"] ?: tag["minecraft:potion_contents"])?.toString()
                if (potionType != null) {
                    applyPotionData(meta, potionType)
                }
            }
        }

        item.itemMeta = meta
        return item
    }

    private fun enrichItemMeta(item: ItemStack, compound: Map<String, Any?>) {
        val tag = (compound["tag"] ?: compound["components"]) as? Map<*, *> ?: return
        val meta = item.itemMeta ?: return
        var changed = false

        val display = (tag["display"] ?: tag["Display"]) as? Map<*, *>
        if (display != null) {
            val name = (display["Name"] ?: display["name"] ?: tag["minecraft:custom_name"])?.toString()
            if (name != null && !meta.hasDisplayName()) {
                val extracted = extractTextFromJson(name)
                if (extracted.isNotEmpty()) {
                    meta.setDisplayName(extracted)
                    changed = true
                }
            }
            val rawLore = display["Lore"] ?: display["lore"] ?: tag["minecraft:lore"]
            val lore = rawLore as? List<*>
            if (lore != null && lore.isNotEmpty() && !meta.hasLore()) {
                val extractedLore = lore.mapNotNull { it?.toString() }.map { extractTextFromJson(it) }
                if (extractedLore.isNotEmpty()) {
                    meta.lore = extractedLore
                    changed = true
                }
            }
        }

        // CustomModelData
        val cmd = (tag["CustomModelData"] ?: tag["custom_model_data"]) as? Number
        if (cmd != null) {
            try {
                val hasCmdMethod = meta.javaClass.methods.firstOrNull { it.name == "hasCustomModelData" && it.parameterCount == 0 }
                val hasCmd = hasCmdMethod?.invoke(meta) as? Boolean ?: false
                if (!hasCmd) {
                    val setCmdMethod = meta.javaClass.methods.firstOrNull { it.name == "setCustomModelData" && it.parameterCount == 1 }
                    setCmdMethod?.invoke(meta, cmd.toInt())
                    changed = true
                }
            } catch (_: Throwable) {}
        }

        // Unbreakable
        val unb = tag["Unbreakable"] ?: tag["unbreakable"]
        if (unb != null && !meta.isUnbreakable) {
            val isUnb = when (unb) {
                is Number -> unb.toInt() != 0
                is Boolean -> unb
                else -> false
            }
            if (isUnb) {
                try {
                    meta.isUnbreakable = true
                    changed = true
                } catch (_: Throwable) {}
            }
        }

        // AttributeModifiers
        if (meta.attributeModifiers == null || meta.attributeModifiers?.isEmpty == true) {
            applyAttributeModifiers(meta, tag, compound)
            changed = true
        }

        // HideFlags
        val hideFlags = (tag["HideFlags"] ?: tag["hide_flags"]) as? Number
        if (hideFlags != null && hideFlags.toInt() != 0) {
            val flags = hideFlags.toInt()
            if ((flags and 1) != 0) safeAddFlag(meta, "HIDE_ENCHANTS")
            if ((flags and 2) != 0) safeAddFlag(meta, "HIDE_ATTRIBUTES")
            if ((flags and 4) != 0) safeAddFlag(meta, "HIDE_UNBREAKABLE")
            if ((flags and 8) != 0) safeAddFlag(meta, "HIDE_DESTROYS")
            if ((flags and 16) != 0) safeAddFlag(meta, "HIDE_PLACED_ON")
            if ((flags and 32) != 0) safeAddFlag(meta, "HIDE_POTION_EFFECTS")
            if ((flags and 64) != 0) safeAddFlag(meta, "HIDE_DYE")
            changed = true
        }

        // Enchantments
        val enchs = tag["Enchantments"] ?: tag["ench"] ?: tag["minecraft:enchantments"] ?: compound["minecraft:enchantments"]
        if (enchs != null && item.enchantments.isEmpty()) {
            applyEnchantments(meta, enchs, isStored = false)
            changed = true
        }

        // StoredEnchantments
        val stored = tag["StoredEnchantments"] ?: tag["stored_enchantments"] ?: tag["minecraft:stored_enchantments"] ?: compound["minecraft:stored_enchantments"]
        if (stored != null && meta is EnchantmentStorageMeta) {
            applyEnchantments(meta, stored, isStored = true)
            changed = true
        }

        // RepairCost
        val rc = (tag["RepairCost"] ?: tag["repair_cost"]) as? Number
        if (rc != null && meta is Repairable && meta.repairCost == 0) {
            meta.repairCost = rc.toInt()
            changed = true
        }

        // Leather armor color
        val colorInt = (display?.get("color") ?: tag["color"]) as? Number
        if (colorInt != null && meta is LeatherArmorMeta) {
            try {
                meta.setColor(Color.fromRGB(colorInt.toInt()))
                changed = true
            } catch (_: Throwable) {}
        }

        // Skull owner & textures
        val skull = tag["SkullOwner"] ?: tag["skull_owner"] ?: tag["minecraft:profile"]
        if (meta is SkullMeta && skull != null && !meta.hasOwner()) {
            applySkull(meta, skull)
            changed = true
        }

        if (changed) {
            item.itemMeta = meta
        }
    }

    private fun safeAddFlag(meta: ItemMeta, flagName: String) {
        try {
            val flag = ItemFlag.valueOf(flagName)
            meta.addItemFlags(flag)
        } catch (_: Throwable) {}
    }

    private fun applyEnchantments(meta: org.bukkit.inventory.meta.ItemMeta, rawEnchs: Any?, isStored: Boolean) {
        if (rawEnchs == null) return
        when (rawEnchs) {
            is List<*> -> {
                for (e in rawEnchs) {
                    val eMap = e as? Map<*, *> ?: continue
                    val eId = (eMap["id"] ?: eMap["Id"])?.toString()
                    val lvl = ((eMap["lvl"] ?: eMap["level"]) as? Number)?.toInt() ?: 1
                    val ench = findEnchantment(eId) ?: continue
                    if (isStored && meta is EnchantmentStorageMeta) {
                        try { meta.addStoredEnchant(ench, lvl, true) } catch (_: Throwable) {}
                    } else {
                        meta.addEnchant(ench, lvl, true)
                    }
                }
            }
            is Map<*, *> -> {
                val levels = (rawEnchs["levels"] as? Map<*, *>) ?: rawEnchs
                for ((k, v) in levels) {
                    val eId = k?.toString()
                    val lvl = (v as? Number)?.toInt() ?: 1
                    val ench = findEnchantment(eId) ?: continue
                    if (isStored && meta is EnchantmentStorageMeta) {
                        try { meta.addStoredEnchant(ench, lvl, true) } catch (_: Throwable) {}
                    } else {
                        meta.addEnchant(ench, lvl, true)
                    }
                }
            }
        }
    }

    private fun applyAttributeModifiers(meta: org.bukkit.inventory.meta.ItemMeta, tag: Map<*, *>?, compound: Map<String, Any?>) {
        val rawModifiers = tag?.get("AttributeModifiers")
            ?: tag?.get("attribute_modifiers")
            ?: tag?.get("minecraft:attribute_modifiers")
            ?: compound["minecraft:attribute_modifiers"]
            ?: return

        val modifierList: List<*> = when (rawModifiers) {
            is List<*> -> rawModifiers
            is Map<*, *> -> (rawModifiers["modifiers"] as? List<*>) ?: emptyList<Any>()
            else -> emptyList<Any>()
        }

        if (modifierList.isEmpty()) return

        for (entry in modifierList) {
            val attrMap = entry as? Map<*, *> ?: continue
            val rawAttrName = (attrMap["AttributeName"] ?: attrMap["type"] ?: attrMap["attribute"])?.toString() ?: continue
            val attribute = resolveAttribute(rawAttrName) ?: continue

            val amount = ((attrMap["Amount"] ?: attrMap["amount"]) as? Number)?.toDouble() ?: continue

            val rawOp = attrMap["Operation"] ?: attrMap["operation"]
            val operation = when (rawOp) {
                is Number -> when (rawOp.toInt()) {
                    1 -> AttributeModifier.Operation.ADD_SCALAR
                    2 -> AttributeModifier.Operation.MULTIPLY_SCALAR_1
                    else -> AttributeModifier.Operation.ADD_NUMBER
                }
                is String -> when (rawOp.lowercase()) {
                    "add_multiplied_base", "1", "add_scalar" -> AttributeModifier.Operation.ADD_SCALAR
                    "add_multiplied_total", "2", "multiply_scalar_1" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1
                    else -> AttributeModifier.Operation.ADD_NUMBER
                }
                else -> AttributeModifier.Operation.ADD_NUMBER
            }

            val slotStr = (attrMap["Slot"] ?: attrMap["slot"])?.toString()?.lowercase()
            val equipmentSlot = when (slotStr) {
                "mainhand" -> EquipmentSlot.HAND
                "offhand" -> EquipmentSlot.OFF_HAND
                "head" -> EquipmentSlot.HEAD
                "chest" -> EquipmentSlot.CHEST
                "legs" -> EquipmentSlot.LEGS
                "feet" -> EquipmentSlot.FEET
                else -> null
            }

            val uuidMost = (attrMap["UUIDMost"] as? Number)?.toLong()
            val uuidLeast = (attrMap["UUIDLeast"] as? Number)?.toLong()
            val uuid = when {
                uuidMost != null && uuidLeast != null -> UUID(uuidMost, uuidLeast)
                attrMap["UUID"] is IntArray && (attrMap["UUID"] as IntArray).size == 4 -> {
                    val arr = attrMap["UUID"] as IntArray
                    val most = (arr[0].toLong() shl 32) or (arr[1].toLong() and 0xFFFFFFFFL)
                    val least = (arr[2].toLong() shl 32) or (arr[3].toLong() and 0xFFFFFFFFL)
                    UUID(most, least)
                }
                attrMap["uuid"] is String -> try {
                    UUID.fromString(attrMap["uuid"].toString())
                } catch (_: Throwable) {
                    UUID.randomUUID()
                }
                else -> UUID.randomUUID()
            }

            val modName = (attrMap["Name"] ?: attrMap["name"] ?: attrMap["id"])?.toString() ?: rawAttrName

            try {
                val modifier = if (equipmentSlot != null) {
                    AttributeModifier(uuid, modName, amount, operation, equipmentSlot)
                } else {
                    AttributeModifier(uuid, modName, amount, operation)
                }
                meta.addAttributeModifier(attribute, modifier)
            } catch (_: Throwable) {
                // Ignore modifier registration errors on unsupported attributes
            }
        }
    }

    private fun resolveAttribute(name: String?): Attribute? {
        if (name.isNullOrBlank()) return null
        val clean = name.removePrefix("minecraft:")
        // Convert camelCase or dot/dash to UPPER_SNAKE_CASE (e.g. generic.attackDamage -> GENERIC_ATTACK_DAMAGE)
        val upperSnake = clean
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .replace('.', '_')
            .replace('-', '_')
            .uppercase()

        // 1. Try exact uppercase snake case
        try {
            return Attribute.valueOf(upperSnake)
        } catch (_: Throwable) {}

        // 2. Try with GENERIC_ prefix if missing
        if (!upperSnake.startsWith("GENERIC_")) {
            try {
                return Attribute.valueOf("GENERIC_$upperSnake")
            } catch (_: Throwable) {}
        } else {
            // 3. Try without GENERIC_ prefix (modern 1.21.2+ Bukkit enums)
            try {
                return Attribute.valueOf(upperSnake.removePrefix("GENERIC_"))
            } catch (_: Throwable) {}
        }

        // 4. Try Registry (modern Bukkit / Paper)
        try {
            val registryClass = Class.forName("org.bukkit.Registry")
            val attributeField = registryClass.getField("ATTRIBUTE")
            val registry = attributeField.get(null)
            val getMethod = registry.javaClass.getMethod("get", NamespacedKey::class.java)
            val key = NamespacedKey.minecraft(clean.lowercase().replace('.', '_'))
            val attr = getMethod.invoke(registry, key) as? Attribute
            if (attr != null) return attr
        } catch (_: Throwable) {}

        return null
    }

    private fun applySkull(meta: SkullMeta, skull: Any?) {
        try {
            val profileClass = Class.forName("com.mojang.authlib.GameProfile")
            val propertyClass = Class.forName("com.mojang.authlib.properties.Property")

            val name: String?
            val uuid: UUID
            val textureValue: String?

            when (skull) {
                is String -> {
                    name = skull
                    uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$skull".toByteArray(Charsets.UTF_8))
                    textureValue = null
                }
                is Map<*, *> -> {
                    name = (skull["Name"] ?: skull["name"])?.toString()
                    val idStr = (skull["Id"] ?: skull["id"])?.toString()
                    val parsedUuid = if (idStr != null) {
                        try { UUID.fromString(idStr) } catch (_: Throwable) { null }
                    } else null
                    uuid = parsedUuid ?: if (name != null) UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8)) else UUID.randomUUID()

                    val properties = skull["Properties"] as? Map<*, *>
                    val textures = properties?.get("textures") as? List<*>
                    val firstTexture = textures?.firstOrNull() as? Map<*, *>
                    textureValue = firstTexture?.get("Value")?.toString()
                }
                else -> return
            }

            val profile = profileClass.getConstructor(UUID::class.java, String::class.java).newInstance(uuid, name ?: "")

            if (!textureValue.isNullOrBlank()) {
                val getProperties = profileClass.getMethod("getProperties")
                val propertyMap = getProperties.invoke(profile)
                val putMethod = propertyMap.javaClass.getMethod("put", Any::class.java, Any::class.java)
                val property = propertyClass.getConstructor(String::class.java, String::class.java).newInstance("textures", textureValue)
                putMethod.invoke(propertyMap, "textures", property)
            }

            // Directly inject GameProfile to avoid triggering Paper's online Mojang API lookups
            val profileField = meta.javaClass.getDeclaredField("profile")
            profileField.isAccessible = true
            profileField.set(meta, profile)
        } catch (_: Throwable) {}
    }

    private fun applyPotionData(meta: org.bukkit.inventory.meta.PotionMeta, potionTypeStr: String) {
        try {
            val clean = potionTypeStr.removePrefix("minecraft:")
            val isExtended = clean.startsWith("long_")
            val isUpgraded = clean.startsWith("strong_")
            val baseName = clean.removePrefix("long_").removePrefix("strong_").uppercase()
            val type = org.bukkit.potion.PotionType.valueOf(baseName)
            meta.basePotionData = org.bukkit.potion.PotionData(type, isExtended, isUpgraded)
        } catch (_: Throwable) {}
    }

    private fun resolveMaterial(id: String): Material? {
        val clean = id.removePrefix("minecraft:").uppercase()
        Material.matchMaterial(clean)?.let { return it }
        Material.matchMaterial(id)?.let { return it }

        val modernName = LEGACY_MATERIAL_MAP[clean]
        if (modernName != null) {
            Material.matchMaterial(modernName)?.let { return it }
        }

        try {
            val matchMethod = Material::class.java.getMethod("matchMaterial", String::class.java, Boolean::class.javaPrimitiveType)
            val res = matchMethod.invoke(null, clean, true) as? Material
            if (res != null) return res
        } catch (_: Throwable) {}

        return null
    }

    private fun findEnchantment(id: String?): Enchantment? {
        if (id.isNullOrBlank()) return null
        val clean = id.removePrefix("minecraft:").lowercase()

        try {
            val key = NamespacedKey.minecraft(clean)
            Enchantment.getByKey(key)?.let { return it }
        } catch (_: Throwable) {}

        Enchantment.getByName(clean.uppercase())?.let { return it }

        val num = id.toIntOrNull()
        if (num != null) {
            val legacyName = LEGACY_ENCHANT_MAP[num]
            if (legacyName != null) {
                Enchantment.getByName(legacyName)?.let { return it }
                try {
                    Enchantment.getByKey(NamespacedKey.minecraft(legacyName.lowercase()))?.let { return it }
                } catch (_: Throwable) {}
            }
        }

        val bukkitLegacyName = MODERN_TO_LEGACY_ENCHANT[clean]
        if (bukkitLegacyName != null) {
            Enchantment.getByName(bukkitLegacyName)?.let { return it }
        }

        return null
    }

    fun extractTextFromJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[") && !trimmed.startsWith("\"")) {
            return raw
        }
        return try {
            val element = parseJsonElement(trimmed) ?: return raw
            if (element.isJsonPrimitive) {
                return element.asString
            }
            val sb = StringBuilder()
            appendJsonText(element, sb)
            sb.toString()
        } catch (_: Throwable) {
            raw
        }
    }

    private fun parseJsonElement(json: String): JsonElement? {
        return try {
            val m = JsonParser::class.java.getMethod("parseString", String::class.java)
            m.invoke(null, json) as? JsonElement
        } catch (_: Throwable) {
            try {
                @Suppress("DEPRECATION")
                JsonParser().parse(json)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun appendJsonText(element: JsonElement, sb: StringBuilder) {
        if (element.isJsonPrimitive) {
            sb.append(element.asString)
            return
        }
        if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                appendJsonText(child, sb)
            }
            return
        }
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            val color = obj.get("color")?.asString
            if (color != null) {
                val chatColor = colorToChatColor(color)
                if (chatColor != null) sb.append(chatColor)
            }
            if (obj.get("bold")?.asBoolean == true) sb.append("§l")
            if (obj.get("italic")?.asBoolean == true) sb.append("§o")
            if (obj.get("underlined")?.asBoolean == true) sb.append("§n")
            if (obj.get("strikethrough")?.asBoolean == true) sb.append("§m")
            if (obj.get("obfuscated")?.asBoolean == true) sb.append("§k")

            obj.get("text")?.asString?.let { sb.append(it) }

            obj.get("extra")?.let { extra ->
                if (extra.isJsonArray) {
                    for (child in extra.asJsonArray) {
                        appendJsonText(child, sb)
                    }
                }
            }
        }
    }

    private fun colorToChatColor(color: String): String? {
        val clean = color.lowercase()
        return when (clean) {
            "black" -> "§0"
            "dark_blue" -> "§1"
            "dark_green" -> "§2"
            "dark_aqua" -> "§3"
            "dark_red" -> "§4"
            "dark_purple" -> "§5"
            "gold" -> "§6"
            "gray" -> "§7"
            "dark_gray" -> "§8"
            "blue" -> "§9"
            "green" -> "§a"
            "aqua" -> "§b"
            "red" -> "§c"
            "light_purple" -> "§d"
            "yellow" -> "§e"
            "white" -> "§f"
            "reset" -> "§r"
            else -> {
                if (clean.startsWith("#") && clean.length == 7) {
                    val sb = StringBuilder("§x")
                    for (c in clean.substring(1)) {
                        sb.append('§').append(c)
                    }
                    sb.toString()
                } else null
            }
        }
    }

    private fun readNbt(bytes: ByteArray): Any? {
        val isGzip = bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
        val rawStream: InputStream = ByteArrayInputStream(bytes)
        val inStream = if (isGzip) GZIPInputStream(rawStream) else rawStream
        DataInputStream(inStream).use { dis ->
            val tagType = dis.readByte().toInt()
            if (tagType == 0) return null
            dis.readUTF() // root tag name (typically "")
            return readPayload(tagType.toByte(), dis)
        }
    }

    private fun readPayload(type: Byte, dis: DataInputStream): Any? {
        return when (type.toInt()) {
            0 -> null
            1 -> dis.readByte()
            2 -> dis.readShort()
            3 -> dis.readInt()
            4 -> dis.readLong()
            5 -> dis.readFloat()
            6 -> dis.readDouble()
            7 -> {
                val len = dis.readInt()
                val buf = ByteArray(len)
                dis.readFully(buf)
                buf
            }
            8 -> dis.readUTF()
            9 -> {
                val elemType = dis.readByte()
                val len = dis.readInt()
                val list = ArrayList<Any?>()
                for (i in 0 until len) {
                    val elem = readPayload(elemType, dis)
                    if (elem != null) list.add(elem)
                }
                list
            }
            10 -> {
                val map = LinkedHashMap<String, Any?>()
                while (true) {
                    val t = dis.readByte()
                    if (t == 0.toByte()) break
                    val name = dis.readUTF()
                    val value = readPayload(t, dis)
                    if (value != null) map[name] = value
                }
                map
            }
            11 -> {
                val len = dis.readInt()
                IntArray(len) { dis.readInt() }
            }
            12 -> {
                val len = dis.readInt()
                LongArray(len) { dis.readLong() }
            }
            else -> throw IllegalArgumentException("Unknown NBT tag type: $type")
        }
    }

    private fun writePayload(value: Any?, dos: DataOutputStream) {
        when (value) {
            is Byte -> dos.writeByte(value.toInt())
            is Boolean -> dos.writeByte(if (value) 1 else 0)
            is Short -> dos.writeShort(value.toInt())
            is Int -> dos.writeInt(value)
            is Long -> dos.writeLong(value)
            is Float -> dos.writeFloat(value)
            is Double -> dos.writeDouble(value)
            is ByteArray -> {
                dos.writeInt(value.size)
                dos.write(value)
            }
            is String -> dos.writeUTF(value)
            is List<*> -> {
                val elemType = if (value.isEmpty()) 0.toByte() else getTagType(value.firstOrNull())
                dos.writeByte(elemType.toInt())
                dos.writeInt(value.size)
                for (elem in value) {
                    writePayload(elem, dos)
                }
            }
            is Map<*, *> -> {
                for ((k, v) in value) {
                    if (k == null) continue
                    val keyStr = k.toString()
                    val type = getTagType(v)
                    if (type == 0.toByte()) continue
                    dos.writeByte(type.toInt())
                    dos.writeUTF(keyStr)
                    writePayload(v, dos)
                }
                dos.writeByte(0) // TAG_End
            }
            is IntArray -> {
                dos.writeInt(value.size)
                for (i in value) dos.writeInt(i)
            }
            is LongArray -> {
                dos.writeInt(value.size)
                for (l in value) dos.writeLong(l)
            }
            null -> {}
            else -> dos.writeUTF(value.toString())
        }
    }

    private fun getTagType(value: Any?): Byte {
        return when (value) {
            null -> 0
            is Byte, is Boolean -> 1
            is Short -> 2
            is Int -> 3
            is Long -> 4
            is Float -> 5
            is Double -> 6
            is ByteArray -> 7
            is String -> 8
            is List<*> -> 9
            is Map<*, *> -> 10
            is IntArray -> 11
            is LongArray -> 12
            else -> 8
        }
    }
}