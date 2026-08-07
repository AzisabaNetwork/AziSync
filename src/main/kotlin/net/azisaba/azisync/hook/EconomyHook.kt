package net.azisaba.azisync.hook

import net.milkbowl.vault.economy.Economy
import net.azisaba.azisync.AziSync
import net.azisaba.azisync.util.EconomyAudit

class EconomyHook(private val plugin: AziSync) {
    private var econ: Economy? = null

    fun setupEconomy(): Boolean {
        if (plugin.server.pluginManager.getPlugin("Vault") == null) {
            EconomyAudit.severe(plugin, "VAULT_PLUGIN_MISSING")
            return false
        }
        val rsp = plugin.server.servicesManager.getRegistration(Economy::class.java)
        if (rsp == null) {
            EconomyAudit.severe(plugin, "VAULT_ECONOMY_PROVIDER_MISSING")
            return false
        }
        econ = rsp.provider
        EconomyAudit.info(plugin, "VAULT_PROVIDER_CONNECTED",
            details = arrayOf("provider" to rsp.provider.name))
        return econ != null
    }

    fun getEconomy(): Economy? {
        return econ
    }
}
