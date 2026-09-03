package dev.rovernotes.notes;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Creates the upload bucket once, when the application is ready.
 *
 * <p>A bucket that has to be made by hand is a setup step nobody performs until the first
 * upload fails, and checking on every upload spends a round trip on a question whose
 * answer does not change. Doing it after startup rather than in the store's constructor
 * keeps object storage being unreachable from preventing the service from starting.
 */
@Component
@ConditionalOnProperty(name = "rover.storage.enabled", havingValue = "true", matchIfMissing = true)
class FileStoreStartup {

    private final FileStore files;

    FileStoreStartup(FileStore files) {
        this.files = files;
    }

    @EventListener(ApplicationReadyEvent.class)
    void prepareBucket() {
        files.ensureBucket();
    }
}
