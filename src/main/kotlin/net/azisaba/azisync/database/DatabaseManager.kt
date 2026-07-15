package net.azisaba.azisync.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.azisaba.azisync.AziSync
import net.azisaba.azisync.database.handler.*
import java.sql.Connection
import java.sql.SQLException

class DatabaseManager(private val plugin: AziSync) {
    private var dataSource: HikariDataSource? = null
    var redisManager: RedisManager? = null

    val isRedis: Boolean = plugin.config.getString("storage.type", "MYSQL")!!.equals("REDIS", ignoreCase = true)

    val inventoryHandler: InventoryStorageHandler by lazy { 
        if (isRedis) net.azisaba.azisync.database.handler.redis.RedisInventoryStorageHandler(plugin) else MySQLInventoryStorageHandler(plugin) 
    }
    val enderchestHandler: EnderchestStorageHandler by lazy { 
        if (isRedis) net.azisaba.azisync.database.handler.redis.RedisEnderchestStorageHandler(plugin) else MySQLEnderchestStorageHandler(plugin) 
    }
    val economyHandler: EconomyStorageHandler by lazy { 
        if (isRedis) net.azisaba.azisync.database.handler.redis.RedisEconomyStorageHandler(plugin) else MySQLEconomyStorageHandler(plugin) 
    }
    val experienceHandler: ExperienceStorageHandler by lazy { 
        if (isRedis) net.azisaba.azisync.database.handler.redis.RedisExperienceStorageHandler(plugin) else MySQLExperienceStorageHandler(plugin) 
    }
    val healthHandler: HealthStorageHandler by lazy { 
        if (isRedis) net.azisaba.azisync.database.handler.redis.RedisHealthStorageHandler(plugin) else MySQLHealthStorageHandler(plugin) 
    }
    val locationHandler: LocationStorageHandler by lazy { 
        if (isRedis) net.azisaba.azisync.database.handler.redis.RedisLocationStorageHandler(plugin) else MySQLLocationStorageHandler(plugin) 
    }
    val potionEffectsHandler: PotionEffectsStorageHandler by lazy { 
        if (isRedis) net.azisaba.azisync.database.handler.redis.RedisPotionEffectsStorageHandler(plugin) else MySQLPotionEffectsStorageHandler(plugin) 
    }
    val advancementHandler: AdvancementStorageHandler by lazy {
        if (isRedis) net.azisaba.azisync.database.handler.redis.RedisAdvancementStorageHandler(plugin) else MySQLAdvancementStorageHandler(plugin)
    }
    val craftGuiHandler: CraftGuiStorageHandler by lazy {
        if (isRedis) net.azisaba.azisync.database.handler.redis.RedisCraftGuiStorageHandler(plugin) else MySQLCraftGuiStorageHandler(plugin)
    }

    init {
        if (isRedis) {
            redisManager = RedisManager(plugin)
        } else {
            setupPool()
            createTables()
        }
    }

    private fun setupPool() {
        val config = plugin.config
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = "jdbc:mysql://${config.getString("database.host")}:${config.getInt("database.port")}/${config.getString("database.databaseName")}"
            username = config.getString("database.username")
            password = config.getString("database.password")
            
            // Optimal HikariCP settings
            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 5000
            idleTimeout = 600000
            maxLifetime = 1800000

            // MySQL optimizations
            addDataSourceProperty("useSSL", config.getBoolean("database.useSSL", false))
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            addDataSourceProperty("useServerPrepStmts", "true")
            addDataSourceProperty("useLocalSessionState", "true")
            addDataSourceProperty("rewriteBatchedStatements", "true")
            addDataSourceProperty("cacheResultSetMetadata", "true")
            addDataSourceProperty("cacheServerConfiguration", "true")
            addDataSourceProperty("elideSetAutoCommits", "true")
            addDataSourceProperty("maintainTimeStats", "false")
        }

        try {
            dataSource = HikariDataSource(hikariConfig)
            plugin.logger.info("Successfully connected to MySQL database via HikariCP.")
        } catch (e: Exception) {
            plugin.logger.severe("Failed to connect to MySQL database.")
            e.printStackTrace()
        }
    }

    fun getConnection(): Connection {
        return dataSource?.connection ?: throw SQLException("DataSource is null or not initialized")
    }

    fun close() {
        if (dataSource != null && !dataSource!!.isClosed) {
            dataSource!!.close()
        }
    }

    private fun createTables() {
        val config = plugin.config
        val inventoryTable = config.getString("database.TablesNames.inventoryTableName", "azisync_inventory")
        val enderChestTable = config.getString("database.TablesNames.enderChestTableName", "azisync_enderchest")
        val experienceTable = config.getString("database.TablesNames.experienceTableName", "azisync_experience")
        val potionTable = config.getString("database.TablesNames.potionEffectsTableName", "azisync_potioneffects")
        val advancementTable = config.getString("database.TablesNames.advancementTableName", "azisync_advancement")
        val healthTable = config.getString("database.TablesNames.healthFoodAirTableName", "azisync_healthfoodair")
        val locationTable = config.getString("database.TablesNames.locationTableName", "azisync_location")
        val economyTable = config.getString("database.TablesNames.economyTableName", "azisync_economy")

        val charSet = "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"

        try {
            getConnection().use { connection ->
                connection.createStatement().use { statement ->
                    
                    // Inventory
                    if (config.getBoolean("general.enableModules.shareInventory") || config.getBoolean("general.enableModules.shareArmor") || config.getBoolean("general.enableModules.shareGameMode")) {
                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS `$inventoryTable` (
                                id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                                player_uuid CHAR(36) UNIQUE NOT NULL,
                                player_name VARCHAR(16) $charSet NOT NULL,
                                inventory LONGTEXT NOT NULL,
                                armor TEXT NOT NULL,
                                hotbar_slot INT(2) NOT NULL DEFAULT 0,
                                gamemode INT(1) NOT NULL DEFAULT 0,
                                sync_complete VARCHAR(5) NOT NULL DEFAULT 'true',
                                last_seen CHAR(13) NOT NULL,
                                PRIMARY KEY(id)
                            );
                            """.trimIndent()
                        )
                    }

                    // EnderChest
                    if (config.getBoolean("general.enableModules.shareEnderChest")) {
                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS `$enderChestTable` (
                                id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                                player_uuid CHAR(36) UNIQUE NOT NULL,
                                player_name VARCHAR(16) $charSet NOT NULL,
                                enderchest LONGTEXT NOT NULL,
                                sync_complete VARCHAR(5) NOT NULL DEFAULT 'true',
                                last_seen CHAR(13) NOT NULL,
                                PRIMARY KEY(id)
                            );
                            """.trimIndent()
                        )
                    }

                    // Experience
                    if (config.getBoolean("general.enableModules.shareExperience")) {
                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS `$experienceTable` (
                                id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                                player_uuid CHAR(36) UNIQUE NOT NULL,
                                player_name VARCHAR(16) $charSet NOT NULL,
                                exp FLOAT(60,30) NOT NULL,
                                exp_to_level INT(10) NOT NULL,
                                total_exp INT(10) NOT NULL,
                                exp_lvl INT(10) NOT NULL,
                                sync_complete VARCHAR(5) NOT NULL DEFAULT 'true',
                                last_seen CHAR(13) NOT NULL,
                                PRIMARY KEY(id)
                            );
                            """.trimIndent()
                        )
                    }

                    // Potion Effects
                    if (config.getBoolean("general.enableModules.sharePotionEffects")) {
                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS `$potionTable` (
                                id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                                player_uuid CHAR(36) UNIQUE NOT NULL,
                                player_name VARCHAR(16) $charSet NOT NULL,
                                potion_effects TEXT NOT NULL,
                                sync_complete VARCHAR(5) NOT NULL DEFAULT 'true',
                                last_seen CHAR(13) NOT NULL,
                                PRIMARY KEY(id)
                            );
                            """.trimIndent()
                        )
                    }

                    // Advancements
                    if (config.getBoolean("general.enableModules.shareAdvancement")) {
                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS `$advancementTable` (
                                id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                                player_uuid CHAR(36) UNIQUE NOT NULL,
                                player_name VARCHAR(16) $charSet NOT NULL,
                                advancements LONGTEXT NOT NULL,
                                sync_complete VARCHAR(5) NOT NULL DEFAULT 'true',
                                last_seen CHAR(13) NOT NULL,
                                PRIMARY KEY(id)
                            );
                            """.trimIndent()
                        )
                    }

                    // Health/Food/Air
                    if (config.getBoolean("general.enableModules.shareHealth") || config.getBoolean("general.enableModules.shareFood") || config.getBoolean("general.enableModules.shareAir")) {
                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS `$healthTable` (
                                id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                                player_uuid CHAR(36) UNIQUE NOT NULL,
                                player_name VARCHAR(16) $charSet NOT NULL,
                                health DOUBLE(10,2) NOT NULL,
                                health_scale DOUBLE(10,2) NOT NULL,
                                max_health DOUBLE(10,2) NOT NULL,
                                food INT(10) NOT NULL,
                                saturation VARCHAR(20) NOT NULL,
                                air INT(10) NOT NULL,
                                max_air INT(10) NOT NULL,
                                sync_complete VARCHAR(5) NOT NULL DEFAULT 'true',
                                last_seen CHAR(13) NOT NULL,
                                PRIMARY KEY(id)
                            );
                            """.trimIndent()
                        )
                    }

                    // Location
                    if (config.getBoolean("general.enableModules.shareLocation")) {
                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS `$locationTable` (
                                id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                                player_uuid CHAR(36) UNIQUE NOT NULL,
                                player_name VARCHAR(16) $charSet NOT NULL,
                                world VARCHAR(32) NOT NULL,
                                x DOUBLE(10,2) NOT NULL,
                                y DOUBLE(10,2) NOT NULL,
                                z DOUBLE(10,2) NOT NULL,
                                yaw FLOAT(5,2) NOT NULL,
                                pitch FLOAT(5,2) NOT NULL,
                                bed_spawn VARCHAR(500) NOT NULL,
                                sync_complete VARCHAR(5) NOT NULL DEFAULT 'true',
                                last_seen CHAR(13) NOT NULL,
                                PRIMARY KEY(id)
                            );
                            """.trimIndent()
                        )
                    }

                    // Economy
                    if (config.getBoolean("general.enableModules.shareEconomy")) {
                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS `$economyTable` (
                                id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                                player_uuid CHAR(36) UNIQUE NOT NULL,
                                player_name VARCHAR(16) $charSet NOT NULL,
                                money DOUBLE(30,2) NOT NULL,
                                offline_money DOUBLE(30,2) NOT NULL,
                                sync_complete VARCHAR(5) NOT NULL DEFAULT 'true',
                                last_seen CHAR(13) NOT NULL,
                                PRIMARY KEY(id)
                            );
                            """.trimIndent()
                        )
                    }

                    // CraftGUI
                    if (config.getBoolean("general.enableModules.shareCraftGui")) {
                        val craftGuiTable = config.getString("database.TablesNames.craftGuiTableName", "azisync_craftgui")
                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS `$craftGuiTable` (
                                id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                                player_uuid CHAR(36) UNIQUE NOT NULL,
                                player_name VARCHAR(16) $charSet NOT NULL,
                                sound_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                                show_result_items BOOLEAN NOT NULL DEFAULT TRUE,
                                craftable_only BOOLEAN NOT NULL DEFAULT FALSE,
                                stash_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                                sync_complete VARCHAR(5) NOT NULL DEFAULT 'true',
                                last_seen CHAR(13) NOT NULL,
                                PRIMARY KEY(id)
                            );
                            """.trimIndent()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("Failed to create tables!")
            e.printStackTrace()
        }
    }
}
