package net.azisaba.azisync.database.handler

import net.azisaba.azisync.AziSync
import net.azisaba.azisync.util.EconomyAudit
import java.sql.SQLException
import java.util.UUID

data class DatabaseEconomyData(
    val money: Double,
    val offlineMoney: Double,
    val syncComplete: String,
    val lastSeen: String
)

class MySQLEconomyStorageHandler(private val plugin: AziSync) : EconomyStorageHandler {

    private val tableName: String
        get() = plugin.config.getString("database.TablesNames.economyTableName", "azisync_economy")!!

    override fun getSyncStatus(uuid: UUID): String? {
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

    override fun hasAccount(uuid: UUID): Boolean {
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

    override fun createAccount(uuid: UUID, playerName: String): Boolean {
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

    override fun getData(uuid: UUID, playerName: String): DatabaseEconomyData? {
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

    override fun getBalance(uuid: UUID): Double? {
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

    override fun setBalance(uuid: UUID, balance: Double): Boolean {
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

    override fun getOfflineBalance(uuid: UUID): Double? {
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

    override fun setOfflineMoney(uuid: UUID, amount: Double): Boolean {
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

    override fun addOfflineMoney(uuid: UUID, amount: Double): Boolean {
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = """
                    INSERT INTO `$tableName`
                    (`player_uuid`, `player_name`, `money`, `offline_money`, `last_seen`, `sync_complete`)
                    VALUES (?, 'Unknown', 0, ?, ?, 'true')
                    ON DUPLICATE KEY UPDATE
                    `offline_money` = `offline_money` + VALUES(`offline_money`),
                    `last_seen` = VALUES(`last_seen`)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.setDouble(2, amount)
                    stmt.setString(3, System.currentTimeMillis().toString())
                    val updated = stmt.executeUpdate() > 0
                    if (updated) {
                        EconomyAudit.info(plugin, "OFFLINE_DELTA_STORED", uuid,
                            details = arrayOf("amount" to amount, "table" to tableName))
                    } else {
                        EconomyAudit.severe(plugin, "OFFLINE_DELTA_NOT_STORED", uuid,
                            details = arrayOf("amount" to amount, "table" to tableName, "reason" to "no_rows_changed"))
                    }
                    updated
                }
            }
        } catch (e: SQLException) {
            EconomyAudit.severe(plugin, "OFFLINE_DELTA_DB_ERROR", uuid, error = e,
                details = arrayOf("amount" to amount, "table" to tableName))
            false
        }
    }

    override fun consumeOfflineMoney(uuid: UUID): Double? {
        plugin.databaseManager.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val amount = conn.prepareStatement(
                    "SELECT `offline_money` FROM `$tableName` WHERE `player_uuid` = ? FOR UPDATE"
                ).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getDouble("offline_money") else null }
                }
                if (amount == null) {
                    conn.rollback()
                    return null
                }
                conn.prepareStatement("UPDATE `$tableName` SET `offline_money` = 0 WHERE `player_uuid` = ?").use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.executeUpdate()
                }
                conn.commit()
                return amount
            } catch (e: SQLException) {
                conn.rollback()
                plugin.logger.warning("Error consuming offline balance for $uuid: ${e.message}")
                return null
            }
        }
    }

    override fun mergeOfflineMoneyIntoBalance(uuid: UUID): EconomyMergeResult? {
        plugin.databaseManager.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val balances = conn.prepareStatement(
                    "SELECT `money`, `offline_money` FROM `$tableName` WHERE `player_uuid` = ? FOR UPDATE"
                ).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getDouble("money") to rs.getDouble("offline_money") else null
                    }
                }
                if (balances == null) {
                    conn.rollback()
                    EconomyAudit.severe(plugin, "ECONOMY_MERGE_ACCOUNT_MISSING", uuid,
                        details = arrayOf("table" to tableName))
                    return null
                }

                val mergedBalance = balances.first + balances.second
                conn.prepareStatement(
                    "UPDATE `$tableName` SET `money` = ?, `offline_money` = 0, `last_seen` = ? WHERE `player_uuid` = ?"
                ).use { stmt ->
                    stmt.setDouble(1, mergedBalance)
                    stmt.setString(2, System.currentTimeMillis().toString())
                    stmt.setString(3, uuid.toString())
                    if (stmt.executeUpdate() != 1) {
                        throw SQLException("Economy balance merge updated an unexpected number of rows")
                    }
                }
                conn.commit()
                val result = EconomyMergeResult(balances.first, balances.second, mergedBalance)
                EconomyAudit.info(plugin, "ECONOMY_MERGE_COMMITTED", uuid,
                    details = arrayOf(
                        "storedBalance" to result.storedBalance,
                        "offlineDelta" to result.offlineDelta,
                        "mergedBalance" to result.mergedBalance,
                        "table" to tableName
                    ))
                return result
            } catch (e: SQLException) {
                conn.rollback()
                EconomyAudit.severe(plugin, "ECONOMY_MERGE_DB_ERROR", uuid, error = e,
                    details = arrayOf("table" to tableName))
                return null
            }
        }
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
            plugin.logger.warning("Error setting economy sync status for ${playerName}: ${e.message}")
            false
        }
    }

    override fun setData(uuid: UUID, playerName: String, money: Double, syncStatus: String): Boolean {
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
                    val updated = stmt.executeUpdate() > 0
                    if (updated) {
                        EconomyAudit.info(plugin, "ECONOMY_SAVE_COMMITTED", uuid, playerName,
                            "balance" to money, "syncStatus" to syncStatus, "table" to tableName)
                    } else {
                        EconomyAudit.severe(plugin, "ECONOMY_SAVE_NOT_COMMITTED", uuid, playerName,
                            details = arrayOf("balance" to money, "syncStatus" to syncStatus, "table" to tableName, "reason" to "no_rows_changed"))
                    }
                    updated
                }
            }
        } catch (e: SQLException) {
            EconomyAudit.severe(plugin, "ECONOMY_SAVE_DB_ERROR", uuid, playerName, e,
                "balance" to money, "syncStatus" to syncStatus, "table" to tableName)
            false
        }
    }
}
