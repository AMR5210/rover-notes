package dev.rovernotes.notes;

import java.util.Optional;
import java.util.UUID;

/**
 * Keeps the original of an uploaded file.
 *
 * <p>The extracted text is what gets indexed and answered from; the original is what a
 * reader is handed when they follow a citation. "Page 34" is only actionable against the
 * document that has a page 34, and the extracted text has pages only as character ranges.
 *
 * <p>Two implementations back this. {@link S3FileStore} speaks the S3 API, which covers
 * MinIO locally and anything S3-compatible in a deployment. {@link AzureBlobFileStore}
 * speaks Azure Blob, which is not S3-compatible and needs its own client. One is selected
 * by {@code rover.storage.provider}.
 *
 * <p>Optional. With {@code rover.storage.enabled} false there is no bean at all and
 * uploads keep working without an original to hand back. Losing the ability to ingest a
 * document because object storage is unavailable would be a worse failure than not
 * keeping the file.
 */
public interface FileStore {

    /**
     * Stores a file and returns the URI recorded against its document.
     *
     * <p>The key is derived from the owner and the document, not from the filename. Two
     * people uploading {@code report.pdf} must not collide, and a filename is
     * caller-supplied text that would otherwise decide a storage path.
     */
    String put(UUID ownerId, UUID documentId, String contentType, byte[] content);

    /**
     * Reads a stored file back, or empty when there is nothing under that key.
     *
     * <p>Empty rather than an exception for a missing object: the ordinary reason for one
     * is a document ingested while storage was unavailable, which is a document without
     * its original and not an error.
     */
    Optional<byte[]> get(UUID ownerId, UUID documentId);

    /**
     * Creates the container if it is absent, and reports whether storage is reachable.
     *
     * <p>Called once at startup. A container that has to be created by hand before the
     * first upload works is a setup step nobody performs until the first upload fails, and
     * checking on every upload spends a round trip on a question whose answer does not
     * change.
     *
     * @return true when the container exists and can be written to
     */
    boolean ensureBucket();

    /** The key both implementations store under, so a document is addressed identically. */
    static String key(UUID ownerId, UUID documentId) {
        return "%s/%s".formatted(ownerId, documentId);
    }
}
