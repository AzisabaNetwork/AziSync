package net.azisaba.azisync.util

import org.bukkit.inventory.ItemStack
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream

object ItemSerializer {

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
        try {
            val inputStream = ByteArrayInputStream(Base64Coder.decodeLines(data))
            val dataInput = BukkitObjectInputStream(inputStream)
            
            val size = dataInput.readInt()
            val items = arrayOfNulls<ItemStack>(size)
            
            for (i in 0 until size) {
                items[i] = dataInput.readObject() as ItemStack?
            }
            
            dataInput.close()
            return items
        } catch (e: Exception) {
            throw IllegalStateException("Unable to decode class type.", e)
        }
    }
}