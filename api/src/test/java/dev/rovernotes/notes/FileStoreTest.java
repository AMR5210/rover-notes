package dev.rovernotes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.UUID;

import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Keeping the original of an uploaded file, against a real object store.
 *
 * <p>MinIO in a container rather than a mocked client. What this code does is almost
 * entirely configuration — path-style addressing, credentials, a region the server does
 * not care about — and every one of those is invisible to a mock and fatal in practice.
 * The path-style setting in particular fails as a DNS error against a hostname that does
 * not resolve, which reads as the service being down rather than as a client
 * misconfiguration.
 */
@SpringBootTest
@ActiveProfiles("local")
class FileStoreTest {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> MINIO =
            new GenericContainer<>("minio/minio:latest")
                    .withCommand("server", "/data")
                    .withEnv("MINIO_ROOT_USER", "rovertest")
                    .withEnv("MINIO_ROOT_PASSWORD", "rovertestsecret")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
        registry.add("rover.storage.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("rover.storage.bucket", () -> "rover-test-uploads");
        registry.add("rover.storage.access-key", () -> "rovertest");
        registry.add("rover.storage.secret-key", () -> "rovertestsecret");
    }

    @Autowired
    FileStore files;

    @Value("${rover.storage.bucket}")
    String bucket;

    private S3Client reader() {
        return S3Client.builder()
                .endpointOverride(URI.create(
                        "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("rovertest", "rovertestsecret")))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Test
    void createsTheBucketWhenItIsNotThere() {
        // Otherwise the first upload fails on a setup step nobody performed.
        assertThat(files.ensureBucket()).isTrue();
    }

    @Test
    void aStoredFileCanBeReadBackByte_forByte() {
        files.ensureBucket();
        UUID owner = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        byte[] content = {'%', 'P', 'D', 'F', '-', '1', '.', '4', 0x00, (byte) 0xFF, 0x0A};

        files.put(owner, document, "application/pdf", content);

        try (S3Client s3 = reader()) {
            assertThat(s3.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(owner + "/" + document)
                            .build())
                    .asByteArray()).isEqualTo(content);
        }
    }

    @Test
    void theKeyIsDerivedFromTheOwnerAndDocument() {
        // Not from the filename. Two people uploading report.pdf must not collide, and a
        // filename is caller-supplied text that would otherwise decide a storage path.
        files.ensureBucket();
        UUID owner = UUID.randomUUID();
        UUID document = UUID.randomUUID();

        String uri = files.put(owner, document, "application/pdf", new byte[]{1, 2, 3});

        assertThat(uri).isEqualTo("s3://" + bucket + "/" + owner + "/" + document);
    }

    @Test
    void twoOwnersUploadingTheSameDocumentNameDoNotCollide() {
        files.ensureBucket();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID document = UUID.randomUUID();

        files.put(first, document, "application/pdf", new byte[]{1});
        files.put(second, document, "application/pdf", new byte[]{2});

        try (S3Client s3 = reader()) {
            assertThat(read(s3, first + "/" + document)).isEqualTo(new byte[]{1});
            assertThat(read(s3, second + "/" + document)).isEqualTo(new byte[]{2});
        }
    }

    @Test
    void re_uploadingADocumentReplacesTheStoredOriginal() {
        // The key is stable per document, so a second upload overwrites rather than
        // leaving the old file behind under a key nothing points at.
        files.ensureBucket();
        UUID owner = UUID.randomUUID();
        UUID document = UUID.randomUUID();

        files.put(owner, document, "application/pdf", new byte[]{1});
        files.put(owner, document, "application/pdf", new byte[]{2, 2});

        try (S3Client s3 = reader()) {
            assertThat(read(s3, owner + "/" + document)).isEqualTo(new byte[]{2, 2});
        }
    }

    @Test
    void aKeyThatWasNeverWrittenIsAbsentRatherThanEmpty() {
        files.ensureBucket();

        try (S3Client s3 = reader()) {
            assertThatThrownBy(() -> read(s3, UUID.randomUUID() + "/" + UUID.randomUUID()))
                    .isInstanceOf(NoSuchKeyException.class);
        }
    }

    @Test
    void aStoredFileIsReadBackThroughTheStore() {
        files.ensureBucket();
        UUID owner = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        byte[] content = {'%', 'P', 'D', 'F', 0x00, (byte) 0xFE};

        files.put(owner, document, "application/pdf", content);

        assertThat(files.get(owner, document)).contains(content);
    }

    @Test
    void readingAKeyThatWasNeverWrittenIsEmptyRatherThanAnError() {
        // The ordinary reason for a miss is a document ingested while storage was
        // unavailable — the best-effort write. That is a document without its original,
        // not a fault, and an exception here would make it look like one.
        files.ensureBucket();

        assertThat(files.get(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void oneOwnersFileIsNotReadableUnderAnothersKey() {
        files.ensureBucket();
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        files.put(mine, document, "application/pdf", new byte[]{1});

        assertThat(files.get(theirs, document)).isEmpty();
    }

    private byte[] read(S3Client s3, String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build()).asByteArray();
    }
}
