package net.azisaba.azisync.database.handler

import net.azisaba.azisync.AziSync
import java.sql.SQLException
import java.util.UUID

data class DatabasePotionData(
    val potionEffects: String,
    val syncComplete: String,
    val lastSeen: String
)

class MySQLPotionEffectsStorageHandler(private val plugin: AziSync) : PotionEffectsStorageHandler {

    private val tableName: String
        get() = plugin.config.getString("database.TablesNames.potionEffectsTableName", "azisync_potioneffects")!!

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
                    (`player_uuid`, `player_name`, `potion_effects`, `last_seen`, `sync_complete`) 
                    VALUES(?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.setString(2, playerName)
                    stmt.setString(3, "none")
                    stmt.setString(4, System.currentTimeMillis().toString())
                    stmt.setString(5, "true")
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error creating potion effects account for ${playerName}: ${e.message}")
            false
        }
    }

    override fun getData(uuid: UUID, playerName: String): DatabasePotionData? {
        if (!hasAccount(uuid)) {
            createAccount(uuid, playerName)
        }
        
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT * FROM `$tableName` WHERE `player_uuid` = ? LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return DatabasePotionData(
                            rs.getString("potion_effects"),
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
            plugin.logger.warning("Error setting potion effects sync status for ${playerName}: ${e.message}")
            false
        }
    }

    override fun setData(uuid: UUID, playerName: String, potionEffects: String, syncStatus: String): Boolean {
        if (!hasAccount(uuid)) {
            createAccount(uuid, playerName)
        }
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = """
                    UPDATE `$tableName` 
                    SET `player_name` = ?, `potion_effects` = ?, `sync_complete` = ?, `last_seen` = ? 
                    WHERE `player_uuid` = ?
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, playerName)
                    stmt.setString(2, potionEffects)
                    stmt.setString(3, syncStatus)
                    stmt.setString(4, System.currentTimeMillis().toString())
                    stmt.setString(5, uuid.toString())
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error saving potion effects data for ${playerName}: ${e.message}")
            false
        }
    }
}
