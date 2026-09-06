package net.azisaba.azisync.migration

import net.azisaba.azisync.AziSync
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean

class MPDBMigrator(private val plugin: AziSync) {

    private val isMigrating = AtomicBoolean(false)

    private val charSet = "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"

    private data class TableModule(
        val id: String,
        val displayName: String,
        val mpdbConfigKey: String,
        val defaultMpdbTable: String,
        val targetConfigKey: String,
        val defaultTargetTable: String,
        val createTargetTableDdl: (tableName: String, charSet: String) -> String,
        val migrateData: (sourceConn: Connection, targetConn: Connection, sourceTable: String, targetTable: String, onProgress: (processed: Int, total: Int) -> Unit) -> Int
    )

    private val modules = listOf(
        TableModule(
            id = "inventory",
            displayName = "Inventory",
            mpdbConfigKey = "migration.mpdb.tables.inventory",
            defaultMpdbTable = "mpdb_inventory",
            targetConfigKey = "database.TablesNames.inventoryTableName",
            defaultTargetTable = "azisync_inventory",
            createTargetTableDdl = { table, cs ->
                """
                CREATE TABLE IF NOT EXISTS `$table` (
                    id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                    player_uuid CHAR(36) UNIQUE NOT NULL,
                    player_name VARCHAR(16) $cs NOT NULL,
                    inventory LONGTEXT NOT NULL,
                    armor TEXT NOT NULL,
                    hotbar_slot INT(2) NOT NULL DEFAULT 0,
                    gamemode INT(1) NOT NULL DEFAULT 0,
                    sync_complete VARCHAR(5) NOT NULL DEFAULT 'true',
                    last_seen CHAR(13) NOT NULL,
                    PRIMARY KEY(id)
                );
                """.trimIndent()
            },
            migrateData = { sourceConn, targetConn, sourceTable, targetTable, onProgress ->
                var processed = 0
                val selectSql = "SELECT player_uuid, player_name, inventory, armor, hotbar_slot, gamemode, sync_complete, last_seen FROM `$sourceTable`"
                val insertSql = """
                    INSERT INTO `$targetTable` (`player_uuid`, `player_name`, `inventory`, `armor`, `hotbar_slot`, `gamemode`, `sync_complete`, `last_seen`)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        `player_name` = VALUES(`player_name`),
                        `inventory` = VALUES(`inventory`),
                        `armor` = VALUES(`armor`),
                        `hotbar_slot` = VALUES(`hotbar_slot`),
                        `gamemode` = VALUES(`gamemode`),
                        `sync_complete` = VALUES(`sync_complete`),
                        `last_seen` = VALUES(`last_seen`)
                """.trimIndent()

                try {
                    sourceConn.prepareStatement(selectSql).use { selectStmt ->
                        selectStmt.fetchSize = 200
                        selectStmt.executeQuery().use { rs ->
                            targetConn.prepareStatement(insertSql).use { insertStmt ->
                                while (rs.next()) {
                                    val uuid = rs.getString("player_uuid") ?: continue
                                    insertStmt.setString(1, uuid)
                                    insertStmt.setString(2, rs.getString("player_name") ?: "")
                                    insertStmt.setString(3, rs.getString("inventory") ?: "")
                                    insertStmt.setString(4, rs.getString("armor") ?: "")
                                    insertStmt.setInt(5, rs.getInt("hotbar_slot"))
                                    insertStmt.setInt(6, rs.getInt("gamemode"))
                                    insertStmt.setString(7, rs.getString("sync_complete") ?: "true")
                                    insertStmt.setString(8, rs.getString("last_seen") ?: System.currentTimeMillis().toString())
                                    insertStmt.addBatch()
                                    processed++

                                    if (processed % 200 == 0) {
                                        insertStmt.executeBatch()
                                        targetConn.commit()
                                        onProgress(processed, -1)
                                    }
                                }
                                insertStmt.executeBatch()
                                targetConn.commit()
                            }
                        }
                    }
                } catch (e: Exception) {
                    try { targetConn.rollback() } catch (_: Exception) {}
                    throw e
                }
                processed
            }
        ),
        TableModule(
            id = "enderchest",
            displayName = "EnderChest",
            mpdbConfigKey = "migration.mpdb.tables.enderchest",
            defaultMpdbTable = "mpdb_enderchest",
            targetConfigKey = "database.TablesNames.enderChestTableName",
            defaultTargetTable = "azisync_enderchest",
            createTargetTableDdl = { table, cs ->
                """
                CREATE TABLE IF NOT EXISTS `$table` (
                    id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                    player_uuid CHAR(36) UNIQUE NOT NULL,
                    player_name VARCHAR(16) $cs NOT NULL,
                    enderchest LONGTEXT NOT NULL,
                    sync_complete VARCHAR(5) NOT NULL DEFAULT 'true',
                    last_seen CHAR(13) NOT NULL,
                    PRIMARY KEY(id)
                );
                """.trimIndent()
            },
            migrateData = { sourceConn, targetConn, sourceTable, targetTable, onProgress ->
                var processed = 0
                val selectSql = "SELECT player_uuid, player_name, enderchest, sync_complete, last_seen FROM `$sourceTable`"
                val insertSql = """
                    INSERT INTO `$targetTable` (`player_uuid`, `player_name`, `enderchest`, `sync_complete`, `last_seen`)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        `player_name` = VALUES(`player_name`),
                        `enderchest` = VALUES(`enderchest`),
                        `sync_complete` = VALUES(`sync_complete`),
                        `last_seen` = VALUES(`last_seen`)
                """.trimIndent()

                try {
                    sourceConn.prepareStatement(selectSql).use { selectStmt ->
                        selectStmt.fetchSize = 200
                        selectStmt.executeQuery().use { rs ->
                            targetConn.prepareStatement(insertSql).use { insertStmt ->
                                while (rs.next()) {
                                    val uuid = rs.getString("player_uuid") ?: continue
                                    insertStmt.setString(1, uuid)
                                    insertStmt.setString(2, rs.getString("player_name") ?: "")
                                    insertStmt.setString(3, rs.getString("enderchest") ?: "")
                                    insertStmt.setString(4, rs.getString("sync_complete") ?: "true")
                                    insertStmt.setString(5, rs.getString("last_seen") ?: System.currentTimeMillis().toString())
                                    insertStmt.addBatch()
                                    processed++

                                    if (processed % 200 == 0) {
                                        insertStmt.executeBatch()
                                        targetConn.commit()
                                        onProgress(processed, -1)
                                    }
                                }
                                insertStmt.executeBatch()
                                targetConn.commit()
                            }
                        }
                    }
                } catch (e: Exception) {
                    try { targetConn.rollback() } catch (_: Exception) {}
                    throw e
                }
                processed
            }
        ),
        TableModule(
            id = "experience",
            displayName = "Experience",
            mpdbConfigKey = "migration.mpdb.tables.experience",
            defaultMpdbTable = "mpdb_experience",
            targetConfigKey = "database.TablesNames.experienceTableName",
            defaultTargetTable = "azisync_experience",
            createTargetTableDdl = { table, cs ->
                """
                CREATE TABLE IF NOT EXISTS `$table` (
                    id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                    player_uuid CHAR(36) UNIQUE NOT NULL,
                    player_name VARCHAR(16) $cs NOT NULL,
                    exp FLOAT(60,30) NOT NULL,
                    exp_to_level INT(10) NOT NULL,
                    total_exp INT(10) NOT NULL,
                    exp_lvl INT(10) NOT NULL,
                    sync_complete VARCHAR(5) NOT NULL DEFAULT 'true',
                    last_seen CHAR(13) NOT NULL,
                    PRIMARY KEY(id)
                );
                """.trimIndent()
            },
            migrateData = { sourceConn, targetConn, sourceTable, targetTable, onProgress ->
                var processed = 0
                val selectSql = "SELECT player_uuid, player_name, exp, exp_to_level, total_exp, exp_lvl, sync_complete, last_seen FROM `$sourceTable`"
                val insertSql = """
                    INSERT INTO `$targetTable` (`player_uuid`, `player_name`, `exp`, `exp_to_level`, `total_exp`, `exp_lvl`, `sync_complete`, `last_seen`)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        `player_name` = VALUES(`player_name`),
                        `exp` = VALUES(`exp`),
                        `exp_to_level` = VALUES(`exp_to_level`),
                        `total_exp` = VALUES(`total_exp`),
                        `exp_lvl` = VALUES(`exp_lvl`),
                        `sync_complete` = VALUES(`sync_complete`),
                        `last_seen` = VALUES(`last_seen`)
                """.trimIndent()

                try {
                    sourceConn.prepareStatement(selectSql).use { selectStmt ->
                        selectStmt.fetchSize = 200
                        selectStmt.executeQuery().use { rs ->
                            targetConn.prepareStatement(insertSql).use { insertStmt ->
                                while (rs.next()) {
                                    val uuid = rs.getString("player_uuid") ?: continue
                                    insertStmt.setString(1, uuid)
                                    insertStmt.setString(2, rs.getString("player_name") ?: "")
                                    insertStmt.setFloat(3, rs.getFloat("exp"))
                                    insertStmt.setInt(4, rs.getInt("exp_to_level"))
                                    insertStmt.setInt(5, rs.getInt("total_exp"))
                                    insertStmt.setInt(6, rs.getInt("exp_lvl"))
                                    insertStmt.setString(7, rs.getString("sync_complete") ?: "true")
                                    insertStmt.setString(8, rs.getString("last_seen") ?: System.currentTimeMillis().toString())
                                    insertStmt.addBatch()
                                    processed++

                                    if (processed % 200 == 0) {
                                        insertStmt.executeBatch()
                                        targetConn.commit()
                                        onProgress(processed, -1)
                                    }
                                }
                                insertStmt.executeBatch()
                                targetConn.commit()
                            }
                        }
                    }
                } catch (e: Exception) {
                    try { targetConn.rollback() } catch (_: Exception) {}
                    throw e
                }
                processed
            }
        ),
        TableModule(
            id = "potionEffects",
            displayName = "PotionEffects",
            mpdbConfigKey = "migration.mpdb.tables.potionEffects",
            defaultMpdbTable = "mpdb_potionEffects",
            targetConfigKey = "database.TablesNames.potionEffectsTableName",
            defaultTargetTable = "azisync_potioneffects",
            createTargetTableDdl = { table, cs ->
                """
                CREATE TABLE IF NOT EXISTS `$table` (
                    id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                    player_uuid CHAR(36) UNIQUE NOT NULL,
                    player_name VARCHAR(16) $cs NOT NULL,
                    potion_effects TEXT NOT NULL,
                    sync_complete VARCHAR(5) NOT NULL DEFAULT 'true',
                    last_seen CHAR(13) NOT NULL,
                    PRIMARY KEY(id)
                );
                """.trimIndent()
            },
            migrateData = { sourceConn, targetConn, sourceTable, targetTable, onProgress ->
                var processed = 0
                val selectSql = "SELECT player_uuid, player_name, potion_effects, sync_complete, last_seen FROM `$sourceTable`"
                val insertSql = """
                    INSERT INTO `$targetTable` (`player_uuid`, `player_name`, `potion_effects`, `sync_complete`, `last_seen`)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        `player_name` = VALUES(`player_name`),
                        `potion_effects` = VALUES(`potion_effects`),
                        `sync_complete` = VALUES(`sync_complete`),
                        `last_seen` = VALUES(`last_seen`)
                """.trimIndent()

                try {
                    sourceConn.prepareStatement(selectSql).use { selectStmt ->
                        selectStmt.fetchSize = 200
                        selectStmt.executeQuery().use { rs ->
                            targetConn.prepareStatement(insertSql).use { insertStmt ->
                                while (rs.next()) {
                                    val uuid = rs.getString("player_uuid") ?: continue
                                    insertStmt.setString(1, uuid)
                                    insertStmt.setString(2, rs.getString("player_name") ?: "")
                                    insertStmt.setString(3, rs.getString("potion_effects") ?: "")
                                    insertStmt.setString(4, rs.getString("sync_complete") ?: "true")
                                    insertStmt.setString(5, rs.getString("last_seen") ?: System.currentTimeMillis().toString())
                                    insertStmt.addBatch()
                                    processed++

                                    if (processed % 200 == 0) {
                                        insertStmt.executeBatch()
                                        targetConn.commit()
                                        onProgress(processed, -1)
                                    }
                                }
                                insertStmt.executeBatch()
                                targetConn.commit()
                            }
                        }
                    }
                } catch (e: Exception) {
                    try { targetConn.rollback() } catch (_: Exception) {}
                    throw e
                }
                processed
            }
        ),
        TableModule(
            id = "healthFoodAir",
            displayName = "HealthFoodAir",
            mpdbConfigKey = "migration.mpdb.tables.healthFoodAir",
            defaultMpdbTable = "mpdb_health_food_air",
            targetConfigKey = "database.TablesNames.healthFoodAirTableName",
            defaultTargetTable = "azisync_healthfoodair",
            createTargetTableDdl = { table, cs ->
                """
                CREATE TABLE IF NOT EXISTS `$table` (
                    id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                    player_uuid CHAR(36) UNIQUE NOT NULL,
                    player_name VARCHAR(16) $cs NOT NULL,
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
            },
            migrateData = { sourceConn, targetConn, sourceTable, targetTable, onProgress ->
                var processed = 0
                val selectSql = "SELECT player_uuid, player_name, health, health_scale, max_health, food, saturation, air, max_air, sync_complete, last_seen FROM `$sourceTable`"
                val insertSql = """
                    INSERT INTO `$targetTable` (`player_uuid`, `player_name`, `health`, `health_scale`, `max_health`, `food`, `saturation`, `air`, `max_air`, `sync_complete`, `last_seen`)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        `player_name` = VALUES(`player_name`),
                        `health` = VALUES(`health`),
                        `health_scale` = VALUES(`health_scale`),
                        `max_health` = VALUES(`max_health`),
                        `food` = VALUES(`food`),
                        `saturation` = VALUES(`saturation`),
                        `air` = VALUES(`air`),
                        `max_air` = VALUES(`max_air`),
                        `sync_complete` = VALUES(`sync_complete`),
                        `last_seen` = VALUES(`last_seen`)
                """.trimIndent()

                try {
                    sourceConn.prepareStatement(selectSql).use { selectStmt ->
                        selectStmt.fetchSize = 200
                        selectStmt.executeQuery().use { rs ->
                            targetConn.prepareStatement(insertSql).use { insertStmt ->
                                while (rs.next()) {
                                    val uuid = rs.getString("player_uuid") ?: continue
                                    insertStmt.setString(1, uuid)
                                    insertStmt.setString(2, rs.getString("player_name") ?: "")
                                    insertStmt.setDouble(3, rs.getDouble("health"))
                                    insertStmt.setDouble(4, rs.getDouble("health_scale"))
                                    insertStmt.setDouble(5, rs.getDouble("max_health"))
                                    insertStmt.setInt(6, rs.getInt("food"))
                                    insertStmt.setString(7, rs.getString("saturation") ?: "5.0")
                                    insertStmt.setInt(8, rs.getInt("air"))
                                    insertStmt.setInt(9, rs.getInt("max_air"))
                                    insertStmt.setString(10, rs.getString("sync_complete") ?: "true")
                                    insertStmt.setString(11, rs.getString("last_seen") ?: System.currentTimeMillis().toString())
                                    insertStmt.addBatch()
                                    processed++

                                    if (processed % 200 == 0) {
                                        insertStmt.executeBatch()
                                        targetConn.commit()
                                        onProgress(processed, -1)
                                    }
                                }
                                insertStmt.executeBatch()
                                targetConn.commit()
                            }
                        }
                    }
                } catch (e: Exception) {
                    try { targetConn.rollback() } catch (_: Exception) {}
                    throw e
                }
                processed
            }
        ),
        TableModule(
            id = "location",
            displayName = "Location",
            mpdbConfigKey = "migration.mpdb.tables.location",
            defaultMpdbTable = "mpdb_location",
            targetConfigKey = "database.TablesNames.locationTableName",
            defaultTargetTable = "azisync_location",
            createTargetTableDdl = { table, cs ->
                """
                CREATE TABLE IF NOT EXISTS `$table` (
                    id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                    player_uuid CHAR(36) UNIQUE NOT NULL,
                    player_name VARCHAR(16) $cs NOT NULL,
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
            },
            migrateData = { sourceConn, targetConn, sourceTable, targetTable, onProgress ->
                var processed = 0
                val selectSql = "SELECT player_uuid, player_name, world, x, y, z, yaw, pitch, bed_spawn, sync_complete, last_seen FROM `$sourceTable`"
                val insertSql = """
                    INSERT INTO `$targetTable` (`player_uuid`, `player_name`, `world`, `x`, `y`, `z`, `yaw`, `pitch`, `bed_spawn`, `sync_complete`, `last_seen`)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        `player_name` = VALUES(`player_name`),
                        `world` = VALUES(`world`),
                        `x` = VALUES(`x`),
                        `y` = VALUES(`y`),
                        `z` = VALUES(`z`),
                        `yaw` = VALUES(`yaw`),
                        `pitch` = VALUES(`pitch`),
                        `bed_spawn` = VALUES(`bed_spawn`),
                        `sync_complete` = VALUES(`sync_complete`),
                        `last_seen` = VALUES(`last_seen`)
                """.trimIndent()

                try {
                    sourceConn.prepareStatement(selectSql).use { selectStmt ->
                        selectStmt.fetchSize = 200
                        selectStmt.executeQuery().use { rs ->
                            targetConn.prepareStatement(insertSql).use { insertStmt ->
                                while (rs.next()) {
                                    val uuid = rs.getString("player_uuid") ?: continue
                                    insertStmt.setString(1, uuid)
                                    insertStmt.setString(2, rs.getString("player_name") ?: "")
                                    insertStmt.setString(3, rs.getString("world") ?: "world")
                                    insertStmt.setDouble(4, rs.getDouble("x"))
                                    insertStmt.setDouble(5, rs.getDouble("y"))
                                    insertStmt.setDouble(6, rs.getDouble("z"))
                                    insertStmt.setFloat(7, rs.getFloat("yaw"))
                                    insertStmt.setFloat(8, rs.getFloat("pitch"))
                                    insertStmt.setString(9, rs.getString("bed_spawn") ?: "")
                                    insertStmt.setString(10, rs.getString("sync_complete") ?: "true")
                                    insertStmt.setString(11, rs.getString("last_seen") ?: System.currentTimeMillis().toString())
                                    insertStmt.addBatch()
                                    processed++

                                    if (processed % 200 == 0) {
                                        insertStmt.executeBatch()
                                        targetConn.commit()
                                        onProgress(processed, -1)
                                    }
                                }
                                insertStmt.executeBatch()
                                targetConn.commit()
                            }
                        }
                    }
                } catch (e: Exception) {
                    try { targetConn.rollback() } catch (_: Exception) {}
                    throw e
                }
                processed
            }
        ),
        TableModule(
            id = "economy",
            displayName = "Economy",
            mpdbConfigKey = "migration.mpdb.tables.economy",
            defaultMpdbTable = "mpdb_economy",
            targetConfigKey = "database.TablesNames.economyTableName",
            defaultTargetTable = "azisync_economy",
            createTargetTableDdl = { table, cs ->
                """
                CREATE TABLE IF NOT EXISTS `$table` (
                    id INT UNSIGNED NOT NULL AUTO_INCREMENT,
                    player_uuid CHAR(36) UNIQUE NOT NULL,
                    player_name VARCHAR(16) $cs NOT NULL,
                    money DOUBLE(30,2) NOT NULL,
                    offline_money DOUBLE(30,2) NOT NULL,
                    sync_complete VARCHAR(5) NOT NULL DEFAULT 'true',
                    last_seen CHAR(13) NOT NULL,
                    PRIMARY KEY(id)
                );
                """.trimIndent()
            },
            migrateData = { sourceConn, targetConn, sourceTable, targetTable, onProgress ->
                var processed = 0
                val selectSql = "SELECT player_uuid, player_name, money, offline_money, sync_complete, last_seen FROM `$sourceTable`"
                val insertSql = """
                    INSERT INTO `$targetTable` (`player_uuid`, `player_name`, `money`, `offline_money`, `sync_complete`, `last_seen`)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        `player_name` = VALUES(`player_name`),
                        `money` = VALUES(`money`),
                        `offline_money` = VALUES(`offline_money`),
                        `sync_complete` = VALUES(`sync_complete`),
                        `last_seen` = VALUES(`last_seen`)
                """.trimIndent()

                try {
                    sourceConn.prepareStatement(selectSql).use { selectStmt ->
                        selectStmt.fetchSize = 200
                        selectStmt.executeQuery().use { rs ->
                            targetConn.prepareStatement(insertSql).use { insertStmt ->
                                while (rs.next()) {
                                    val uuid = rs.getString("player_uuid") ?: continue
                                    insertStmt.setString(1, uuid)
                                    insertStmt.setString(2, rs.getString("player_name") ?: "")
                                    insertStmt.setDouble(3, rs.getDouble("money"))
                                    insertStmt.setDouble(4, rs.getDouble("offline_money"))
                                    insertStmt.setString(5, rs.getString("sync_complete") ?: "true")
                                    insertStmt.setString(6, rs.getString("last_seen") ?: System.currentTimeMillis().toString())
                                    insertStmt.addBatch()
                                    processed++

                                    if (processed % 200 == 0) {
                                        insertStmt.executeBatch()
                                        targetConn.commit()
                                        onProgress(processed, -1)
                                    }
                                }
                                insertStmt.executeBatch()
                                targetConn.commit()
                            }
                        }
                    }
                } catch (e: Exception) {
                    try { targetConn.rollback() } catch (_: Exception) {}
                    throw e
                }
                processed
            }
        )
    )

    private fun getSourceConnection(): Connection {
        val config = plugin.config
        val useMain = config.getBoolean("migration.mpdb.useMainDatabase", true)
        if (useMain) {
            return plugin.databaseManager.getConnection()
        }
        val host = config.getString("migration.mpdb.host", "localhost")
        val port = config.getInt("migration.mpdb.port", 3306)
        val dbName = config.getString("migration.mpdb.database", "mpdb")
        val username = config.getString("migration.mpdb.username", "azisync")
        val password = config.getString("migration.mpdb.password", "password")
        val useSSL = config.getBoolean("migration.mpdb.useSSL", false)

        try {
            Class.forName("com.mysql.cj.jdbc.Driver")
        } catch (_: ClassNotFoundException) {
            try {
                Class.forName("com.mysql.jdbc.Driver")
            } catch (_: ClassNotFoundException) {}
        }

        val url = "jdbc:mysql://$host:$port/$dbName?useSSL=$useSSL&allowPublicKeyRetrieval=true&characterEncoding=utf8&rewriteBatchedStatements=true"
        return DriverManager.getConnection(url, username, password)
    }

    private fun findActualTableName(conn: Connection, configuredName: String): String? {
        try {
            conn.prepareStatement("SELECT 1 FROM `$configuredName` LIMIT 1").use { stmt ->
                stmt.executeQuery().use { /* valid */ }
            }
            return configuredName
        } catch (_: SQLException) {}

        try {
            val md = conn.metaData
            md.getTables(conn.catalog, null, "%", arrayOf("TABLE")).use { rs ->
                while (rs.next()) {
                    val actualName = rs.getString("TABLE_NAME")
                    if (actualName.equals(configuredName, ignoreCase = true)) {
                        return actualName
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun getRecordCount(conn: Connection, tableName: String): Int {
        try {
            conn.prepareStatement("SELECT COUNT(*) FROM `$tableName`").use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) return rs.getInt(1)
                }
            }
        } catch (_: Exception) {}
        return 0
    }

    fun scan(sender: CommandSender) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            plugin.messageManager.sendMessage(sender, "migrate_scanning")
            try {
                getSourceConnection().use { conn ->
                    plugin.messageManager.sendMessage(sender, "migrate_scan_header")
                    var foundCount = 0
                    for (module in modules) {
                        val configuredName = plugin.config.getString(module.mpdbConfigKey, module.defaultMpdbTable) ?: module.defaultMpdbTable
                        val actualName = findActualTableName(conn, configuredName)
                        if (actualName != null) {
                            val count = getRecordCount(conn, actualName)
                            plugin.messageManager.sendMessage(
                                sender,
                                "migrate_scan_found",
                                mapOf(
                                    "{module}" to module.displayName,
                                    "{table}" to actualName,
                                    "{count}" to count.toString()
                                )
                            )
                            foundCount++
                        } else {
                            plugin.messageManager.sendMessage(
                                sender,
                                "migrate_scan_not_found",
                                mapOf(
                                    "{module}" to module.displayName,
                                    "{table}" to configuredName
                                )
                            )
                        }
                    }
                    if (foundCount > 0) {
                        plugin.messageManager.sendMessage(sender, "migrate_confirm_prompt")
                    }
                }
            } catch (e: Exception) {
                plugin.logger.severe("Error scanning MPDB database: ${e.message}")
                e.printStackTrace()
                plugin.messageManager.sendMessage(
                    sender,
                    "migrate_failed",
                    mapOf("{error}" to (e.message ?: e.toString()))
                )
            }
        })
    }

    fun migrate(sender: CommandSender) {
        if (!isMigrating.compareAndSet(false, true)) {
            plugin.messageManager.sendMessage(sender, "migrate_already_running")
            return
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                plugin.messageManager.sendMessage(sender, "migrate_started")
                plugin.logger.info("Starting MPDB data migration...")

                var totalMigrated = 0

                getSourceConnection().use { sourceConn ->
                    plugin.databaseManager.getConnection().use { targetConn ->
                        try {
                            targetConn.autoCommit = false

                            for (module in modules) {
                                val configuredName = plugin.config.getString(module.mpdbConfigKey, module.defaultMpdbTable) ?: module.defaultMpdbTable
                                val actualSourceTable = findActualTableName(sourceConn, configuredName) ?: continue
                                val targetTable = plugin.config.getString(module.targetConfigKey, module.defaultTargetTable) ?: module.defaultTargetTable

                                val countInSource = getRecordCount(sourceConn, actualSourceTable)
                                if (countInSource == 0) continue

                                // Ensure target table exists
                                targetConn.createStatement().use { stmt ->
                                    stmt.execute(module.createTargetTableDdl(targetTable, charSet))
                                }
                                targetConn.commit()

                                plugin.logger.info("Migrating ${module.displayName} ($actualSourceTable -> $targetTable): $countInSource records...")

                                val migrated = module.migrateData(sourceConn, targetConn, actualSourceTable, targetTable) { processed, _ ->
                                    if (countInSource >= 500 && processed % 500 == 0) {
                                        plugin.messageManager.sendMessage(
                                            sender,
                                            "migrate_progress",
                                            mapOf(
                                                "{module}" to module.displayName,
                                                "{count}" to processed.toString(),
                                                "{total}" to countInSource.toString()
                                            )
                                        )
                                    }
                                }

                                totalMigrated += migrated
                                plugin.messageManager.sendMessage(
                                    sender,
                                    "migrate_table_success",
                                    mapOf(
                                        "{module}" to module.displayName,
                                        "{count}" to migrated.toString()
                                    )
                                )
                                plugin.logger.info("Migrated ${module.displayName}: $migrated records.")
                            }
                        } finally {
                            try {
                                if (!targetConn.isClosed) {
                                    targetConn.autoCommit = true
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                plugin.messageManager.sendMessage(
                    sender,
                    "migrate_completed",
                    mapOf("{total}" to totalMigrated.toString())
                )
                plugin.logger.info("MPDB data migration completed successfully! Total records: $totalMigrated")
            } catch (e: Exception) {
                plugin.logger.severe("MPDB data migration failed: ${e.message}")
                e.printStackTrace()
                plugin.messageManager.sendMessage(
                    sender,
                    "migrate_failed",
                    mapOf("{error}" to (e.message ?: e.toString()))
                )
            } finally {
                isMigrating.set(false)
            }
        })
    }
}
