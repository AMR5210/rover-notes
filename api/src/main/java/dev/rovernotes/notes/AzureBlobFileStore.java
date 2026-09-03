package dev.rovernotes.notes;

import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;

import com.azure.core.util.BinaryData;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link FileStore} over Azure Blob Storage.
 *
 * <p>Azure Blob does not speak the S3 API, so this is a second client and not an endpoint
 * change. The two implementations share a key format, so a document is addressed the same
 * way under either.
 *
 * <p><strong>Credentials.</strong> A connection string is used when one is configured,
 * which is what a local run against Azurite needs. Otherwise the account URL is combined
 * with {@code DefaultAzureCredential}, which resolves the container app's managed identity
 * at runtime. The managed-identity path is the one a deployment uses: it keeps the storage
 * key out of configuration, out of the image, and out of the environment, so there is no
 * credential to rotate or to leak.
 */
public class AzureBlobFileStore implements FileStore {

    private static final Logger log = LoggerFactory.getLogger(AzureBlobFileStore.class);

    private final BlobContainerClient container;
    private final String containerName;

    public AzureBlobFileStore(String accountUrl, String connectionString, String containerName) {
        this.containerName = containerName;

        BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
        if (connectionString != null && !connectionString.isBlank()) {
            builder.connectionString(connectionString);
        } else {
            builder.endpoint(accountUrl).credential(new DefaultAzureCredentialBuilder().build());
        }

        BlobServiceClient service = builder.buildClient();
        this.container = service.getBlobContainerClient(containerName);
    }

    @Override
    public String put(UUID ownerId, UUID documentId, String contentType, byte[] content) {
        String key = FileStore.key(ownerId, documentId);
        var blob = container.getBlobClient(key);
        // Overwrite permitted: re-ingesting the same document writes the same key, and the
        // alternative is an upload that fails the second time a file is corrected.
        blob.upload(BinaryData.fromBytes(content), true);
        blob.setHttpHeaders(new BlobHttpHeaders().setContentType(contentType));
        return blob.getBlobUrl();
    }

    @Override
    public Optional<byte[]> get(UUID ownerId, UUID documentId) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            container.getBlobClient(FileStore.key(ownerId, documentId)).downloadStream(buffer);
            return Optional.of(buffer.toByteArray());
        } catch (BlobStorageException e) {
            if (e.getErrorCode() == BlobErrorCode.BLOB_NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public boolean ensureBucket() {
        try {
            if (!container.exists()) {
                container.create();
                log.info("created blob container '{}'", containerName);
            }
            return true;
        } catch (RuntimeException e) {
            // Same posture as the S3 implementation: reported and not thrown, because
            // ingestion works without the original file and refusing to start over
            // storage would take down a service whose main paths do not need it.
            log.warn("blob storage is unavailable ({}); uploads will not keep the original",
                    e.getMessage());
            return false;
        }
    }
}
