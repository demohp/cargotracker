package org.eclipse.cargotracker.interfaces.handling.file;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.specialized.AppendBlobClient;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.batch.api.chunk.listener.SkipReadListener;
import jakarta.batch.runtime.context.JobContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Writes lines that failed to parse to Azure Blob Storage instead of the local file system.
 *
 * <p>Configuration is supplied entirely through environment variables so that no paths or
 * credentials are hard-coded in the application artefact (12-factor app, factor III &amp; IV):
 *
 * <ul>
 *   <li>{@code AZURE_STORAGE_CONNECTION_STRING} – Azure Storage account connection string
 *       (required). Can be provided via Azure App Configuration or Key Vault reference.
 *   <li>{@code AZURE_STORAGE_FAILED_CONTAINER} – Blob container name for failed-parse records
 *       (optional, defaults to {@code cargo-failed}).
 * </ul>
 */
@Dependent
@Named("LineParseExceptionListener")
public class LineParseExceptionListener implements SkipReadListener {

  /** Environment variable holding the Azure Storage connection string. */
  private static final String ENV_CONNECTION_STRING = "AZURE_STORAGE_CONNECTION_STRING";

  /** Environment variable holding the target Blob container name for failed records. */
  private static final String ENV_FAILED_CONTAINER = "AZURE_STORAGE_FAILED_CONTAINER";

  /** Default container name used when {@value #ENV_FAILED_CONTAINER} is not set. */
  private static final String DEFAULT_FAILED_CONTAINER = "cargo-failed";

  @Inject private Logger logger;

  @Inject private JobContext jobContext;

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

  /** Returns the Blob container name for failed records, falling back to the default. */
  private String resolveContainerName() {
    String value = System.getenv(ENV_FAILED_CONTAINER);
    return (value != null && !value.trim().isEmpty()) ? value.trim() : DEFAULT_FAILED_CONTAINER;
  }

  /**
   * Builds the blob name for the current job instance's failed-records file.
   *
   * <p>Example: {@code failed_EventFileProcessor_42.csv}
   */
  private String buildBlobName() {
    return "failed_" + jobContext.getJobName() + "_" + jobContext.getInstanceId() + ".csv";
  }

  // -------------------------------------------------------------------------
  // SkipReadListener
  // -------------------------------------------------------------------------

  /**
   * Appends the unparseable line to an append blob in Azure Blob Storage and logs a warning.
   *
   * <p>The blob is created on first write and subsequent calls append to the same blob, preserving
   * the same semantics as the previous {@code FileWriter(file, true)} (append mode) behaviour.
   */
  @Override
  public void onSkipReadItem(Exception e) throws Exception {
    EventLineParseException parseException = (EventLineParseException) e;

    logger.log(Level.WARNING, "Problem parsing event file line", parseException);

    BlobServiceClient serviceClient =
        new BlobServiceClientBuilder()
            .connectionString(resolveConnectionString())
            .buildClient();

    BlobContainerClient containerClient =
        serviceClient.getBlobContainerClient(resolveContainerName());

    // Create the container if it does not exist (idempotent)
    if (!containerClient.exists()) {
      containerClient.create();
    }

    String blobName = buildBlobName();
    AppendBlobClient appendBlobClient =
        containerClient.getBlobClient(blobName).getAppendBlobClient();

    // Create the append blob only on the very first write; subsequent calls just append.
    if (!appendBlobClient.exists()) {
      appendBlobClient.create();
    }

    String line = parseException.getLine() + System.lineSeparator();
    byte[] data = line.getBytes(StandardCharsets.UTF_8);
    appendBlobClient.appendBlock(new ByteArrayInputStream(data), data.length);
  }
}
