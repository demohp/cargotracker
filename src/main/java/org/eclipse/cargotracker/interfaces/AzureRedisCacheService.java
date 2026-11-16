package org.eclipse.cargotracker.interfaces;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.logging.Level;
import java.util.logging.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Azure Cache for Redis-backed caching service with TTL support (cr-java-0067).
 *
 * <p>This CDI ApplicationScoped bean replaces in-memory static caches (such as those in
 * {@link CoordinatesFactory} and {@code RealtimeCargoTrackingViewAdapter}) with a distributed
 * Redis cache hosted on Azure Cache for Redis. Using a distributed cache with TTL policies
 * prevents indefinite memory growth, eliminates stale-data inconsistencies across multiple
 * application instances, and ensures cache consistency in cloud environments.
 *
 * <p>Configuration (environment variables):
 * <ul>
 *   <li>{@code AZURE_REDIS_HOST} – Azure Cache for Redis hostname (required in cloud; defaults
 *       to {@code localhost} for local development).</li>
 *   <li>{@code AZURE_REDIS_PORT} – Redis port (defaults to {@code 6380} for Azure TLS, or
 *       {@code 6379} for local).</li>
 *   <li>{@code AZURE_REDIS_PASSWORD} – Redis access key / password (required in cloud).</li>
 *   <li>{@code AZURE_REDIS_SSL} – {@code true} to enable SSL/TLS (default {@code true} for
 *       Azure Cache for Redis).</li>
 *   <li>{@code AZURE_REDIS_TTL_SECONDS} – Default TTL in seconds for cached entries
 *       (defaults to {@code 3600} – 1 hour).</li>
 * </ul>
 *
 * <p>When {@code AZURE_REDIS_HOST} is not set the service operates in <em>local fallback</em>
 * mode: all cache operations are no-ops and callers must supply their own default values.
 * This preserves existing behaviour in local / test environments without requiring Azure
 * credentials.
 */
@ApplicationScoped
public class AzureRedisCacheService {

  private static final Logger LOGGER =
      Logger.getLogger(AzureRedisCacheService.class.getName());

  // -------------------------------------------------------------------------
  // Environment variable names
  // -------------------------------------------------------------------------
  private static final String ENV_REDIS_HOST     = "AZURE_REDIS_HOST";
  private static final String ENV_REDIS_PORT     = "AZURE_REDIS_PORT";
  private static final String ENV_REDIS_PASSWORD = "AZURE_REDIS_PASSWORD";
  private static final String ENV_REDIS_SSL      = "AZURE_REDIS_SSL";
  private static final String ENV_REDIS_TTL      = "AZURE_REDIS_TTL_SECONDS";

  // -------------------------------------------------------------------------
  // Defaults
  // -------------------------------------------------------------------------
  private static final int    DEFAULT_AZURE_PORT = 6380;
  private static final int    DEFAULT_LOCAL_PORT = 6379;
  private static final int    DEFAULT_TTL_SECONDS = 3600; // 1 hour

  // -------------------------------------------------------------------------
  // State
  // -------------------------------------------------------------------------
  private JedisPool jedisPool;
  private int       defaultTtlSeconds;
  private boolean   redisAvailable;

  // -------------------------------------------------------------------------
  // Lifecycle
  // -------------------------------------------------------------------------

  @PostConstruct
  public void init() {
    String host     = System.getenv(ENV_REDIS_HOST);
    String password = System.getenv(ENV_REDIS_PASSWORD);
    String portStr  = System.getenv(ENV_REDIS_PORT);
    String sslStr   = System.getenv(ENV_REDIS_SSL);
    String ttlStr   = System.getenv(ENV_REDIS_TTL);

    defaultTtlSeconds = parseTtl(ttlStr);

    if (host == null || host.isBlank()) {
      LOGGER.info(
          "AZURE_REDIS_HOST not set. AzureRedisCacheService operating in local fallback mode. "
              + "Set AZURE_REDIS_HOST, AZURE_REDIS_PASSWORD, and AZURE_REDIS_SSL=true in cloud "
              + "deployments to enable Azure Cache for Redis.");
      redisAvailable = false;
      return;
    }

    boolean useSsl = !"false".equalsIgnoreCase(sslStr); // default true for Azure
    int port = parsePort(portStr, useSsl);

    try {
      JedisPoolConfig poolConfig = buildPoolConfig();
      if (password != null && !password.isBlank()) {
        jedisPool = new JedisPool(poolConfig, host, port, 2000, password, useSsl);
      } else {
        jedisPool = new JedisPool(poolConfig, host, port, 2000, null, useSsl);
      }
      // Verify connectivity
      try (Jedis jedis = jedisPool.getResource()) {
        jedis.ping();
      }
      redisAvailable = true;
      LOGGER.log(
          Level.INFO,
          "Azure Cache for Redis connected: host={0}, port={1}, ssl={2}, ttl={3}s",
          new Object[]{host, port, useSsl, defaultTtlSeconds});
    } catch (Exception ex) {
      LOGGER.log(
          Level.WARNING,
          "Failed to connect to Azure Cache for Redis at {0}:{1}. "
              + "Falling back to local in-memory mode. Error: {2}",
          new Object[]{host, port, ex.getMessage()});
      redisAvailable = false;
      if (jedisPool != null) {
        jedisPool.close();
        jedisPool = null;
      }
    }
  }

  @PreDestroy
  public void destroy() {
    if (jedisPool != null && !jedisPool.isClosed()) {
      jedisPool.close();
      LOGGER.info("Azure Cache for Redis connection pool closed.");
    }
  }

  // -------------------------------------------------------------------------
  // Public cache operations
  // -------------------------------------------------------------------------

  /**
   * Stores a string value in the cache under {@code key} with the default TTL.
   *
   * @param key   cache key (must not be null)
   * @param value value to store
   */
  public void put(String key, String value) {
    put(key, value, defaultTtlSeconds);
  }

  /**
   * Stores a string value in the cache under {@code key} with an explicit TTL.
   *
   * @param key        cache key (must not be null)
   * @param value      value to store
   * @param ttlSeconds time-to-live in seconds; use {@code -1} to persist without expiry
   */
  public void put(String key, String value, int ttlSeconds) {
    if (!redisAvailable || jedisPool == null) {
      return;
    }
    try (Jedis jedis = jedisPool.getResource()) {
      if (ttlSeconds > 0) {
        jedis.setex(key, ttlSeconds, value);
      } else {
        jedis.set(key, value);
      }
    } catch (Exception ex) {
      LOGGER.log(Level.WARNING, "Redis PUT failed for key ''{0}'': {1}",
          new Object[]{key, ex.getMessage()});
    }
  }

  /**
   * Retrieves a string value from the cache.
   *
   * @param key cache key
   * @return the cached value, or {@code null} if not present or Redis is unavailable
   */
  public String get(String key) {
    if (!redisAvailable || jedisPool == null) {
      return null;
    }
    try (Jedis jedis = jedisPool.getResource()) {
      return jedis.get(key);
    } catch (Exception ex) {
      LOGGER.log(Level.WARNING, "Redis GET failed for key ''{0}'': {1}",
          new Object[]{key, ex.getMessage()});
      return null;
    }
  }

  /**
   * Returns {@code true} if the cache contains an entry for {@code key}.
   *
   * @param key cache key
   * @return {@code true} if the key exists in Redis
   */
  public boolean exists(String key) {
    if (!redisAvailable || jedisPool == null) {
      return false;
    }
    try (Jedis jedis = jedisPool.getResource()) {
      return jedis.exists(key);
    } catch (Exception ex) {
      LOGGER.log(Level.WARNING, "Redis EXISTS failed for key ''{0}'': {1}",
          new Object[]{key, ex.getMessage()});
      return false;
    }
  }

  /**
   * Removes a cache entry.
   *
   * @param key cache key to evict
   */
  public void evict(String key) {
    if (!redisAvailable || jedisPool == null) {
      return;
    }
    try (Jedis jedis = jedisPool.getResource()) {
      jedis.del(key);
    } catch (Exception ex) {
      LOGGER.log(Level.WARNING, "Redis DEL failed for key ''{0}'': {1}",
          new Object[]{key, ex.getMessage()});
    }
  }

  /**
   * Returns whether the Redis connection is available.
   *
   * @return {@code true} if Redis is reachable
   */
  public boolean isRedisAvailable() {
    return redisAvailable;
  }

  /**
   * Returns the default TTL (in seconds) applied to cache entries.
   *
   * @return default TTL in seconds
   */
  public int getDefaultTtlSeconds() {
    return defaultTtlSeconds;
  }

  // -------------------------------------------------------------------------
  // Internal helpers
  // -------------------------------------------------------------------------

  private JedisPoolConfig buildPoolConfig() {
    JedisPoolConfig config = new JedisPoolConfig();
    config.setMaxTotal(16);
    config.setMaxIdle(8);
    config.setMinIdle(2);
    config.setTestOnBorrow(true);
    config.setTestOnReturn(true);
    config.setTestWhileIdle(true);
    return config;
  }

  private int parsePort(String portStr, boolean useSsl) {
    if (portStr != null && !portStr.isBlank()) {
      try {
        return Integer.parseInt(portStr.trim());
      } catch (NumberFormatException e) {
        LOGGER.log(Level.WARNING, "Invalid AZURE_REDIS_PORT value ''{0}''; using default.", portStr);
      }
    }
    return useSsl ? DEFAULT_AZURE_PORT : DEFAULT_LOCAL_PORT;
  }

  private int parseTtl(String ttlStr) {
    if (ttlStr != null && !ttlStr.isBlank()) {
      try {
        int ttl = Integer.parseInt(ttlStr.trim());
        if (ttl > 0) {
          return ttl;
        }
      } catch (NumberFormatException e) {
        LOGGER.log(Level.WARNING,
            "Invalid AZURE_REDIS_TTL_SECONDS value ''{0}''; using default {1}s.",
            new Object[]{ttlStr, DEFAULT_TTL_SECONDS});
      }
    }
    return DEFAULT_TTL_SECONDS;
  }
}
