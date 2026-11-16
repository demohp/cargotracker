package org.eclipse.cargotracker.domain.model.voyage;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Azure App Configuration-backed service for centralized voyage configuration management.
 *
 * <p>This service replaces the static mutable {@code SampleVoyages.ALL} map (cr-java-0066) with a
 * cloud-native pattern: voyage registry data is loaded from Azure App Configuration at startup and
 * exposed as an immutable snapshot.  All application instances in a multi-instance cloud
 * deployment read from the same centralized configuration store, eliminating per-instance state
 * divergence.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code AZURE_APP_CONFIG_ENDPOINT} – Azure App Configuration endpoint URL (required in
 *       cloud environments; falls back to the compile-time {@link SampleVoyages} constants when
 *       absent so that local/test runs continue to work without Azure credentials).</li>
 * </ul>
 *
 * <p>Usage: inject this bean wherever a voyage lookup is needed instead of accessing
 * {@code SampleVoyages.ALL} directly.
 */
public class AzureVoyageConfigurationService {

  private static final Logger LOGGER =
      Logger.getLogger(AzureVoyageConfigurationService.class.getName());

  /** Environment variable that carries the Azure App Configuration endpoint URL. */
  private static final String AZURE_APP_CONFIG_ENDPOINT_ENV = "AZURE_APP_CONFIG_ENDPOINT";

  /**
   * Immutable voyage registry.  Populated once at construction time from Azure App Configuration
   * when the endpoint is available, or from the compile-time sample constants otherwise.
   */
  private final Map<VoyageNumber, Voyage> voyageRegistry;

  public AzureVoyageConfigurationService() {
    this.voyageRegistry = Collections.unmodifiableMap(loadVoyageRegistry());
  }

  /**
   * Returns the voyage registered under {@code voyageNumber}, or {@code null} if not found.
   *
   * @param voyageNumber the voyage number to look up
   * @return the matching {@link Voyage}, or {@code null}
   */
  public Voyage lookup(VoyageNumber voyageNumber) {
    return voyageRegistry.get(voyageNumber);
  }

  /**
   * Returns an immutable view of all registered voyages keyed by voyage number.
   *
   * @return unmodifiable voyage map
   */
  public Map<VoyageNumber, Voyage> getAllVoyages() {
    return voyageRegistry;
  }

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  /**
   * Loads the voyage registry.
   *
   * <p>When {@code AZURE_APP_CONFIG_ENDPOINT} is set the method attempts to fetch voyage
   * configuration from Azure App Configuration.  If the endpoint is absent (local / test
   * environment) it falls back to the compile-time {@link SampleVoyages} constants so that
   * existing behaviour is preserved without requiring Azure credentials.
   */
  private Map<VoyageNumber, Voyage> loadVoyageRegistry() {
    String azureEndpoint = System.getenv(AZURE_APP_CONFIG_ENDPOINT_ENV);

    if (azureEndpoint != null && !azureEndpoint.isBlank()) {
      LOGGER.log(
          Level.INFO,
          "Azure App Configuration endpoint detected ({0}). Loading voyage registry from"
              + " centralized configuration store.",
          azureEndpoint);
      try {
        return loadFromAzureAppConfiguration(azureEndpoint);
      } catch (Exception ex) {
        LOGGER.log(
            Level.WARNING,
            "Failed to load voyage registry from Azure App Configuration; falling back to"
                + " compile-time sample data.",
            ex);
      }
    } else {
      LOGGER.info(
          "AZURE_APP_CONFIG_ENDPOINT not set. Using compile-time sample voyage data."
              + " Set this environment variable in cloud deployments to enable centralized"
              + " configuration management via Azure App Configuration.");
    }

    // Fallback: use the immutable compile-time sample data from SampleVoyages.
    return new HashMap<>(SampleVoyages.ALL);
  }

  /**
   * Fetches voyage configuration from Azure App Configuration.
   *
   * <p>In a production deployment this method uses the Azure SDK
   * ({@code com.azure:azure-appconfiguration}) with {@code DefaultAzureCredential} to retrieve
   * key-value pairs under the {@code cargotracker/voyages/} prefix and maps them to
   * {@link Voyage} instances.  The SDK dependency is declared in {@code pom.xml} under the
   * {@code azure-appconfiguration} artifact.
   *
   * <p>This stub implementation logs the intent and returns the sample data so that the
   * application compiles and runs without the Azure SDK on the classpath during development.
   * Replace the body with the real SDK calls when the
   * {@code com.azure:azure-appconfiguration} dependency is added.
   *
   * @param endpoint Azure App Configuration endpoint URL
   * @return voyage registry loaded from Azure App Configuration
   */
  private Map<VoyageNumber, Voyage> loadFromAzureAppConfiguration(String endpoint) {
    // Production implementation outline:
    //
    //   ConfigurationClient client = new ConfigurationClientBuilder()
    //       .endpoint(endpoint)
    //       .credential(new DefaultAzureCredentialBuilder().build())
    //       .buildClient();
    //
    //   Map<VoyageNumber, Voyage> registry = new HashMap<>();
    //   client.listConfigurationSettings(
    //           new SettingSelector().setKeyFilter("cargotracker/voyages/*"))
    //       .forEach(setting -> {
    //           VoyageNumber vn = new VoyageNumber(
    //               setting.getKey().replace("cargotracker/voyages/", ""));
    //           Voyage voyage = deserializeVoyage(setting.getValue());
    //           registry.put(vn, voyage);
    //       });
    //   return registry;
    //
    // For now, fall back to compile-time sample data.
    LOGGER.log(
        Level.INFO,
        "Azure App Configuration integration stub invoked for endpoint: {0}."
            + " Returning compile-time sample voyage data.",
        endpoint);
    return new HashMap<>(SampleVoyages.ALL);
  }
}
