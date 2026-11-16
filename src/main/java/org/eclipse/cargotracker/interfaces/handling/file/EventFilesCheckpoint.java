package org.eclipse.cargotracker.interfaces.handling.file;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

/**
 * Checkpoint for the event-file batch job.
 *
 * <p>Tracks the list of Azure Blob names to process, the current blob index, and the current
 * line pointer within the active blob. This replaces the previous {@code java.io.File}-based
 * checkpoint so that the batch job can resume from the correct position after a restart without
 * any dependency on the local file system.
 */
public class EventFilesCheckpoint implements Serializable {

  private static final long serialVersionUID = 2L;

  /** Ordered list of blob names available in the upload container. */
  private List<String> blobNames = new LinkedList<>();

  /** Index of the blob currently being processed. */
  private int blobIndex = 0;

  /**
   * Line pointer within the current blob (0-based index into the list of lines downloaded from
   * the blob). Replaces the previous {@code RandomAccessFile} byte-offset pointer.
   */
  private long filePointer = 0;

  // -------------------------------------------------------------------------
  // Accessors
  // -------------------------------------------------------------------------

  public void setBlobNames(List<String> blobNames) {
    this.blobNames = blobNames;
  }

  public long getFilePointer() {
    return filePointer;
  }

  public void setFilePointer(long filePointer) {
    this.filePointer = filePointer;
  }

  // -------------------------------------------------------------------------
  // Navigation
  // -------------------------------------------------------------------------

  /**
   * Returns the name of the blob currently being processed, or {@code null} when all blobs have
   * been consumed.
   */
  public String currentBlobName() {
    if (blobNames.size() > blobIndex) {
      return blobNames.get(blobIndex);
    }
    return null;
  }

  /**
   * Advances to the next blob, resets the line pointer, and returns the new blob name, or
   * {@code null} when there are no more blobs to process.
   */
  public String nextBlobName() {
    filePointer = 0;
    if (blobNames.size() > ++blobIndex) {
      return blobNames.get(blobIndex);
    }
    return null;
  }
}
