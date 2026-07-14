package net.azisaba.azisync.database

import net.azisaba.azisync.AziSync
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

class RedisManager(private val plugin: AziSync) {
    private var jedisPool: JedisPool? = null

    init {
        setupPool()
    }

    private fun setupPool() {
        val config = plugin.config
        val host = config.getString("redis.host", "localhost")!!
        val port = config.getInt("redis.port", 6379)
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
                JedisPool(poolConfig, host, port, timeout, password)
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

    fun close() {
        if (jedisPool != null && !jedisPool!!.isClosed) {
            jedisPool!!.close()
        }
    }
}
