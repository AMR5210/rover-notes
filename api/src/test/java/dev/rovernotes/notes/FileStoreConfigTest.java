package dev.rovernotes.notes;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Which object store the application selects.
 *
 * <p>Worth pinning because the failure is silent. Selecting the wrong provider gives a
 * context that starts, endpoints that answer, and uploads whose originals go to a store
 * nobody reads back from — visible only when somebody follows a citation to a file.
 *
 * <p>No network is involved. Both clients are constructed lazily, so building one proves
 * the wiring without reaching a storage account.
 */
class FileStoreConfigTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(FileStoreConfig.class)
            .withPropertyValues(
                    "rover.storage.endpoint=http://localhost:9000",
                    "rover.storage.bucket=rover-documents",
                    "rover.storage.access-key=local",
                    "rover.storage.secret-key=localsecret");

    @Test
    void defaultsToS3SoALocalCheckoutNeedsNoStorageConfiguration() {
        context.run(ctx -> assertThat(ctx).getBean(FileStore.class).isInstanceOf(S3FileStore.class));
    }

    @Test
    void selectsAzureBlobWhenTheProviderSaysSo() {
        context.withPropertyValues(
                        "rover.storage.provider=azure-blob",
                        "rover.storage.account-url=https://example.blob.core.windows.net")
                .run(ctx -> assertThat(ctx).getBean(FileStore.class)
                        .isInstanceOf(AzureBlobFileStore.class));
    }

    @Test
    void exactlyOneStoreIsDefined() {
        // Both beans returning the same type would otherwise fail injection at startup
        // instead of here, and the two conditions are what keep that from happening.
        context.withPropertyValues("rover.storage.provider=azure-blob",
                        "rover.storage.account-url=https://example.blob.core.windows.net")
                .run(ctx -> assertThat(ctx).getBeans(FileStore.class).hasSize(1));
    }

    @Test
    void definesNoStoreWhenStorageIsSwitchedOff() {
        // NoteController resolves this through an ObjectProvider, so no bean means uploads
        // keep working without an original to hand back.
        context.withPropertyValues("rover.storage.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FileStore.class));
    }

    @Test
    void bothStoresAddressADocumentIdentically() {
        // The key format is shared, so a corpus written under one provider is readable
        // under the other after a migration.
        var owner = java.util.UUID.randomUUID();
        var document = java.util.UUID.randomUUID();

        assertThat(FileStore.key(owner, document)).isEqualTo(owner + "/" + document);
    }
}
