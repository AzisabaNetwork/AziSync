package net.azisaba.azisync.database.handler

import net.azisaba.azisync.AziSync
import java.sql.SQLException
import java.util.UUID

data class DatabaseInventoryData(
    val inventory: String,
    val armor: String,
    val hotbarSlot: Int,
    val gamemode: Int,
    val syncComplete: String,
    val lastSeen: String
)

class MySQLInventoryStorageHandler(private val plugin: AziSync) {

    private val tableName: String
        get() = plugin.config.getString("database.TablesNames.inventoryTableName", "azisync_inventory")!!

    fun getSyncStatus(uuid: UUID): String? {
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT `sync_complete` FROM `$tableName` WHERE `player_uuid` = ? LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getString("sync_complete")
                    }
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
                stmt.executeQuery().use { rs ->
                    return rs.next()
                }
            }
        }
    }

    fun createAccount(uuid: UUID, playerName: String): Boolean {
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = """
                    INSERT INTO `$tableName`
                    (`player_uuid`, `player_name`, `inventory`, `armor`, `hotbar_slot`, `gamemode`, `last_seen`, `sync_complete`) 
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.setString(2, playerName)
                    stmt.setString(3, "none")
                    stmt.setString(4, "none")
                    stmt.setInt(5, 0)
                    stmt.setInt(6, 0)
                    stmt.setString(7, System.currentTimeMillis().toString())
                    stmt.setString(8, "true")
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error creating account for ${playerName}: ${e.message}")
            false
        }
    }

    fun getData(uuid: UUID, playerName: String): DatabaseInventoryData? {
        if (!hasAccount(uuid)) {
            createAccount(uuid, playerName)
        }
        
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT * FROM `$tableName` WHERE `player_uuid` = ? LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return DatabaseInventoryData(
                            rs.getString("inventory"),
                            rs.getString("armor"),
                            rs.getInt("hotbar_slot"),
                            rs.getInt("gamemode"),
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
            plugin.logger.warning("Error setting sync status for ${playerName}: ${e.message}")
            false
        }
    }

    fun setData(uuid: UUID, playerName: String, inventory: String, armor: String, hotbarSlot: Int, gamemode: Int, syncStatus: String): Boolean {
        if (!hasAccount(uuid)) {
            createAccount(uuid, playerName)
        }
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = """
                    UPDATE `$tableName` 
                    SET `player_name` = ?, `inventory` = ?, `armor` = ?, `hotbar_slot` = ?, 
                        `gamemode` = ?, `sync_complete` = ?, `last_seen` = ? 
                    WHERE `player_uuid` = ?
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, playerName)
                    stmt.setString(2, inventory)
                    stmt.setString(3, armor)
                    stmt.setInt(4, hotbarSlot)
                    stmt.setInt(5, gamemode)
                    stmt.setString(6, syncStatus)
                    stmt.setString(7, System.currentTimeMillis().toString())
                    stmt.setString(8, uuid.toString())
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error saving inventory data for ${playerName}: ${e.message}")
            false
        }
    }
}
