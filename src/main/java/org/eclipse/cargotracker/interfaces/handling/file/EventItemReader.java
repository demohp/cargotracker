package org.eclipse.cargotracker.interfaces.handling.file;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.batch.api.chunk.AbstractItemReader;
import jakarta.batch.runtime.context.JobContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.cargotracker.application.util.DateConverter;
import org.eclipse.cargotracker.domain.model.cargo.TrackingId;
import org.eclipse.cargotracker.domain.model.handling.HandlingEvent;
import org.eclipse.cargotracker.domain.model.location.UnLocode;
import org.eclipse.cargotracker.domain.model.voyage.VoyageNumber;
import org.eclipse.cargotracker.interfaces.handling.HandlingEventRegistrationAttempt;

/**
 * Reads handling-event CSV records from Azure Blob Storage instead of the local file system.
 *
 * <p>Configuration is supplied entirely through environment variables so that no paths or
 * credentials are hard-coded in the application artefact (12-factor app, factor III &amp; IV):
 *
 * <ul>
 *   <li>{@code AZURE_STORAGE_CONNECTION_STRING} – Azure Storage account connection string
 *       (required). Can be provided via Azure App Configuration or Key Vault reference.
 *   <li>{@code AZURE_STORAGE_UPLOAD_CONTAINER} – Blob container name for incoming event files
 *       (optional, defaults to {@code cargo-upload}).
 * </ul>
 */
@Dependent
@Named("EventItemReader")
public class EventItemReader extends AbstractItemReader {

  /** Environment variable holding the Azure Storage connection string. */
  private static final String ENV_CONNECTION_STRING = "AZURE_STORAGE_CONNECTION_STRING";

  /** Environment variable holding the upload Blob container name. */
  private static final String ENV_UPLOAD_CONTAINER = "AZURE_STORAGE_UPLOAD_CONTAINER";

  /** Default container name used when {@value #ENV_UPLOAD_CONTAINER} is not set. */
  private static final String DEFAULT_UPLOAD_CONTAINER = "cargo-upload";

  @Inject private Logger logger;

  @Inject private JobContext jobContext;

  private EventFilesCheckpoint checkpoint;

  /** Azure Blob container client for the upload container. */
  private BlobContainerClient containerClient;

  /**
   * Lines of the blob currently being read, loaded entirely into memory so that
   * checkpoint-based resumption (line pointer) is preserved.
   */
  private List<String> currentBlobLines;

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

  /** Returns the Blob container name for uploads, falling back to the default when not set. */
  private String resolveContainerName() {
    String value = System.getenv(ENV_UPLOAD_CONTAINER);
    return (value != null && !value.trim().isEmpty()) ? value.trim() : DEFAULT_UPLOAD_CONTAINER;
  }

  /**
   * Downloads the content of the given blob and returns all non-null lines.
   *
   * @param blobName the name of the blob to download
   * @return ordered list of lines contained in the blob
   */
  private List<String> downloadBlobLines(String blobName) throws Exception {
    BlobClient blobClient = containerClient.getBlobClient(blobName);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    blobClient.downloadStream(outputStream);

    List<String> lines = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                new ByteArrayInputStream(outputStream.toByteArray()), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    return lines;
  }

  // -------------------------------------------------------------------------
  // AbstractItemReader lifecycle
  // -------------------------------------------------------------------------

  /**
   * Initialises the Azure Blob Storage container client and populates the checkpoint with the
   * list of blobs available in the upload container.
   *
   * <p>The container is created with private access if it does not already exist, preserving the
   * same "create on first use" semantics as the previous {@code uploadDirectory.mkdirs()} call.
   */
  @Override
  public void open(Serializable checkpoint) throws Exception {
    BlobServiceClient serviceClient =
        new BlobServiceClientBuilder()
            .connectionString(resolveConnectionString())
            .buildClient();

    containerClient = serviceClient.getBlobContainerClient(resolveContainerName());

    // Create the container if it does not exist (idempotent – mirrors uploadDirectory.mkdirs())
    if (!containerClient.exists()) {
      logger.log(Level.INFO, "Upload container does not exist, creating it");
      containerClient.create();
    }

    if (checkpoint == null) {
      this.checkpoint = new EventFilesCheckpoint();
      logger.log(Level.INFO, "Scanning upload container: {0}", resolveContainerName());

      List<String> blobNames = new ArrayList<>();
      for (BlobItem blobItem : containerClient.listBlobs()) {
        blobNames.add(blobItem.getName());
      }
      this.checkpoint.setBlobNames(blobNames);
    } else {
      logger.log(Level.INFO, "Starting from previous checkpoint");
      this.checkpoint = (EventFilesCheckpoint) checkpoint;
    }

    String currentBlobName = this.checkpoint.currentBlobName();

    if (currentBlobName == null) {
      logger.log(Level.INFO, "No blobs to process");
      currentBlobLines = null;
    } else {
      logger.log(Level.INFO, "Processing blob: {0}", currentBlobName);
      currentBlobLines = downloadBlobLines(currentBlobName);
    }
  }

  /**
   * Reads the next line from the current blob in Azure Blob Storage.
   *
   * <p>When all lines in the current blob have been consumed the blob is deleted (mirroring the
   * previous {@code currentFile().delete()} behaviour) and processing advances to the next blob.
   */
  @Override
  public Object readItem() throws Exception {
    if (currentBlobLines != null) {
      long linePointer = this.checkpoint.getFilePointer();

      if (linePointer < currentBlobLines.size()) {
        String line = currentBlobLines.get((int) linePointer);
        this.checkpoint.setFilePointer(linePointer + 1);
        return parseLine(line);
      } else {
        // All lines consumed – delete the blob and advance to the next one
        String finishedBlob = this.checkpoint.currentBlobName();
        logger.log(Level.INFO, "Finished processing blob, deleting: {0}", finishedBlob);
        containerClient.getBlobClient(finishedBlob).delete();

        String nextBlobName = this.checkpoint.nextBlobName();

        if (nextBlobName == null) {
          logger.log(Level.INFO, "No more blobs to process");
          currentBlobLines = null;
          return null;
        } else {
          logger.log(Level.INFO, "Processing blob: {0}", nextBlobName);
          currentBlobLines = downloadBlobLines(nextBlobName);
          return readItem();
        }
      }
    } else {
      return null;
    }
  }

  private Object parseLine(String line) throws EventLineParseException {
    String[] result = line.split(",");

    if (result.length != 5) {
      throw new EventLineParseException("Wrong number of data elements", line);
    }

    LocalDateTime completionTime = null;

    try {
      completionTime = DateConverter.toDateTime(result[0]);
    } catch (DateTimeParseException e) {
      throw new EventLineParseException("Cannot parse completion time", e, line);
    }

    TrackingId trackingId = null;

    try {
      trackingId = new TrackingId(result[1]);
    } catch (NullPointerException e) {
      throw new EventLineParseException("Cannot parse tracking ID", e, line);
    }

    VoyageNumber voyageNumber = null;

    try {
      if (!result[2].isEmpty()) {
        voyageNumber = new VoyageNumber(result[2]);
      }
    } catch (NullPointerException e) {
      throw new EventLineParseException("Cannot parse voyage number", e, line);
    }

    UnLocode unLocode = null;

    try {
      unLocode = new UnLocode(result[3]);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new EventLineParseException("Cannot parse UN location code", e, line);
    }

    HandlingEvent.Type eventType = null;

    try {
      eventType = HandlingEvent.Type.valueOf(result[4]);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new EventLineParseException("Cannot parse event type", e, line);
    }

    HandlingEventRegistrationAttempt attempt =
        new HandlingEventRegistrationAttempt(
            LocalDateTime.now(), completionTime, trackingId, voyageNumber, eventType, unLocode);

    return attempt;
  }

  @Override
  public Serializable checkpointInfo() throws Exception {
    return this.checkpoint;
  }
}
