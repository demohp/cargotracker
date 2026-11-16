package org.eclipse.cargotracker.interfaces.handling.file;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.specialized.AppendBlobClient;
import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jakarta.batch.api.chunk.AbstractItemWriter;
import jakarta.batch.runtime.context.JobContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import org.eclipse.cargotracker.application.ApplicationEvents;
import org.eclipse.cargotracker.application.util.DateConverter;
import org.eclipse.cargotracker.interfaces.handling.HandlingEventRegistrationAttempt;

/**
 * Writes processed handling-event records to Azure Blob Storage instead of the local file system.
 *
 * <p>Configuration is supplied entirely through environment variables so that no paths or
 * credentials are hard-coded in the application artefact (12-factor app, factor III &amp; IV):
 *
 * <ul>
 *   <li>{@code AZURE_STORAGE_CONNECTION_STRING} – Azure Storage account connection string
 *       (required). Can be provided via Azure App Configuration or Key Vault reference.
 *   <li>{@code AZURE_STORAGE_ARCHIVE_CONTAINER} – Blob container name for archived events
 *       (optional, defaults to {@code cargo-archive}).
 * </ul>
 */
@Dependent
@Named("EventItemWriter")
public class EventItemWriter extends AbstractItemWriter {

  /** Environment variable holding the Azure Storage connection string. */
  private static final String ENV_CONNECTION_STRING = "AZURE_STORAGE_CONNECTION_STRING";

  /** Environment variable holding the target Blob container name. */
  private static final String ENV_ARCHIVE_CONTAINER = "AZURE_STORAGE_ARCHIVE_CONTAINER";

  /** Default container name used when {@value #ENV_ARCHIVE_CONTAINER} is not set. */
  private static final String DEFAULT_ARCHIVE_CONTAINER = "cargo-archive";

  @Inject private JobContext jobContext;
  @Inject private ApplicationEvents applicationEvents;

  private BlobContainerClient containerClient;

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /**
   * Returns the Azure Storage connection string from the environment.
   *
   * @throws IllegalStateException if the environment variable is not set.
   */
  private String resolveConnectionString() {
    String value = System.getenv(ENV_CONNECTION_STRING);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalStateException(
          "Required environment variable '"
              + ENV_CONNECTION_STRING
              + "' is not set. "
              + "Provide the Azure Storage account connection string via Azure App Configuration "
              + "or Key Vault before starting the application.");
    }
    return value.trim();
  }

  /** Returns the Blob container name, falling back to the default when not configured. */
  private String resolveContainerName() {
    String value = System.getenv(ENV_ARCHIVE_CONTAINER);
    return (value != null && !value.trim().isEmpty()) ? value.trim() : DEFAULT_ARCHIVE_CONTAINER;
  }

  /**
   * Builds the blob name for the current job instance.
   *
   * <p>Example: {@code archive_EventFileProcessor_42.csv}
   */
  private String buildBlobName() {
    return "archive_" + jobContext.getJobName() + "_" + jobContext.getInstanceId() + ".csv";
  }

  // -------------------------------------------------------------------------
  // AbstractItemWriter lifecycle
  // -------------------------------------------------------------------------

  /**
   * Initialises the Azure Blob Storage container client and ensures the target container exists.
   * The container is created with private access if it does not already exist.
   */
  @Override
  public void open(Serializable checkpoint) throws Exception {
    BlobServiceClient serviceClient =
        new BlobServiceClientBuilder()
            .connectionString(resolveConnectionString())
            .buildClient();

    containerClient = serviceClient.getBlobContainerClient(resolveContainerName());

    // Create the container if it does not exist (idempotent)
    if (!containerClient.exists()) {
      containerClient.create();
    }
  }

  /**
   * Appends each processed handling-event record to an append blob in Azure Blob Storage.
   *
   * <p>The blob is created on first write and subsequent calls append to the same blob, preserving
   * the same semantics as the previous {@code FileWriter(path, true)} (append mode) behaviour.
   */
  @Override
  @Transactional
  public void writeItems(List<Object> items) throws Exception {
    String blobName = buildBlobName();
    AppendBlobClient appendBlobClient =
        containerClient.getBlobClient(blobName).getAppendBlobClient();

    // Create the append blob only on the very first write; subsequent calls just append.
    if (!appendBlobClient.exists()) {
      appendBlobClient.create();
    }

    StringBuilder csvLines = new StringBuilder();

    items.stream()
        .map(item -> (HandlingEventRegistrationAttempt) item)
        .forEach(
            attempt -> {
              applicationEvents.receivedHandlingEventRegistrationAttempt(attempt);
              csvLines
                  .append(DateConverter.toString(attempt.getRegistrationTime()))
                  .append(",")
                  .append(DateConverter.toString(attempt.getCompletionTime()))
                  .append(",")
                  .append(attempt.getTrackingId())
                  .append(",")
                  .append(attempt.getVoyageNumber())
                  .append(",")
                  .append(attempt.getUnLocode())
                  .append(",")
                  .append(attempt.getType())
                  .append(System.lineSeparator());
            });

    if (csvLines.length() > 0) {
      byte[] data = csvLines.toString().getBytes(StandardCharsets.UTF_8);
      appendBlobClient.appendBlock(new ByteArrayInputStream(data), data.length);
    }
  }
}
