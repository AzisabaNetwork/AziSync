package net.azisaba.azisync.database.handler

import net.azisaba.azisync.AziSync
import java.util.UUID

class MySQLCraftGuiStorageHandler(private val plugin: AziSync) : CraftGuiStorageHandler {
    private val tableName = plugin.config.getString("database.TablesNames.craftGuiTableName", "azisync_craftgui")

    override fun getSyncStatus(uuid: UUID): String? {
        plugin.databaseManager.getConnection().use { connection ->
            connection.prepareStatement("SELECT sync_complete FROM `$tableName` WHERE player_uuid = ?").use { statement ->
                statement.setString(1, uuid.toString())
                val resultSet = statement.executeQuery()
                if (resultSet.next()) {
                    return resultSet.getString("sync_complete")
                }
            }
        }
        return null
    }

    override fun hasAccount(uuid: UUID): Boolean {
        plugin.databaseManager.getConnection().use { connection ->
            connection.prepareStatement("SELECT id FROM `$tableName` WHERE player_uuid = ?").use { statement ->
                statement.setString(1, uuid.toString())
                val resultSet = statement.executeQuery()
                return resultSet.next()
            }
        }
    }

    override fun createAccount(uuid: UUID, playerName: String): Boolean {
        plugin.databaseManager.getConnection().use { connection ->
            connection.prepareStatement("INSERT IGNORE INTO `$tableName` (player_uuid, player_name, sound_enabled, show_result_items, craftable_only, stash_enabled, sync_complete, last_seen) VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use { statement ->
                statement.setString(1, uuid.toString())
                statement.setString(2, playerName)
                statement.setBoolean(3, true)
                statement.setBoolean(4, true)
                statement.setBoolean(5, false)
                statement.setBoolean(6, false)
                statement.setString(7, "true")
                statement.setString(8, System.currentTimeMillis().toString())
                return statement.executeUpdate() > 0
            }
        }
    }

    override fun getData(uuid: UUID, playerName: String): DatabaseCraftGuiData? {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        plugin.databaseManager.getConnection().use { connection ->
            connection.prepareStatement("SELECT * FROM `$tableName` WHERE player_uuid = ?").use { statement ->
                statement.setString(1, uuid.toString())
                val resultSet = statement.executeQuery()
                if (resultSet.next()) {
                    return DatabaseCraftGuiData(
                        resultSet.getBoolean("sound_enabled"),
                        resultSet.getBoolean("show_result_items"),
                        resultSet.getBoolean("craftable_only"),
                        resultSet.getBoolean("stash_enabled"),
                        resultSet.getString("sync_complete"),
                        resultSet.getString("last_seen")
                    )
                }
            }
        }
        return null
    }

    override fun setSyncStatus(uuid: UUID, playerName: String, status: String): Boolean {
        plugin.databaseManager.getConnection().use { connection ->
            connection.prepareStatement("UPDATE `$tableName` SET sync_complete = ?, last_seen = ? WHERE player_uuid = ?").use { statement ->
                statement.setString(1, status)
                statement.setString(2, System.currentTimeMillis().toString())
                statement.setString(3, uuid.toString())
                return statement.executeUpdate() > 0
            }
        }
    }

    override fun setData(
        uuid: UUID, playerName: String, soundEnabled: Boolean, showResultItems: Boolean,
        craftableOnly: Boolean, stashEnabled: Boolean, syncStatus: String
    ): Boolean {
        if (!hasAccount(uuid)) createAccount(uuid, playerName)
        plugin.databaseManager.getConnection().use { connection ->
            connection.prepareStatement("UPDATE `$tableName` SET player_name = ?, sound_enabled = ?, show_result_items = ?, craftable_only = ?, stash_enabled = ?, sync_complete = ?, last_seen = ? WHERE player_uuid = ?").use { statement ->
                statement.setString(1, playerName)
                statement.setBoolean(2, soundEnabled)
                statement.setBoolean(3, showResultItems)
                statement.setBoolean(4, craftableOnly)
                statement.setBoolean(5, stashEnabled)
                statement.setString(6, syncStatus)
                statement.setString(7, System.currentTimeMillis().toString())
                statement.setString(8, uuid.toString())
                return statement.executeUpdate() > 0
            }
        }
    }
}
