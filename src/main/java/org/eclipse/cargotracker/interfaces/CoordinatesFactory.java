package org.eclipse.cargotracker.interfaces;

import static org.eclipse.cargotracker.domain.model.location.Location.UNKNOWN;
import static org.eclipse.cargotracker.domain.model.location.SampleLocations.CHICAGO;
import static org.eclipse.cargotracker.domain.model.location.SampleLocations.DALLAS;
import static org.eclipse.cargotracker.domain.model.location.SampleLocations.GOTHENBURG;
import static org.eclipse.cargotracker.domain.model.location.SampleLocations.HAMBURG;
import static org.eclipse.cargotracker.domain.model.location.SampleLocations.HANGZOU;
import static org.eclipse.cargotracker.domain.model.location.SampleLocations.HELSINKI;
import static org.eclipse.cargotracker.domain.model.location.SampleLocations.HONGKONG;
import static org.eclipse.cargotracker.domain.model.location.SampleLocations.MELBOURNE;
import static org.eclipse.cargotracker.domain.model.location.SampleLocations.NEWYORK;
import static org.eclipse.cargotracker.domain.model.location.SampleLocations.ROTTERDAM;
import static org.eclipse.cargotracker.domain.model.location.SampleLocations.SHANGHAI;
import static org.eclipse.cargotracker.domain.model.location.SampleLocations.STOCKHOLM;
import static org.eclipse.cargotracker.domain.model.location.SampleLocations.TOKYO;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.cargotracker.domain.model.location.Location;
import org.eclipse.cargotracker.domain.model.location.UnLocode;

/**
 * Coordinates factory backed by Azure Cache for Redis (cr-java-0067).
 *
 * <p>The previous implementation stored coordinates in a static {@code HashMap} that acted as an
 * unbounded in-memory cache without any TTL or expiration policy. In a cloud environment with
 * multiple application instances this caused:
 * <ul>
 *   <li>Indefinite memory growth if the map were ever extended at runtime.</li>
 *   <li>No cache synchronisation across instances.</li>
 *   <li>Stale data inconsistencies if coordinate data were updated.</li>
 * </ul>
 *
 * <p>This refactored version delegates all cache reads and writes to
 * {@link AzureRedisCacheService}, which connects to Azure Cache for Redis with a configurable
 * TTL (default 1 hour). When Redis is unavailable (local / test environments) the service
 * transparently falls back to the compile-time coordinate constants so that existing behaviour
 * is preserved without requiring Azure credentials.
 *
 * <p>Coordinates are stored in Redis as {@code "lat,lon"} strings under the key prefix
 * {@code "coordinates:"}.
 */
@ApplicationScoped
public class CoordinatesFactory {

  private static final Logger LOGGER = Logger.getLogger(CoordinatesFactory.class.getName());

  /** Redis key prefix for coordinate entries. */
  private static final String CACHE_KEY_PREFIX = "coordinates:";

  /**
   * Compile-time fallback map used when Redis is unavailable.
   * This map is immutable and never grows at runtime, so it does not cause memory growth.
   */
  private static final Map<String, Coordinates> FALLBACK_COORDINATES_MAP;

  static {
    Map<String, Coordinates> map = new HashMap<>();
    // TODO [Clean Code] See if there is a service to get the latitude/longitude data from.
    map.put(HONGKONG.getUnLocode().getIdString(),  new Coordinates(22,  114));
    map.put(MELBOURNE.getUnLocode().getIdString(), new Coordinates(-38, 145));
    map.put(STOCKHOLM.getUnLocode().getIdString(), new Coordinates(59,   18));
    map.put(HELSINKI.getUnLocode().getIdString(),  new Coordinates(60,   25));
    map.put(CHICAGO.getUnLocode().getIdString(),   new Coordinates(42,  -88));
    map.put(TOKYO.getUnLocode().getIdString(),     new Coordinates(36,  140));
    map.put(HAMBURG.getUnLocode().getIdString(),   new Coordinates(54,   10));
    map.put(SHANGHAI.getUnLocode().getIdString(),  new Coordinates(31,  121));
    map.put(ROTTERDAM.getUnLocode().getIdString(), new Coordinates(52,    5));
    map.put(GOTHENBURG.getUnLocode().getIdString(),new Coordinates(58,   12));
    map.put(HANGZOU.getUnLocode().getIdString(),   new Coordinates(30,  120));
    map.put(NEWYORK.getUnLocode().getIdString(),   new Coordinates(41,  -74));
    map.put(DALLAS.getUnLocode().getIdString(),    new Coordinates(33,  -97));
    map.put(UNKNOWN.getUnLocode().getIdString(),   new Coordinates(-90,   0)); // The South Pole.
    FALLBACK_COORDINATES_MAP = Collections.unmodifiableMap(map);
  }

  @Inject
  private AzureRedisCacheService redisCacheService;

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  /**
   * Returns the {@link Coordinates} for the given {@link Location}.
   *
   * @param location the location to look up
   * @return coordinates, or {@code null} if not found
   */
  public Coordinates find(Location location) {
    return find(location.getUnLocode());
  }

  /**
   * Returns the {@link Coordinates} for the given {@link UnLocode}.
   *
   * @param unLocode the UN/LOCODE to look up
   * @return coordinates, or {@code null} if not found
   */
  public Coordinates find(UnLocode unLocode) {
    return find(unLocode.getIdString());
  }

  /**
   * Returns the {@link Coordinates} for the given UN/LOCODE string.
   *
   * <p>Lookup order:
   * <ol>
   *   <li>Azure Cache for Redis (distributed, TTL-controlled).</li>
   *   <li>Compile-time fallback map (when Redis is unavailable).</li>
   * </ol>
   *
   * <p>On a cache miss in Redis the value is populated from the fallback map and written back
   * to Redis with the configured TTL so that subsequent lookups are served from the distributed
   * cache.
   *
   * @param unLocode UN/LOCODE string (e.g. {@code "CNHKG"})
   * @return coordinates, or {@code null} if not found
   */
  public Coordinates find(String unLocode) {
    // 1. Try Redis first (distributed cache with TTL).
    if (redisCacheService.isRedisAvailable()) {
      String cacheKey = CACHE_KEY_PREFIX + unLocode;
      String cached = redisCacheService.get(cacheKey);
      if (cached != null) {
        return parseCoordinates(cached);
      }

      // Cache miss – look up in fallback map and populate Redis.
      Coordinates coords = FALLBACK_COORDINATES_MAP.get(unLocode);
      if (coords != null) {
        try {
          redisCacheService.put(cacheKey, serializeCoordinates(coords));
        } catch (Exception ex) {
          LOGGER.log(Level.WARNING,
              "Failed to populate Redis cache for key ''{0}'': {1}",
              new Object[]{cacheKey, ex.getMessage()});
        }
      }
      return coords;
    }

    // 2. Redis unavailable – use compile-time fallback map directly.
    return FALLBACK_COORDINATES_MAP.get(unLocode);
  }

  // -------------------------------------------------------------------------
  // Serialisation helpers
  // -------------------------------------------------------------------------

  /**
   * Serialises a {@link Coordinates} instance to a {@code "lat,lon"} string for Redis storage.
   */
  private static String serializeCoordinates(Coordinates coords) {
    return coords.getLatitude() + "," + coords.getLongitude();
  }

  /**
   * Deserialises a {@code "lat,lon"} string retrieved from Redis into a {@link Coordinates}.
   *
   * @param value the Redis value
   * @return parsed {@link Coordinates}, or {@code null} on parse error
   */
  private static Coordinates parseCoordinates(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      String[] parts = value.split(",", 2);
      double lat = Double.parseDouble(parts[0].trim());
      double lng = Double.parseDouble(parts[1].trim());
      return new Coordinates(lat, lng);
    } catch (Exception ex) {
      LOGGER.log(Level.WARNING,
          "Failed to parse coordinates from Redis value ''{0}'': {1}",
          new Object[]{value, ex.getMessage()});
      return null;
    }
  }
}
