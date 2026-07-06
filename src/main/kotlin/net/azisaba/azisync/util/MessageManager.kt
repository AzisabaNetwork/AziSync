package net.azisaba.azisync.util

import net.azisaba.azisync.AziSync
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

class MessageManager(private val plugin: AziSync) {

    private val langCache = ConcurrentHashMap<String, YamlConfiguration>()
    private val defaultLang = "lang_ja"

    init {
        loadLanguages()
    }

    fun loadLanguages() {
        langCache.clear()
        
        val messagesDir = File(plugin.dataFolder, "messages")
        if (!messagesDir.exists()) {
            messagesDir.mkdirs()
        }

        arrayOf("lang_ja.yml", "lang_en.yml").forEach { fileName ->
            val file = File(messagesDir, fileName)
            if (!file.exists()) {
                val resource = plugin.getResource("messages/$fileName")
                if (resource != null) {
                    plugin.saveResource("messages/$fileName", false)
                }
            }
            if (file.exists()) {
                val config = YamlConfiguration.loadConfiguration(file)
                val nameWithoutExt = fileName.replace(".yml", "")
                langCache[nameWithoutExt] = config
            }
        }
        plugin.logger.info("Loaded ${langCache.size} language files.")
    }

    private fun getLocale(sender: CommandSender): String {
        if (sender !is Player) return defaultLang
        val locale = sender.locale.lowercase()
        return if (locale.startsWith("ja")) "lang_ja" else "lang_en"
    }

    fun getMessage(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()): String {
        val langKey = getLocale(sender)
        val config = langCache[langKey] ?: langCache[defaultLang]
        
        if (config == null) return "Missing Lang File"
        
        val prefix = config.getString("prefix", "&8[&bAziSync&8] ")!!
        var message = config.getString(key, "Missing key: $key")!!
        
        for ((k, v) in placeholders) {
            message = message.replace(k, v)
        }
        
        return ChatColor.translateAlternateColorCodes('&', prefix + message)
    }

    fun sendMessage(sender: CommandSender, key: String, placeholders: Map<String, String> = emptyMap()) {
        sender.sendMessage(getMessage(sender, key, placeholders))
    }
}