package net.azisaba.azisync.util

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.advancement.Advancement
import org.bukkit.entity.Player
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class SerializedAdvancement(
    val key: NamespacedKey,
    val awardedCriteria: List<String>
)

object AdvancementSerializer {

    fun toBase64(player: Player): String {
        try {
            val advancements = ArrayList<Pair<String, Collection<String>>>()
            val iterator = Bukkit.advancementIterator()
            while (iterator.hasNext()) {
                val advancement = iterator.next()
                val awardedCriteria = player.getAdvancementProgress(advancement).awardedCriteria
                if (awardedCriteria.isNotEmpty()) {
                    advancements.add(advancement.key.toString() to awardedCriteria)
                }
            }

            val outputStream = ByteArrayOutputStream()
            DataOutputStream(outputStream).use { output ->
                output.writeInt(advancements.size)
                for ((key, criteria) in advancements) {
                    output.writeUTF(key)
                    output.writeInt(criteria.size)
                    criteria.forEach(output::writeUTF)
                }
            }
            return Base64Coder.encodeLines(outputStream.toByteArray())
        } catch (e: Exception) {
            throw IllegalStateException("Unable to save advancements.", e)
        }
    }

    fun fromBase64(data: String): List<SerializedAdvancement> {
        if (data.isEmpty() || data == "none") return emptyList()
        return try {
            val inputStream = ByteArrayInputStream(Base64Coder.decodeLines(data))
            DataInputStream(inputStream).use { input ->
                val size = input.readInt()
                val advancements = ArrayList<SerializedAdvancement>(size)
                for (i in 0 until size) {
                    val key = parseKey(input.readUTF())
                    val criteriaSize = input.readInt()
                    val criteria = ArrayList<String>(criteriaSize)
                    for (j in 0 until criteriaSize) {
                        criteria.add(input.readUTF())
                    }
                    if (key != null) {
                        advancements.add(SerializedAdvancement(key, criteria))
                    }
                }
                advancements
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun apply(player: Player, advancements: List<SerializedAdvancement>) {
        val savedAdvancements = advancements.associateBy { it.key }
        val iterator = Bukkit.advancementIterator()
        while (iterator.hasNext()) {
            val advancement = iterator.next()
            val progress = player.getAdvancementProgress(advancement)
            progress.awardedCriteria.toList().forEach { progress.revokeCriteria(it) }
            savedAdvancements[advancement.key]?.awardedCriteria?.forEach { progress.awardCriteria(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun parseKey(value: String): NamespacedKey? {
        val split = value.split(":", limit = 2)
        return try {
            if (split.size == 2) {
                NamespacedKey(split[0], split[1])
            } else {
                NamespacedKey.minecraft(value)
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
