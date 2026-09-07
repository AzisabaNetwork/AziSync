package net.azisaba.azisync.migration

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.util.ItemSerializer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean

class MPDBMigrator(private val plugin: AziSync) {

    private val isMigrating = AtomicBoolean(false)
    private val charSet = "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"

    private fun convertItemFormat(raw: String, defaultSize: Int): String {
        if (raw.isBlank() || raw == "none") return "none"
        return try {
            val items = ItemSerializer.fromBase64(raw, defaultSize)
            ItemSerializer.toBase64(items)
        } catch (e: Exception) {
            plugin.logger.warning("[AziSync] Failed to re-encode item data during migration, saving original: ${e.message}")
            raw
        }
    }

    companion object {
        fun normalizeUuid(raw: String?): String? {
            if (raw == null) return null
            val clean = raw.trim().lowercase()
            return if (clean.length == 32 && !clean.contains("-")) {
                "${clean.substring(0, 8)}-${clean.substring(8, 12)}-${clean.substring(12, 16)}-${clean.substring(16, 20)}-${clean.substring(20, 32)}"
            } else {
                clean
            }
        }
    }

    private data class TableModule(
        val id: String,
        val displayName: String,
        val mpdbConfigKey: String,
        val mpdbPropertyKey: String,
        val defaultMpdbTable: String,
        val targetConfigKey: String,
        val defaultTargetTable: String,
        val createTargetTableDdl: (tableName: String, charSet: String) -> String,
        val migrateData: (sourceConn: Connection, targetConn: Connection, sourceTable: String, targetTable: String, onProgress: (processed: Int, total: Int) -> Unit) -> Int
    )

    data class SourceConnectionInfo(
        val useMainDatabase: Boolean,
        val description: String,
        val host: String = "",
        val port: Int = 3306,
        val database: String = "",
        val username: String = "",
        val password: String = "",
        val useSSL: Boolean = false,
        val mpdbYaml: YamlConfiguration? = null
    )

    private val modules = listOf(
        TableModule(
            id = "inventory",
            displayName = "Inventory",
            mpdbConfigKey = "migration.mpdb.tables.inventory",
            mpdbPropertyKey = "inventoryTableName",
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
                                    val rawUuid = rs.getString("player_uuid") ?: continue
                                    val uuid = normalizeUuid(rawUuid) ?: continue
                                    insertStmt.setString(1, uuid)
                                    insertStmt.setString(2, rs.getString("player_name") ?: "")
                                    insertStmt.setString(3, convertItemFormat(rs.getString("inventory") ?: "", 36))
                                    insertStmt.setString(4, convertItemFormat(rs.getString("armor") ?: "", 4))
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
            mpdbPropertyKey = "enderchestTableName",
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
                                    val rawUuid = rs.getString("player_uuid") ?: continue
                                    val uuid = normalizeUuid(rawUuid) ?: continue
                                    insertStmt.setString(1, uuid)
                                    insertStmt.setString(2, rs.getString("player_name") ?: "")
                                    insertStmt.setString(3, convertItemFormat(rs.getString("enderchest") ?: "", 27))
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
            mpdbPropertyKey = "experienceTableName",
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
                                    val rawUuid = rs.getString("player_uuid") ?: continue
                                    val uuid = normalizeUuid(rawUuid) ?: continue
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
            mpdbPropertyKey = "potionEffectsTableName",
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
                                    val rawUuid = rs.getString("player_uuid") ?: continue
                                    val uuid = normalizeUuid(rawUuid) ?: continue
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
            mpdbPropertyKey = "healthFoodAirTableName",
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
                                    val rawUuid = rs.getString("player_uuid") ?: continue
                                    val uuid = normalizeUuid(rawUuid) ?: continue
                                    insertStmt.setString(1, uuid)
                                    insertStmt.setString(2, rs.getString("player_name") ?: "")
                                    insertStmt.setDouble(3, rs.getDouble("health"))
                                    val healthScale = rs.getDouble("health_scale")
                                    insertStmt.setDouble(4, if (healthScale > 0.0) healthScale else 20.0)
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
            mpdbPropertyKey = "locationTableName",
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
                                    val rawUuid = rs.getString("player_uuid") ?: continue
                                    val uuid = normalizeUuid(rawUuid) ?: continue
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
            mpdbPropertyKey = "economyTableName",
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
                val hasOfflineMoney = runCatching {
                    sourceConn.metaData.getColumns(null, null, sourceTable, "offline_money").use { it.next() }
                }.getOrDefault(false)
                val selectSql = if (hasOfflineMoney) {
                    "SELECT player_uuid, player_name, money, offline_money, sync_complete, last_seen FROM `$sourceTable`"
                } else {
                    "SELECT player_uuid, player_name, money, sync_complete, last_seen FROM `$sourceTable`"
                }
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
                                    val rawUuid = rs.getString("player_uuid") ?: continue
                                    val uuid = normalizeUuid(rawUuid) ?: continue
                                    insertStmt.setString(1, uuid)
                                    insertStmt.setString(2, rs.getString("player_name") ?: "")
                                    insertStmt.setDouble(3, rs.getDouble("money"))
                                    insertStmt.setDouble(4, if (hasOfflineMoney) rs.getDouble("offline_money") else 0.0)
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
                                // Clean up old unhyphenated 32-character records from targetTable
                                try {
                                    targetConn.createStatement().use { cleanStmt ->
                                        cleanStmt.executeUpdate("DELETE FROM `$targetTable` WHERE LENGTH(`player_uuid`) = 32")
                                    }
                                    targetConn.commit()
                                } catch (_: Exception) {}
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

    fun resolveSourceInfo(): SourceConnectionInfo {
        val config = plugin.config
        val mpdbConfigFile = File(plugin.dataFolder.parentFile, "MysqlPlayerDataBridge/config.yml")
        val mpdbYaml = if (mpdbConfigFile.exists()) {
            try {
                YamlConfiguration.loadConfiguration(mpdbConfigFile)
            } catch (_: Exception) {
                null
            }
        } else null

        val autoDetect = config.getBoolean("migration.mpdb.autoDetectFromPlugin", true)
        val hasManualConfig = config.contains("migration.mpdb.host")
        val explicitlyDisabledMain = config.contains("migration.mpdb.useMainDatabase") && !config.getBoolean("migration.mpdb.useMainDatabase")

        // 1. If manual host is configured in AziSync and autoDetect is disabled or no mpdb config exists
        if (hasManualConfig && (!autoDetect || mpdbYaml == null)) {
            val host = config.getString("migration.mpdb.host", "localhost")!!
            val port = config.getInt("migration.mpdb.port", 3306)
            val dbName = config.getString("migration.mpdb.database")
                ?: config.getString("migration.mpdb.databaseName", "mpdb")!!
            val username = config.getString("migration.mpdb.username")
                ?: config.getString("migration.mpdb.user", "azisync")!!
            val password = config.getString("migration.mpdb.password", "password")!!
            val useSSL = config.getBoolean("migration.mpdb.useSSL", config.getBoolean("migration.mpdb.sslEnabled", false))
            return SourceConnectionInfo(
                useMainDatabase = false,
                description = "$dbName@$host:$port (AziSync config)",
                host = host,
                port = port,
                database = dbName,
                username = username,
                password = password,
                useSSL = useSSL,
                mpdbYaml = mpdbYaml
            )
        }

        // 2. Auto-detect from plugins/MysqlPlayerDataBridge/config.yml
        if (autoDetect && mpdbYaml != null && mpdbYaml.contains("database.mysql.host")) {
            val host = mpdbYaml.getString("database.mysql.host", "localhost")!!
            val port = mpdbYaml.getInt("database.mysql.port", 3306)
            val dbName = mpdbYaml.getString("database.mysql.databaseName", "life_userdata")!!
            val username = mpdbYaml.getString("database.mysql.user", "life_userdata")!!
            val password = mpdbYaml.getString("database.mysql.password", "")!!
            val useSSL = mpdbYaml.getBoolean("database.mysql.sslEnabled", false)
            return SourceConnectionInfo(
                useMainDatabase = false,
                description = "$dbName@$host:$port (Auto-detected from MysqlPlayerDataBridge)",
                host = host,
                port = port,
                database = dbName,
                username = username,
                password = password,
                useSSL = useSSL,
                mpdbYaml = mpdbYaml
            )
        }

        // 3. Fallback to manual host in AziSync if present
        if (hasManualConfig || explicitlyDisabledMain) {
            val host = config.getString("migration.mpdb.host", "localhost")!!
            val port = config.getInt("migration.mpdb.port", 3306)
            val dbName = config.getString("migration.mpdb.database")
                ?: config.getString("migration.mpdb.databaseName", "mpdb")!!
            val username = config.getString("migration.mpdb.username")
                ?: config.getString("migration.mpdb.user", "azisync")!!
            val password = config.getString("migration.mpdb.password", "password")!!
            val useSSL = config.getBoolean("migration.mpdb.useSSL", config.getBoolean("migration.mpdb.sslEnabled", false))
            return SourceConnectionInfo(
                useMainDatabase = false,
                description = "$dbName@$host:$port (AziSync config)",
                host = host,
                port = port,
                database = dbName,
                username = username,
                password = password,
                useSSL = useSSL,
                mpdbYaml = mpdbYaml
            )
        }

        // 4. Fallback to AziSync Main Database
        val mainDbName = config.getString("database.databaseName", "azisync")!!
        val mainHost = config.getString("database.host", "localhost")!!
        return SourceConnectionInfo(
            useMainDatabase = true,
            description = "$mainDbName@$mainHost (AziSync Main Database)",
            mpdbYaml = mpdbYaml
        )
    }

    private fun getConfiguredMpdbTable(module: TableModule, mpdbYaml: YamlConfiguration?): String {
        // 1. AziSync config: migration.mpdb.tables.<id>
        plugin.config.getString(module.mpdbConfigKey)?.let { return it }
        // 2. AziSync config: migration.mpdb.TablesNames.<mpdbPropertyKey>
        plugin.config.getString("migration.mpdb.TablesNames.${module.mpdbPropertyKey}")?.let { return it }
        // 3. MPDB config file: database.mysql.TablesNames.<mpdbPropertyKey>
        mpdbYaml?.getString("database.mysql.TablesNames.${module.mpdbPropertyKey}")?.let { return it }
        // 4. Default
        return module.defaultMpdbTable
    }

    fun getSourceConnection(info: SourceConnectionInfo): Connection {
        if (info.useMainDatabase) {
            return plugin.databaseManager.getConnection()
        }

        try {
            Class.forName("com.mysql.cj.jdbc.Driver")
        } catch (_: ClassNotFoundException) {
            try {
                Class.forName("com.mysql.jdbc.Driver")
            } catch (_: ClassNotFoundException) {}
        }

        val url = "jdbc:mysql://${info.host}:${info.port}/${info.database}?useSSL=${info.useSSL}&allowPublicKeyRetrieval=true&characterEncoding=utf8&rewriteBatchedStatements=true"
        return DriverManager.getConnection(url, info.username, info.password)
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

    fun scan(sender: CommandSender, targetModuleId: String? = null) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val sourceInfo = resolveSourceInfo()
            plugin.messageManager.sendMessage(sender, "migrate_scanning", mapOf("{target}" to sourceInfo.description))
            try {
                val targetModules = if (targetModuleId != null) {
                    val matched = modules.filter { it.id.equals(targetModuleId, ignoreCase = true) }
                    if (matched.isEmpty()) {
                        sender.sendMessage("§cUnknown module: $targetModuleId. Available: ${modules.joinToString { it.id }}")
                        return@Runnable
                    }
                    matched
                } else {
                    modules
                }

                getSourceConnection(sourceInfo).use { conn ->
                    plugin.messageManager.sendMessage(sender, "migrate_scan_header")
                    var foundCount = 0
                    for (module in targetModules) {
                        val configuredName = getConfiguredMpdbTable(module, sourceInfo.mpdbYaml)
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
                        if (targetModuleId != null) {
                            sender.sendMessage("§e移行を開始するには §6/azisync migrate mpdb $targetModuleId confirm §eを実行してください。既存のデータは上書きされます。")
                        } else {
                            plugin.messageManager.sendMessage(sender, "migrate_confirm_prompt")
                        }
                    }
                }
            } catch (e: Exception) {
                plugin.logger.severe("Error scanning MPDB database (${sourceInfo.description}): ${e.message}")
                e.printStackTrace()
                plugin.messageManager.sendMessage(
                    sender,
                    "migrate_failed",
                    mapOf("{error}" to (e.message ?: e.toString()))
                )
            }
        })
    }

    fun migrate(sender: CommandSender, targetModuleId: String? = null) {
        if (!isMigrating.compareAndSet(false, true)) {
            plugin.messageManager.sendMessage(sender, "migrate_already_running")
            return
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                val targetModules = if (targetModuleId != null) {
                    val matched = modules.filter { it.id.equals(targetModuleId, ignoreCase = true) }
                    if (matched.isEmpty()) {
                        sender.sendMessage("§cUnknown module: $targetModuleId. Available: ${modules.joinToString { it.id }}")
                        isMigrating.set(false)
                        return@Runnable
                    }
                    matched
                } else {
                    modules
                }

                val sourceInfo = resolveSourceInfo()
                plugin.messageManager.sendMessage(sender, "migrate_started")
                plugin.logger.info("Starting MPDB data migration from ${sourceInfo.description}...")

                var totalMigrated = 0

                getSourceConnection(sourceInfo).use { sourceConn ->
                    plugin.databaseManager.getConnection().use { targetConn ->
                        try {
                            targetConn.autoCommit = false
                            val charSet = "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"

                            for (module in targetModules) {
                                val configuredName = getConfiguredMpdbTable(module, sourceInfo.mpdbYaml)
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
