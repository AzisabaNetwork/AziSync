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
        val hyphenated = uuid.toString().lowercase()
        val unhyphenated = hyphenated.replace("-", "")
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT `player_uuid` FROM `$tableName` WHERE `player_uuid` = ? OR `player_uuid` = ? LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, hyphenated)
                stmt.setString(2, unhyphenated)
                stmt.executeQuery().use { rs ->
                    return rs.next()
                }
            }
        }
    }

    override fun createAccount(uuid: UUID, playerName: String, initialBalance: Double): Boolean {
        val hyphenated = uuid.toString().lowercase()
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = """
                    INSERT INTO `$tableName`
                    (`player_uuid`, `player_name`, `money`, `offline_money`, `last_seen`, `sync_complete`) 
                    VALUES(?, ?, ?, ?, ?, ?)
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, hyphenated)
                    stmt.setString(2, playerName)
                    stmt.setDouble(3, initialBalance)
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
        val hyphenated = uuid.toString().lowercase()
        val unhyphenated = hyphenated.replace("-", "")
        val offlineUuid = UUID.nameUUIDFromBytes("OfflinePlayer:$playerName".toByteArray(Charsets.UTF_8)).toString().lowercase()

        plugin.databaseManager.getConnection().use { conn ->
            val sql = """
                SELECT `id`, `player_uuid`, `player_name`, `money`, `offline_money`, `sync_complete`, `last_seen`
                FROM `$tableName`
                WHERE `player_uuid` IN (?, ?, ?) OR LOWER(`player_name`) = LOWER(?)
                ORDER BY `money` DESC, `id` DESC
            """.trimIndent()

            var primaryRow: DatabaseEconomyData? = null
            var primaryId: Long? = null
            var primaryUuid: String? = null
            val duplicateIds = mutableListOf<Long>()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, hyphenated)
                stmt.setString(2, unhyphenated)
                stmt.setString(3, offlineUuid)
                stmt.setString(4, playerName)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = rs.getLong("id")
                        val rowUuid = rs.getString("player_uuid")
                        if (primaryRow == null) {
                            primaryId = id
                            primaryUuid = rowUuid
                            primaryRow = DatabaseEconomyData(
                                rs.getDouble("money"),
                                rs.getDouble("offline_money"),
                                rs.getString("sync_complete"),
                                rs.getString("last_seen")
                            )
                        } else {
                            duplicateIds.add(id)
                        }
                    }
                }
            }

            if (primaryRow != null) {
                // If there were multiple rows (e.g. dummy 0.0 row and migrated positive row)
                // or if the chosen row's UUID is not the standard hyphenated UUID, auto-heal
                if (duplicateIds.isNotEmpty() || primaryUuid != hyphenated) {
                    try {
                        if (duplicateIds.isNotEmpty()) {
                            val placeholders = duplicateIds.joinToString(",") { "?" }
                            conn.prepareStatement("DELETE FROM `$tableName` WHERE `id` IN ($placeholders)").use { delStmt ->
                                duplicateIds.forEachIndexed { index, dupId -> delStmt.setLong(index + 1, dupId) }
                                delStmt.executeUpdate()
                            }
                        }
                        conn.prepareStatement("UPDATE `$tableName` SET `player_uuid` = ?, `player_name` = ? WHERE `id` = ?").use { updStmt ->
                            updStmt.setString(1, hyphenated)
                            updStmt.setString(2, playerName)
                            updStmt.setLong(3, primaryId!!)
                            updStmt.executeUpdate()
                        }
                        EconomyAudit.info(plugin, "ECONOMY_ACCOUNT_HEALED", uuid, playerName,
                            "oldUuid" to primaryUuid, "newUuid" to hyphenated, "restoredBalance" to primaryRow.money,
                            "removedDuplicates" to duplicateIds.size)
                    } catch (e: Exception) {
                        plugin.logger.warning("Failed to auto-heal economy record for $playerName: ${e.message}")
                    }
                }
                return primaryRow
            }
        }

        // Account does not exist in DB: initialize with Vault balance if available
        val player = org.bukkit.Bukkit.getPlayer(uuid)
        val econ = plugin.hookManager.economyHook.getEconomy()
        val initialBalance = if (player != null && player.isOnline && econ != null) {
            econ.getBalance(player).coerceAtLeast(0.0)
        } else 0.0
        createAccount(uuid, playerName, initialBalance)
        if (initialBalance > 0.0) {
            EconomyAudit.info(plugin, "ECONOMY_ACCOUNT_INITIALIZED_FROM_VAULT", uuid, playerName,
                "initialBalance" to initialBalance, "provider" to (econ?.name ?: "none"))
        }
        return DatabaseEconomyData(initialBalance, 0.0, "true", System.currentTimeMillis().toString())
    }

    override fun getBalance(uuid: UUID): Double? {
        val hyphenated = uuid.toString().lowercase()
        val unhyphenated = hyphenated.replace("-", "")
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT `money` FROM `$tableName` WHERE `player_uuid` = ? OR `player_uuid` = ? ORDER BY `money` DESC LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, hyphenated)
                stmt.setString(2, unhyphenated)
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
        val hyphenated = uuid.toString().lowercase()
        val unhyphenated = hyphenated.replace("-", "")
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = "UPDATE `$tableName` SET `money` = ?, `player_uuid` = ? WHERE `player_uuid` = ? OR `player_uuid` = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setDouble(1, balance)
                    stmt.setString(2, hyphenated)
                    stmt.setString(3, hyphenated)
                    stmt.setString(4, unhyphenated)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error setting balance for $uuid: ${e.message}")
            false
        }
    }

    override fun getOfflineBalance(uuid: UUID): Double? {
        val hyphenated = uuid.toString().lowercase()
        val unhyphenated = hyphenated.replace("-", "")
        plugin.databaseManager.getConnection().use { conn ->
            val sql = "SELECT `offline_money` FROM `$tableName` WHERE `player_uuid` = ? OR `player_uuid` = ? ORDER BY `money` DESC LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, hyphenated)
                stmt.setString(2, unhyphenated)
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
        val hyphenated = uuid.toString().lowercase()
        val unhyphenated = hyphenated.replace("-", "")
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = "UPDATE `$tableName` SET `offline_money` = ?, `player_uuid` = ? WHERE `player_uuid` = ? OR `player_uuid` = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setDouble(1, amount)
                    stmt.setString(2, hyphenated)
                    stmt.setString(3, hyphenated)
                    stmt.setString(4, unhyphenated)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error setting offline balance for $uuid: ${e.message}")
            false
        }
    }

    override fun addOfflineMoney(uuid: UUID, amount: Double): Boolean {
        val hyphenated = uuid.toString().lowercase()
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
                    stmt.setString(1, hyphenated)
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
        val hyphenated = uuid.toString().lowercase()
        val unhyphenated = hyphenated.replace("-", "")
        plugin.databaseManager.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val selectSql = "SELECT `id`, `offline_money` FROM `$tableName` WHERE `player_uuid` = ? OR `player_uuid` = ? ORDER BY `money` DESC LIMIT 1 FOR UPDATE"
                val row = conn.prepareStatement(selectSql).use { stmt ->
                    stmt.setString(1, hyphenated)
                    stmt.setString(2, unhyphenated)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getLong("id") to rs.getDouble("offline_money") else null
                    }
                }
                if (row == null) {
                    conn.rollback()
                    return null
                }
                val (id, amount) = row
                conn.prepareStatement("UPDATE `$tableName` SET `offline_money` = 0, `player_uuid` = ? WHERE `id` = ?").use { stmt ->
                    stmt.setString(1, hyphenated)
                    stmt.setLong(2, id)
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
        val hyphenated = uuid.toString().lowercase()
        val unhyphenated = hyphenated.replace("-", "")
        plugin.databaseManager.getConnection().use { conn ->
            conn.autoCommit = false
            try {
                val selectSql = "SELECT `id`, `money`, `offline_money` FROM `$tableName` WHERE `player_uuid` = ? OR `player_uuid` = ? ORDER BY `money` DESC LIMIT 1 FOR UPDATE"
                val balances = conn.prepareStatement(selectSql).use { stmt ->
                    stmt.setString(1, hyphenated)
                    stmt.setString(2, unhyphenated)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) Triple(rs.getLong("id"), rs.getDouble("money"), rs.getDouble("offline_money")) else null
                    }
                }
                if (balances == null) {
                    conn.rollback()
                    EconomyAudit.severe(plugin, "ECONOMY_MERGE_ACCOUNT_MISSING", uuid,
                        details = arrayOf("table" to tableName))
                    return null
                }

                val (id, storedBalance, offlineDelta) = balances
                val mergedBalance = storedBalance + offlineDelta
                conn.prepareStatement(
                    "UPDATE `$tableName` SET `money` = ?, `offline_money` = 0, `last_seen` = ?, `player_uuid` = ? WHERE `id` = ?"
                ).use { stmt ->
                    stmt.setDouble(1, mergedBalance)
                    stmt.setString(2, System.currentTimeMillis().toString())
                    stmt.setString(3, hyphenated)
                    stmt.setLong(4, id)
                    if (stmt.executeUpdate() != 1) {
                        throw SQLException("Economy balance merge updated an unexpected number of rows")
                    }
                }
                conn.commit()
                val result = EconomyMergeResult(storedBalance, offlineDelta, mergedBalance)
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
        val hyphenated = uuid.toString().lowercase()
        val unhyphenated = hyphenated.replace("-", "")
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = "UPDATE `$tableName` SET `sync_complete` = ?, `last_seen` = ?, `player_uuid` = ? WHERE `player_uuid` = ? OR `player_uuid` = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, status)
                    stmt.setString(2, System.currentTimeMillis().toString())
                    stmt.setString(3, hyphenated)
                    stmt.setString(4, hyphenated)
                    stmt.setString(5, unhyphenated)
                    stmt.executeUpdate() > 0
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("Error setting economy sync status for ${playerName}: ${e.message}")
            false
        }
    }

    override fun setData(uuid: UUID, playerName: String, money: Double, syncStatus: String): Boolean {
        val hyphenated = uuid.toString().lowercase()
        val unhyphenated = hyphenated.replace("-", "")
        if (!hasAccount(uuid)) {
            createAccount(uuid, playerName, money)
        }
        return try {
            plugin.databaseManager.getConnection().use { conn ->
                val sql = """
                    UPDATE `$tableName` 
                    SET `player_name` = ?, `money` = ?, `sync_complete` = ?, `last_seen` = ?, `player_uuid` = ?
                    WHERE `player_uuid` = ? OR `player_uuid` = ?
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, playerName)
                    stmt.setDouble(2, money)
                    stmt.setString(3, syncStatus)
                    stmt.setString(4, System.currentTimeMillis().toString())
                    stmt.setString(5, hyphenated)
                    stmt.setString(6, hyphenated)
                    stmt.setString(7, unhyphenated)
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
