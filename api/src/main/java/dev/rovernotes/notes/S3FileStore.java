package dev.rovernotes.notes;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@link FileStore} over the S3 API: MinIO locally, and any S3-compatible service in a
 * deployment. The two differ by an endpoint and a credential, which is the reason for
 * using the S3 API and not MinIO's own client.
 *
 * <p>Path-style addressing is forced. Virtual-host addressing puts the bucket in the
 * hostname, which requires DNS that resolves {@code bucket.localhost} — it does not, and
 * the failure is a connection error that reads as the service being down.
 *
 * <p>This is the default provider, so a local checkout needs no storage configuration.
 */
public class S3FileStore implements FileStore {

    private static final Logger log = LoggerFactory.getLogger(S3FileStore.class);

    private final S3Client s3;
    private final String bucket;

    public S3FileStore(String endpoint, String bucket, String accessKey,
                       String secretKey, String region) {
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Override
    public String put(UUID ownerId, UUID documentId, String contentType, byte[] content) {
        String key = FileStore.key(ownerId, documentId);
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(content));
        return "s3://%s/%s".formatted(bucket, key);
    }

    @Override
    public Optional<byte[]> get(UUID ownerId, UUID documentId) {
        try {
            return Optional.of(s3.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(FileStore.key(ownerId, documentId))
                            .build())
                    .asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("created object storage bucket '{}'", bucket);
            return true;
        } catch (S3Exception | software.amazon.awssdk.core.exception.SdkClientException e) {
            // Reported and not thrown. Ingestion works without the original file, and
            // refusing to start over storage being unreachable would take the whole
            // service down for a feature that degrades cleanly.
            log.warn("object storage is unavailable ({}); uploads will not keep the original",
                    e.getMessage());
            return false;
        }
    }
}
