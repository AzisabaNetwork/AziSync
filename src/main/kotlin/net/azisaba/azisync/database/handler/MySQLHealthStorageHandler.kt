package net.azisaba.azisync.database.handler

import net.azisaba.azisync.AziSync
import java.sql.SQLException
import java.util.UUID

data class DatabaseHealthData(
    val health: Double,
    val healthScale: Double,
    val maxHealth: Double,
    val food: Int,
    val saturation: String,
    val air: Int,
    val maxAir: Int,
    val syncComplete: String,
    val lastSeen: String
)

class MySQLHealthStorageHandler(private val plugin: AziSync) : HealthStorageHandler {

    private val tableName: String
        get() = plugin.config.getString("database.TablesNames.healthFoodAirTableName", "azisync_healthfoodair")!!

    override fun getSyncStatus(uuid: UUID): String? {
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT `sync_complete` FROM `$tableName` WHERE `player_uuid` = ? LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs ->
                    if (rs.next()) return rs.getString("sync_complete")
                }
            }
        }
        return null
    }

    override fun hasAccount(uuid: UUID): Boolean {
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT `player_uuid` FROM `$tableName` WHERE `player_uuid` = ? LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs -> return rs.next() }
            }
        }
    }

    override fun createAccount(uuid: UUID, playerName: String): Boolean {
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = """
                    INSERT INTO `$tableName`
                    (`player_uuid`, `player_name`, `health`, `health_scale`, `max_health`, `food`, `saturation`, `air`, `max_air`, `last_seen`, `sync_complete`) 
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.setString(2, playerName)
                    stmt.setDouble(3, 20.0)
                    stmt.setDouble(4, 20.0)
                    stmt.setDouble(5, 20.0)
                    stmt.setInt(6, 20)
                    stmt.setString(7, "5.0")
                    stmt.setInt(8, 300)
                    stmt.setInt(9, 300)
                    stmt.setString(10, System.currentTimeMillis().toString())
                    stmt.setString(11, "true")
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error creating health account for ${playerName}: ${e.message}")
            false
        }
    }

    override fun getData(uuid: UUID, playerName: String): DatabaseHealthData? {
        if (!hasAccount(uuid)) {
            createAccount(uuid, playerName)
        }
        
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT * FROM `$tableName` WHERE `player_uuid` = ? LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return DatabaseHealthData(
                            rs.getDouble("health"),
                            rs.getDouble("health_scale"),
                            rs.getDouble("max_health"),
                            rs.getInt("food"),
                            rs.getString("saturation"),
                            rs.getInt("air"),
                            rs.getInt("max_air"),
                            rs.getString("sync_complete"),
                            rs.getString("last_seen")
                        )
                    }
                }
            }
        }
        return null
    }

    override fun setSyncStatus(uuid: UUID, playerName: String, status: String): Boolean {
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = "UPDATE `$tableName` SET `sync_complete` = ?, `last_seen` = ? WHERE `player_uuid` = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, status)
                    stmt.setString(2, System.currentTimeMillis().toString())
                    stmt.setString(3, uuid.toString())
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error setting health sync status for ${playerName}: ${e.message}")
            false
        }
    }

    override fun setData(
        uuid: UUID, playerName: String, 
        health: Double, 
        healthScale: Double, 
        maxHealth: Double, 
        food: Int, 
        saturation: String, 
        air: Int, 
        maxAir: Int, 
        syncStatus: String
    ): Boolean {
        if (!hasAccount(uuid)) {
            createAccount(uuid, playerName)
        }
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = """
                    UPDATE `$tableName` 
                    SET `player_name` = ?, `health` = ?, `health_scale` = ?, `max_health` = ?, `food` = ?, `saturation` = ?, `air` = ?, `max_air` = ?, `sync_complete` = ?, `last_seen` = ? 
                    WHERE `player_uuid` = ?
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, playerName)
                    stmt.setDouble(2, health)
                    stmt.setDouble(3, healthScale)
                    stmt.setDouble(4, maxHealth)
                    stmt.setInt(5, food)
                    stmt.setString(6, saturation)
                    stmt.setInt(7, air)
                    stmt.setInt(8, maxAir)
                    stmt.setString(9, syncStatus)
                    stmt.setString(10, System.currentTimeMillis().toString())
                    stmt.setString(11, uuid.toString())
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error saving health data for ${playerName}: ${e.message}")
            false
        }
    }
}
