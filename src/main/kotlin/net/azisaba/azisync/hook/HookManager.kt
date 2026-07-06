package net.azisaba.azisync.hook

import net.azisaba.azisync.AziSync

class HookManager(private val plugin: AziSync) {

    val economyHook = EconomyHook(plugin)
    val jobsHook = JobsHook(plugin)

    fun registerHooks() {
        val pluginManager = plugin.server.pluginManager

        if (economyHook.setupEconomy()) {
            plugin.logger.info("Hooked into Vault Economy.")
        } else {
            plugin.logger.warning("Vault not found or Economy provider missing. Economy sync will not work.")
        }

        jobsHook.init()

        if (plugin.config.getBoolean("general.economyOptions.ChestShop-Support", false)) {
            if (pluginManager.getPlugin("ChestShop") != null) {
                pluginManager.registerEvents(ChestShopHook(plugin), plugin)
                plugin.logger.info("Hooked into ChestShop.")
            } else {
                plugin.logger.warning("ChestShop-Support is enabled in config, but ChestShop plugin was not found.")
            }
        }

        if (plugin.config.getBoolean("general.economyOptions.CrazyAuctions-Support", false)) {
            if (pluginManager.getPlugin("CrazyAuctions") != null || pluginManager.getPlugin("CrazyAuctionsPlus") != null) {
                pluginManager.registerEvents(CrazyAuctionsHook(plugin), plugin)
                plugin.logger.info("Hooked into CrazyAuctions.")
            } else {
                plugin.logger.warning("CrazyAuctions-Support is enabled in config, but CrazyAuctions plugin was not found.")
            }
        }

        if (plugin.config.getBoolean("general.economyOptions.ShopChest-Support", false)) {
            if (pluginManager.getPlugin("ShopChest") != null) {
                pluginManager.registerEvents(ShopChestHook(plugin), plugin)
                plugin.logger.info("Hooked into ShopChest.")
            } else {
                plugin.logger.warning("ShopChest-Support is enabled in config, but ShopChest plugin was not found.")
            }
        }
    }
}