package dev.rovernotes.agent;

import java.util.Optional;

import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.ErrorType;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;

/**
 * What a refusal from the model provider means for the caller who asked the question.
 *
 * <p>Spring AI does not wrap the SDK's exceptions — {@code AnthropicChatModel} lets
 * {@link AnthropicServiceException} propagate — so without a decision here every refusal
 * reaches the caller as 500. That status says this service is at fault and that the
 * request should not be repeated, and for most refusals both halves are wrong: a rate
 * limit is worth retrying after a wait, an exhausted balance is a billing state rather
 * than a defect, and an overloaded provider recovers on its own.
 *
 * <p>The cost of collapsing them was measured rather than argued: the generation eval
 * reported {@code Server error '500'} for every question in a run, and the reason — no
 * credit on the account — was only visible by reading eighty lines of the API's log. One
 * status and one sentence in the body would have said it on the first line.
 *
 * <h2>What stays a 500</h2>
 *
 * <p>A 400 or 422 that is not about billing means this code built a request the API would
 * not accept, which is a defect here and belongs in the logs as one. Mapping every 4xx to
 * 503 would hide that behind a status suggesting the provider was at fault, so anything
 * not matched below is left to the default handling unchanged.
 */
record ModelRefusal(HttpStatus status, String reason, String detail, Long retryAfterSeconds) {

    /**
     * The part of the billing message that is stable enough to match on.
     *
     * <p>Matched on text because the API does not distinguish this case by type. The SDK
     * defines {@link ErrorType#BILLING_ERROR}, but the response an exhausted balance
     * actually produces carries {@code "type": "invalid_request_error"} — checked against
     * the live API, whose body was
     * {@code {"type":"invalid_request_error","message":"Your credit balance is too low to
     * access the Anthropic API..."}}. Both are handled: the type when it is sent, and this
     * substring for the response that is sent today.
     */
    private static final String BILLING_TEXT = "credit balance";

    /**
     * Classifies a refusal, or reports that it is this service's own fault.
     *
     * <p>An empty result means the request was rejected for something this code did, and
     * the caller should keep whatever handling it already had.
     */
    static Optional<ModelRefusal> of(AnthropicServiceException e) {
        int status = e.statusCode();
        String detail = detailOf(e);

        if (status == 429) {
            return Optional.of(new ModelRefusal(HttpStatus.TOO_MANY_REQUESTS,
                    "the model provider is rate limiting this deployment", detail,
                    retryAfterSeconds(e)));
        }
        if (status >= 500) {
            return Optional.of(unavailable(
                    "the model provider is unavailable", detail));
        }
        if (isBilling(e, detail)) {
            return Optional.of(unavailable(
                    "this deployment has no model credit", detail));
        }
        if (status == 401 || status == 403) {
            return Optional.of(unavailable(
                    "the model provider refused this deployment's credentials", detail));
        }
        return Optional.empty();
    }

    private static ModelRefusal unavailable(String reason, String detail) {
        return new ModelRefusal(HttpStatus.SERVICE_UNAVAILABLE, reason, detail, null);
    }

    private static boolean isBilling(AnthropicServiceException e, String detail) {
        return e.errorType().filter(ErrorType.BILLING_ERROR::equals).isPresent()
                || detail.toLowerCase(java.util.Locale.ROOT).contains(BILLING_TEXT);
    }

    /**
     * The provider's own sentence, carried through rather than discarded.
     *
     * <p>The same decision the parsing client makes with its 422 detail, for the same
     * reason: "the model provider is unavailable" describes an exhausted balance, an
     * expired key and a regional outage identically, and the sentence that separates them
     * has already been sent.
     */
    private static String detailOf(AnthropicServiceException e) {
        try {
            JsonValue body = e.body();
            JsonNode node = body.convert(JsonNode.class);
            if (node != null) {
                String message = node.path("error").path("message").asText("");
                if (!message.isBlank()) {
                    return message;
                }
            }
        } catch (RuntimeException ignored) {
            // Falls through to the exception's own text, which embeds the same body.
        }
        String message = e.getMessage();
        return message == null ? "no detail given" : message;
    }

    /**
     * How long the provider asked the caller to wait, when it said.
     *
     * <p>Null rather than a guessed default: a fabricated wait is worse than none, because
     * a client honours it as though the provider had chosen it.
     */
    private static Long retryAfterSeconds(AnthropicServiceException e) {
        for (String value : e.headers().values("retry-after")) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ignored) {
                // Retry-After also permits an HTTP date, which is not what this reports.
            }
        }
        return null;
    }
}
