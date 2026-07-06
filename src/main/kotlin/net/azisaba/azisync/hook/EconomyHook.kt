package net.azisaba.azisync.hook

import net.milkbowl.vault.economy.Economy
import net.azisaba.azisync.AziSync

class EconomyHook(private val plugin: AziSync) {
    private var econ: Economy? = null

    fun setupEconomy(): Boolean {
        if (plugin.server.pluginManager.getPlugin("Vault") == null) {
            return false
        }
        val rsp = plugin.server.servicesManager.getRegistration(Economy::class.java) ?: return false
        econ = rsp.provider
        return econ != null
    }

    fun getEconomy(): Economy? {
        return econ
    }
}