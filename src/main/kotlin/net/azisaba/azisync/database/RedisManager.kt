package net.azisaba.azisync.database

import net.azisaba.azisync.AziSync
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.util.UUID

class RedisManager(private val plugin: AziSync) {
    private var jedisPool: JedisPool? = null

    init {
        setupPool()
    }

    private fun setupPool() {
        val config = plugin.config
        val host = config.getString("redis.host", "localhost")!!
        val port = config.getInt("redis.port", 6379)
        val username = config.getString("redis.username", "")!!
        val password = config.getString("redis.password", "")!!
        val timeout = config.getInt("redis.timeout", 2000)

        val poolConfig = JedisPoolConfig().apply {
            maxTotal = 16
            maxIdle = 8
            minIdle = 2
            testOnBorrow = true
        }

        try {
            jedisPool = if (password.isNotEmpty()) {
                val clientConfig = DefaultJedisClientConfig.builder()
                    .timeoutMillis(timeout)
                    .socketTimeoutMillis(timeout)
                    .password(password)
                    .apply {
                        if (username.isNotEmpty()) {
                            user(username)
                        }
                    }
                    .build()
                JedisPool(poolConfig, HostAndPort(host, port), clientConfig)
            } else {
                JedisPool(poolConfig, host, port, timeout)
            }
            plugin.logger.info("Successfully connected to Redis database.")
        } catch (e: Exception) {
            plugin.logger.severe("Failed to connect to Redis database.")
            e.printStackTrace()
        }
    }

    fun getResource(): Jedis {
        return jedisPool?.resource ?: throw IllegalStateException("JedisPool is not initialized")
    }

    fun getSyncStatus(uuid: UUID): String? {
        return try {
            getResource().use { it.get(syncStatusKey(uuid)) }
        } catch (e: Exception) {
            plugin.logger.warning("Failed to read sync status from Redis: ${e.message}")
            null
        }
    }

    fun setSyncStatus(uuid: UUID, status: String) {
        try {
            getResource().use {
                it.setex(syncStatusKey(uuid), 300, status)
            }
        } catch (e: Exception) {
            plugin.logger.warning("Failed to write sync status to Redis: ${e.message}")
        }
    }

    private fun syncStatusKey(uuid: UUID): String {
        return "azisync:sync_status:$uuid"
    }

    fun close() {
        if (jedisPool != null && !jedisPool!!.isClosed) {
            jedisPool!!.close()
        }
    }
}
