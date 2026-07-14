package net.azisaba.azisync.database.handler

import net.azisaba.azisync.AziSync
import java.sql.SQLException
import java.util.UUID

data class DatabaseLocationData(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val bedSpawn: String,
    val syncComplete: String,
    val lastSeen: String
)

class MySQLLocationStorageHandler(private val plugin: AziSync) {

    private val tableName: String
        get() = plugin.config.getString("database.TablesNames.locationTableName", "azisync_location")!!

    fun getSyncStatus(uuid: UUID): String? {
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

    fun hasAccount(uuid: UUID): Boolean {
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT `player_uuid` FROM `$tableName` WHERE `player_uuid` = ? LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs -> return rs.next() }
            }
        }
    }

    fun createAccount(uuid: UUID, playerName: String): Boolean {
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = """
                    INSERT INTO `$tableName`
                    (`player_uuid`, `player_name`, `world`, `x`, `y`, `z`, `yaw`, `pitch`, `bed_spawn`, `last_seen`, `sync_complete`) 
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.setString(2, playerName)
                    stmt.setString(3, "world")
                    stmt.setDouble(4, 0.0)
                    stmt.setDouble(5, 0.0)
                    stmt.setDouble(6, 0.0)
                    stmt.setFloat(7, 0f)
                    stmt.setFloat(8, 0f)
                    stmt.setString(9, "none")
                    stmt.setString(10, System.currentTimeMillis().toString())
                    stmt.setString(11, "true")
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error creating location account for ${playerName}: ${e.message}")
            false
        }
    }

    fun getData(uuid: UUID, playerName: String): DatabaseLocationData? {
        if (!hasAccount(uuid)) {
            createAccount(uuid, playerName)
        }
        
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT * FROM `$tableName` WHERE `player_uuid` = ? LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return DatabaseLocationData(
                            rs.getString("world"),
                            rs.getDouble("x"),
                            rs.getDouble("y"),
                            rs.getDouble("z"),
                            rs.getFloat("yaw"),
                            rs.getFloat("pitch"),
                            rs.getString("bed_spawn"),
                            rs.getString("sync_complete"),
                            rs.getString("last_seen")
                        )
                    }
                }
            }
        }
        return null
    }

    fun setSyncStatus(uuid: UUID, playerName: String, status: String): Boolean {
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
            plugin.logger.warning("Error setting location sync status for ${playerName}: ${e.message}")
            false
        }
    }

    fun setData(
        uuid: UUID, playerName: String, 
        world: String, 
        x: Double, 
        y: Double, 
        z: Double, 
        yaw: Float, 
        pitch: Float, 
        bedSpawn: String, 
        syncStatus: String
    ): Boolean {
        if (!hasAccount(uuid)) {
            createAccount(uuid, playerName)
        }
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = """
                    UPDATE `$tableName` 
                    SET `player_name` = ?, `world` = ?, `x` = ?, `y` = ?, `z` = ?, `yaw` = ?, `pitch` = ?, `bed_spawn` = ?, `sync_complete` = ?, `last_seen` = ? 
                    WHERE `player_uuid` = ?
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, playerName)
                    stmt.setString(2, world)
                    stmt.setDouble(3, x)
                    stmt.setDouble(4, y)
                    stmt.setDouble(5, z)
                    stmt.setFloat(6, yaw)
                    stmt.setFloat(7, pitch)
                    stmt.setString(8, bedSpawn)
                    stmt.setString(9, syncStatus)
                    stmt.setString(10, System.currentTimeMillis().toString())
                    stmt.setString(11, uuid.toString())
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error saving location data for ${playerName}: ${e.message}")
            false
        }
    }
}
