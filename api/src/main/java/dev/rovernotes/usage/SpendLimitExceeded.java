package dev.rovernotes.usage;

import java.math.BigDecimal;

/**
 * Raised when an owner's spend inside the window has reached their cap.
 *
 * <p>Carries the numbers a caller needs to explain the refusal rather than only the fact
 * of it: what was spent, what the limit is, and how long until enough of that spend ages
 * out of the window.
 *
 * <p>The wait is in seconds rather than as an instant. A client comparing an absolute
 * timestamp against its own clock is comparing two clocks, and the difference between
 * them is exactly the error that makes a retry either premature or late.
 */
public class SpendLimitExceeded extends RuntimeException {

    private final BigDecimal spent;
    private final BigDecimal cap;
    private final long retryAfterSeconds;

    public SpendLimitExceeded(BigDecimal spent, BigDecimal cap, long retryAfterSeconds) {
        super("spend of %s reached the cap of %s; retry in %ds"
                .formatted(spent.toPlainString(), cap.toPlainString(), retryAfterSeconds));
        this.spent = spent;
        this.cap = cap;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public BigDecimal spent() {
        return spent;
    }

    public BigDecimal cap() {
        return cap;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
