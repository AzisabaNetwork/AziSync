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

    enum class StorageMode {
        SQL,
        HYBRID
    }

    data class SyncState(val status: String, val version: Long)

    val storageMode: StorageMode = resolveStorageMode()

    val inventoryHandler: InventoryStorageHandler by lazy { MySQLInventoryStorageHandler(plugin) }
    val enderchestHandler: EnderchestStorageHandler by lazy { MySQLEnderchestStorageHandler(plugin) }
    val economyHandler: EconomyStorageHandler by lazy { MySQLEconomyStorageHandler(plugin) }
    val experienceHandler: ExperienceStorageHandler by lazy { MySQLExperienceStorageHandler(plugin) }
    val healthHandler: HealthStorageHandler by lazy { MySQLHealthStorageHandler(plugin) }
    val locationHandler: LocationStorageHandler by lazy { MySQLLocationStorageHandler(plugin) }
    val potionEffectsHandler: PotionEffectsStorageHandler by lazy { MySQLPotionEffectsStorageHandler(plugin) }
    val advancementHandler: AdvancementStorageHandler by lazy { MySQLAdvancementStorageHandler(plugin) }
    val craftGuiHandler: CraftGuiStorageHandler by lazy { MySQLCraftGuiStorageHandler(plugin) }

    init {
        setupPool()
        if (!isAvailable()) {
            throw SQLException("Failed to initialize MySQL DataSource")
        }
        createTables()

        if (storageMode == StorageMode.HYBRID) {
            redisManager = RedisManager(plugin)
        }
    }

    private fun resolveStorageMode(): StorageMode {
        return when (val configured = plugin.config.getString("storage.type", "SQL")!!.uppercase()) {
            "SQL", "MYSQL" -> StorageMode.SQL
            "HYBRID" -> StorageMode.HYBRID
            "REDIS" -> {
                plugin.logger.warning("storage.type=REDIS is no longer supported as a standalone mode. Using HYBRID; SQL remains the persistent storage.")
                StorageMode.HYBRID
            }
            else -> {
                plugin.logger.warning("Unknown storage.type '$configured'. Falling back to SQL.")
                StorageMode.SQL
            }
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
        val source = dataSource
        if (source == null || source.isClosed) {
            throw SQLException("DataSource is null, closed, or not initialized")
        }
        return source.connection
    }

    fun isAvailable(): Boolean {
        return dataSource?.let { !it.isClosed } == true
    }

    fun close() {
        redisManager?.close()
        if (dataSource != null && !dataSource!!.isClosed) {
            dataSource!!.close()
        }
        dataSource = null
    }

    fun beginSync(uuid: java.util.UUID, playerName: String): Long {
        val tableName = plugin.config.getString("database.TablesNames.syncStateTableName", "azisync_sync_state")!!
        getConnection().use { connection ->
            connection.autoCommit = false
            try {
                val currentVersion = connection.prepareStatement(
                    "SELECT `version` FROM `$tableName` WHERE `player_uuid` = ? FOR UPDATE"
                ).use { statement ->
                    statement.setString(1, uuid.toString())
                    statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getLong("version") else 0L }
                }
                val version = currentVersion + 1
                connection.prepareStatement(
                    "INSERT INTO `$tableName` (`player_uuid`, `player_name`, `status`, `version`, `updated_at`) VALUES (?, ?, 'saving', ?, ?) " +
                        "ON DUPLICATE KEY UPDATE `player_name` = VALUES(`player_name`), `status` = 'saving', `version` = VALUES(`version`), `updated_at` = VALUES(`updated_at`)"
                ).use { statement ->
                    statement.setString(1, uuid.toString())
                    statement.setString(2, playerName)
                    statement.setLong(3, version)
                    statement.setLong(4, System.currentTimeMillis())
                    statement.executeUpdate()
                }
                connection.commit()
                return version
            } catch (e: SQLException) {
                connection.rollback()
                throw e
            }
        }
    }

    fun completeSync(uuid: java.util.UUID, version: Long): Boolean {
        val tableName = plugin.config.getString("database.TablesNames.syncStateTableName", "azisync_sync_state")!!
        getConnection().use { connection ->
            connection.prepareStatement(
                "UPDATE `$tableName` SET `status` = 'complete', `updated_at` = ? WHERE `player_uuid` = ? AND `version` = ?"
            ).use { statement ->
                statement.setLong(1, System.currentTimeMillis())
                statement.setString(2, uuid.toString())
                statement.setLong(3, version)
                return statement.executeUpdate() == 1
            }
        }
    }

    fun getSyncState(uuid: java.util.UUID): SyncState? {
        val tableName = plugin.config.getString("database.TablesNames.syncStateTableName", "azisync_sync_state")!!
        getConnection().use { connection ->
            connection.prepareStatement("SELECT `status`, `version` FROM `$tableName` WHERE `player_uuid` = ? LIMIT 1").use { statement ->
                statement.setString(1, uuid.toString())
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) return SyncState(resultSet.getString("status"), resultSet.getLong("version"))
                }
            }
        }
        return null
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
                    val syncStateTable = config.getString("database.TablesNames.syncStateTableName", "azisync_sync_state")
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS `$syncStateTable` (
                            player_uuid CHAR(36) NOT NULL,
                            player_name VARCHAR(16) $charSet NOT NULL,
                            status VARCHAR(16) NOT NULL,
                            version BIGINT NOT NULL,
                            updated_at BIGINT NOT NULL,
                            PRIMARY KEY(player_uuid)
                        );
                        """.trimIndent()
                    )

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
                    if (config.getBoolean("general.enableModules.shareLocation") || config.getBoolean("general.enableModules.shareBedSpawn")) {
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
