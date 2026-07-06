package net.azisaba.azisync.database.handler

import net.azisaba.azisync.AziSync
import java.sql.SQLException
import java.util.UUID

data class DatabaseEconomyData(
    val money: Double,
    val offlineMoney: Double,
    val syncComplete: String,
    val lastSeen: String
)

class EconomyStorageHandler(private val plugin: AziSync) {

    private val tableName: String
        get() = plugin.config.getString("database.TablesNames.economyTableName", "azisync_economy")!!

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
                    (`player_uuid`, `player_name`, `money`, `offline_money`, `last_seen`, `sync_complete`) 
                    VALUES(?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.setString(2, playerName)
                    stmt.setDouble(3, 0.0)
                    stmt.setDouble(4, 0.0)
                    stmt.setString(5, System.currentTimeMillis().toString())
                    stmt.setString(6, "true")
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error creating economy account for ${playerName}: ${e.message}")
            false
        }
    }

    fun getData(uuid: UUID, playerName: String): DatabaseEconomyData? {
        if (!hasAccount(uuid)) {
            createAccount(uuid, playerName)
        }
        
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT * FROM `$tableName` WHERE `player_uuid` = ? LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return DatabaseEconomyData(
                            rs.getDouble("money"),
                            rs.getDouble("offline_money"),
                            rs.getString("sync_complete"),
                            rs.getString("last_seen")
                        )
                    }
                }
            }
        }
        return null
    }

    fun getBalance(uuid: UUID): Double? {
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT `money` FROM `$tableName` WHERE `player_uuid` = ? LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getDouble("money")
                    }
                }
            }
        }
        return null
    }

    fun setBalance(uuid: UUID, balance: Double): Boolean {
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = "UPDATE `$tableName` SET `money` = ? WHERE `player_uuid` = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setDouble(1, balance)
                    stmt.setString(2, uuid.toString())
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error setting balance for $uuid: ${e.message}")
            false
        }
    }

    fun getOfflineBalance(uuid: UUID): Double? {
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT `offline_money` FROM `$tableName` WHERE `player_uuid` = ? LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getDouble("offline_money")
                    }
                }
            }
        }
        return null
    }

    fun setOfflineMoney(uuid: UUID, amount: Double): Boolean {
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = "UPDATE `$tableName` SET `offline_money` = ? WHERE `player_uuid` = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setDouble(1, amount)
                    stmt.setString(2, uuid.toString())
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error setting offline balance for $uuid: ${e.message}")
            false
        }
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
            plugin.logger.warning("Error setting economy sync status for ${playerName}: ${e.message}")
            false
        }
    }

    fun setData(uuid: UUID, playerName: String, money: Double, syncStatus: String): Boolean {
        if (!hasAccount(uuid)) {
            createAccount(uuid, playerName)
        }
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = """
                    UPDATE `$tableName` 
                    SET `player_name` = ?, `money` = ?, `sync_complete` = ?, `last_seen` = ? 
                    WHERE `player_uuid` = ?
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, playerName)
                    stmt.setDouble(2, money)
                    stmt.setString(3, syncStatus)
                    stmt.setString(4, System.currentTimeMillis().toString())
                    stmt.setString(5, uuid.toString())
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error saving economy data for ${playerName}: ${e.message}")
            false
        }
    }
}