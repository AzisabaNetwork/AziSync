package net.azisaba.azisync.database.handler

import net.azisaba.azisync.AziSync
import java.sql.SQLException
import java.util.UUID

data class DatabaseExperienceData(
    val exp: Float,
    val expToLevel: Int,
    val totalExp: Int,
    val expLvl: Int,
    val syncComplete: String,
    val lastSeen: String
)

class MySQLExperienceStorageHandler(private val plugin: AziSync) : ExperienceStorageHandler {

    private val tableName: String
        get() = plugin.config.getString("database.TablesNames.experienceTableName", "azisync_experience")!!

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
                    (`player_uuid`, `player_name`, `exp`, `exp_to_level`, `total_exp`, `exp_lvl`, `last_seen`, `sync_complete`) 
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.setString(2, playerName)
                    stmt.setFloat(3, 0f)
                    stmt.setInt(4, 0)
                    stmt.setInt(5, 0)
                    stmt.setInt(6, 0)
                    stmt.setString(7, System.currentTimeMillis().toString())
                    stmt.setString(8, "true")
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error creating experience account for ${playerName}: ${e.message}")
            false
        }
    }

    override fun getData(uuid: UUID, playerName: String): DatabaseExperienceData? {
        if (!hasAccount(uuid)) {
            createAccount(uuid, playerName)
        }
        
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT * FROM `$tableName` WHERE `player_uuid` = ? LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return DatabaseExperienceData(
                            rs.getFloat("exp"),
                            rs.getInt("exp_to_level"),
                            rs.getInt("total_exp"),
                            rs.getInt("exp_lvl"),
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
            plugin.logger.warning("Error setting experience sync status for ${playerName}: ${e.message}")
            false
        }
    }

    override fun setData(uuid: UUID, playerName: String, exp: Float, expToLevel: Int, totalExp: Int, expLvl: Int, syncStatus: String): Boolean {
        if (!hasAccount(uuid)) {
            createAccount(uuid, playerName)
        }
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = """
                    UPDATE `$tableName` 
                    SET `player_name` = ?, `exp` = ?, `exp_to_level` = ?, `total_exp` = ?, `exp_lvl` = ?, `sync_complete` = ?, `last_seen` = ? 
                    WHERE `player_uuid` = ?
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, playerName)
                    stmt.setFloat(2, exp)
                    stmt.setInt(3, expToLevel)
                    stmt.setInt(4, totalExp)
                    stmt.setInt(5, expLvl)
                    stmt.setString(6, syncStatus)
                    stmt.setString(7, System.currentTimeMillis().toString())
                    stmt.setString(8, uuid.toString())
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error saving experience data for ${playerName}: ${e.message}")
            false
        }
    }
}
