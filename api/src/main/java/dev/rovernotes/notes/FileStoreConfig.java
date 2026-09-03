package dev.rovernotes.notes;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the object store.
 *
 * <p>{@code rover.storage.provider} chooses between the two implementations and defaults
 * to {@code s3}, so a local checkout running MinIO needs no storage configuration at all.
 * A deployment on Azure sets it to {@code azure-blob}.
 *
 * <p>Both beans sit behind {@code rover.storage.enabled}. With storage off there is no
 * bean, {@code NoteController} finds none through its {@code ObjectProvider}, and uploads
 * keep working without an original to hand back.
 */
@Configuration
@ConditionalOnProperty(name = "rover.storage.enabled", havingValue = "true", matchIfMissing = true)
class FileStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "rover.storage.provider", havingValue = "s3",
            matchIfMissing = true)
    FileStore s3FileStore(@Value("${rover.storage.endpoint}") String endpoint,
                          @Value("${rover.storage.bucket}") String bucket,
                          @Value("${rover.storage.access-key}") String accessKey,
                          @Value("${rover.storage.secret-key}") String secretKey,
                          @Value("${rover.storage.region:us-east-1}") String region) {
        return new S3FileStore(endpoint, bucket, accessKey, secretKey, region);
    }

    /**
     * @param accountUrl       {@code https://<account>.blob.core.windows.net}, used with
     *                         the container app's managed identity
     * @param connectionString set only for a local run against Azurite; a deployment
     *                         leaves it empty so no storage key exists to leak
     */
    @Bean
    @ConditionalOnProperty(name = "rover.storage.provider", havingValue = "azure-blob")
    FileStore azureBlobFileStore(
            @Value("${rover.storage.account-url:}") String accountUrl,
            @Value("${rover.storage.connection-string:}") String connectionString,
            @Value("${rover.storage.bucket}") String container) {
        return new AzureBlobFileStore(accountUrl, connectionString, container);
    }
}
