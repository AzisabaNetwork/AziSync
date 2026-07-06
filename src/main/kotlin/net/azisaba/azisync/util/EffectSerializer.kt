package net.azisaba.azisync.util

import org.bukkit.potion.PotionEffect
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object EffectSerializer {

    fun toBase64(effects: Collection<PotionEffect>): String {
        try {
            val outputStream = ByteArrayOutputStream()
            val dataOutput = BukkitObjectOutputStream(outputStream)
            
            dataOutput.writeInt(effects.size)
            for (effect in effects) {
                dataOutput.writeObject(effect)
            }
            
            dataOutput.close()
            return Base64Coder.encodeLines(outputStream.toByteArray())
        } catch (e: Exception) {
            throw IllegalStateException("Unable to save potion effect.", e)
        }
    }

    fun fromBase64(data: String): Collection<PotionEffect> {
        if (data.isEmpty() || data == "none") return emptyList()
        try {
            val inputStream = ByteArrayInputStream(Base64Coder.decodeLines(data))
            val dataInput = BukkitObjectInputStream(inputStream)
            
            val size = dataInput.readInt()
            val effects = ArrayList<PotionEffect>()
            
            for (i in 0 until size) {
                effects.add(dataInput.readObject() as PotionEffect)
            }
            
            dataInput.close()
            return effects
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }
}