package org.eclipse.cargotracker.interfaces.booking.sse;

import jakarta.enterprise.inject.spi.CDI;
import java.util.EnumMap;
import java.util.Map;
import org.eclipse.cargotracker.domain.model.cargo.Cargo;
import org.eclipse.cargotracker.domain.model.cargo.RoutingStatus;
import org.eclipse.cargotracker.domain.model.cargo.TransportStatus;
import org.eclipse.cargotracker.interfaces.AzureRedisCacheService;

/**
 * View adapter for displaying a cargo in a realtime tracking context.
 *
 * <p>The previous implementation stored routing-status and transport-status label maps as static
 * {@code EnumMap} fields (cr-java-0067). These acted as unbounded in-memory caches without any
 * TTL or expiration policy, causing stale-data inconsistencies across multiple cloud instances.
 *
 * <p>This refactored version delegates label lookups to {@link AzureRedisCacheService}, which
 * stores entries in Azure Cache for Redis with a configurable TTL. When Redis is unavailable
 * (local / test environments) the service falls back to the compile-time label constants so that
 * existing behaviour is preserved without requiring Azure credentials.
 *
 * <p>Labels are stored in Redis under the key prefixes:
 * <ul>
 *   <li>{@code "routing_status_label:"} for {@link RoutingStatus} labels.</li>
 *   <li>{@code "transport_status_label:"} for {@link TransportStatus} labels.</li>
 * </ul>
 */
public class RealtimeCargoTrackingViewAdapter {

  // -------------------------------------------------------------------------
  // Redis cache key prefixes for status labels
  // -------------------------------------------------------------------------
  private static final String ROUTING_STATUS_KEY_PREFIX   = "routing_status_label:";
  private static final String TRANSPORT_STATUS_KEY_PREFIX = "transport_status_label:";

  /**
   * Compile-time fallback maps used when Redis is unavailable.
   * These are immutable and never grow at runtime, so they do not cause memory growth.
   */
  private static final Map<RoutingStatus, String> FALLBACK_ROUTING_STATUS_LABELS;
  private static final Map<TransportStatus, String> FALLBACK_TRANSPORT_STATUS_LABELS;

  static {
    Map<RoutingStatus, String> routingLabels = new EnumMap<>(RoutingStatus.class);
    routingLabels.put(RoutingStatus.NOT_ROUTED, "Not routed");
    routingLabels.put(RoutingStatus.ROUTED,     "Routed");
    routingLabels.put(RoutingStatus.MISROUTED,  "Misrouted");
    FALLBACK_ROUTING_STATUS_LABELS = java.util.Collections.unmodifiableMap(routingLabels);

    Map<TransportStatus, String> transportLabels = new EnumMap<>(TransportStatus.class);
    transportLabels.put(TransportStatus.NOT_RECEIVED,    "Not received");
    transportLabels.put(TransportStatus.IN_PORT,         "In port");
    transportLabels.put(TransportStatus.ONBOARD_CARRIER, "Onboard carrier");
    transportLabels.put(TransportStatus.CLAIMED,         "Claimed");
    transportLabels.put(TransportStatus.UNKNOWN,         "Unknown");
    FALLBACK_TRANSPORT_STATUS_LABELS = java.util.Collections.unmodifiableMap(transportLabels);
  }

  private final Cargo cargo;

  public RealtimeCargoTrackingViewAdapter(Cargo cargo) {
    this.cargo = cargo;
  }

  public String getTrackingId() {
    return cargo.getTrackingId().getIdString();
  }

  /**
   * Returns the human-readable routing status label.
   *
   * <p>Lookup order:
   * <ol>
   *   <li>Azure Cache for Redis (distributed, TTL-controlled).</li>
   *   <li>Compile-time fallback map (when Redis is unavailable).</li>
   * </ol>
   */
  public String getRoutingStatus() {
    RoutingStatus status = cargo.getDelivery().getRoutingStatus();
    return getRoutingStatusLabel(status);
  }

  public boolean isMisdirected() {
    return cargo.getDelivery().isMisdirected();
  }

  /**
   * Returns the human-readable transport status label.
   *
   * <p>Lookup order:
   * <ol>
   *   <li>Azure Cache for Redis (distributed, TTL-controlled).</li>
   *   <li>Compile-time fallback map (when Redis is unavailable).</li>
   * </ol>
   */
  public String getTransportStatus() {
    TransportStatus status = cargo.getDelivery().getTransportStatus();
    return getTransportStatusLabel(status);
  }

  public boolean isAtDestination() {
    return cargo.getDelivery().isUnloadedAtDestination();
  }

  public LocationViewAdapter getOrigin() {
    return new LocationViewAdapter(cargo.getOrigin());
  }

  public LocationViewAdapter getLastKnownLocation() {
    return new LocationViewAdapter(cargo.getDelivery().getLastKnownLocation());
  }

  public LocationViewAdapter getLocation() {
    return cargo.getDelivery().getTransportStatus() == TransportStatus.NOT_RECEIVED
        ? getOrigin()
        : getLastKnownLocation();
  }

  public String getStatusCode() {
    RoutingStatus routingStatus = cargo.getDelivery().getRoutingStatus();

    if (routingStatus == RoutingStatus.NOT_ROUTED || routingStatus == RoutingStatus.MISROUTED) {
      return routingStatus.toString();
    }

    if (cargo.getDelivery().isMisdirected()) {
      return "MISDIRECTED";
    }

    if (cargo.getDelivery().isUnloadedAtDestination()) {
      return "AT_DESTINATION";
    }

    return cargo.getDelivery().getTransportStatus().toString();
  }

  // -------------------------------------------------------------------------
  // Internal helpers – Redis-backed label lookups with fallback
  // -------------------------------------------------------------------------

  /**
   * Retrieves the routing status label from Azure Cache for Redis, falling back to the
   * compile-time constant map when Redis is unavailable.
   *
   * @param status the routing status enum value
   * @return human-readable label string
   */
  private String getRoutingStatusLabel(RoutingStatus status) {
    AzureRedisCacheService cache = redisCacheService();
    if (cache.isRedisAvailable()) {
      String cacheKey = ROUTING_STATUS_KEY_PREFIX + status.name();
      String cached = cache.get(cacheKey);
      if (cached != null) {
        return cached;
      }
      // Cache miss – populate from fallback and write to Redis with TTL.
      String label = FALLBACK_ROUTING_STATUS_LABELS.get(status);
      if (label != null) {
        cache.put(cacheKey, label);
      }
      return label;
    }
    return FALLBACK_ROUTING_STATUS_LABELS.get(status);
  }

  /**
   * Retrieves the transport status label from Azure Cache for Redis, falling back to the
   * compile-time constant map when Redis is unavailable.
   *
   * @param status the transport status enum value
   * @return human-readable label string
   */
  private String getTransportStatusLabel(TransportStatus status) {
    AzureRedisCacheService cache = redisCacheService();
    if (cache.isRedisAvailable()) {
      String cacheKey = TRANSPORT_STATUS_KEY_PREFIX + status.name();
      String cached = cache.get(cacheKey);
      if (cached != null) {
        return cached;
      }
      // Cache miss – populate from fallback and write to Redis with TTL.
      String label = FALLBACK_TRANSPORT_STATUS_LABELS.get(status);
      if (label != null) {
        cache.put(cacheKey, label);
      }
      return label;
    }
    return FALLBACK_TRANSPORT_STATUS_LABELS.get(status);
  }

  /**
   * Retrieves the CDI-managed {@link AzureRedisCacheService} bean.
   * Using CDI.current() because this adapter is a plain object, not a CDI bean.
   */
  private AzureRedisCacheService redisCacheService() {
    return CDI.current().select(AzureRedisCacheService.class).get();
  }
}
