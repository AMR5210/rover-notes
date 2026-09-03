package dev.rovernotes;

/**
 * How often a caller may ask, as the filter needs to know it.
 *
 * <p>Declared here rather than in the module that implements it, and that placement is
 * load-bearing. The {@code usage} module already depends on this package for
 * {@link CurrentOwner}; a filter in this package depending on {@code usage} in return
 * would close a cycle, which {@code ModularityTests} refuses on every build. An interface
 * on this side keeps the arrow pointing one way: this package says what it needs, and
 * {@code usage} — which owns everything else about what a caller has spent — provides it.
 *
 * <p>The split also falls where the knowledge does. Which bucket a request belongs in is a
 * question about paths and methods, which the filter can see and the accounting cannot.
 * What a bucket allows is a question about cost, which is the other way round.
 */
public interface RequestLimits {

    /** Counted per caller: searches, answers, and anything else read through the API. */
    String API = "api";

    /**
     * Counted separately because a write costs more than a read.
     *
     * <p>A note that is written is chunked and embedded, which is one call to the embedding
     * server per batch of chunks. That server is already the largest single contributor to
     * read latency under load, so a caller writing quickly slows down everyone's searches
     * in a way a caller reading quickly does not.
     */
    String INGEST = "ingest";

    /**
     * Counted by client address, since these are the endpoints answering someone with no
     * account.
     *
     * <p>Registration hashes a password at Argon2's 19 MiB baseline and sends mail; a
     * password reset sends mail to an address the caller names; open client registration
     * writes a row. None is expensive enough to matter once and all are worth something to
     * an abuser in bulk.
     *
     * <p>This is a different protection from the per-account lockout. That one stops a
     * password being guessed against one account; this one stops one address being tried
     * against many, which the lockout cannot see.
     */
    String AUTH = "auth";

    /**
     * Takes one request's worth of allowance from a caller's bucket.
     *
     * @param bucket  which limit; an unknown name is treated as the general one
     * @param subject an account id, or a client address where there is no account yet
     */
    Decision take(String bucket, String subject);

    /** Whether the request may proceed, with the wait to report when it may not. */
    record Decision(boolean allowed, long retryAfterSeconds) {

        public static Decision allow() {
            return new Decision(true, 0);
        }

        public static Decision refuse(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }
}
