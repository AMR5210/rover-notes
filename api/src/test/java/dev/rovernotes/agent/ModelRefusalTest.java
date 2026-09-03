package dev.rovernotes.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnexpectedStatusCodeException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * What each refusal from the model provider means for the caller.
 *
 * <p>The bodies are the shapes the API actually sends, kept literal rather than built from
 * the code under test. The billing one is the exact response a live request returned
 * against an account with no credit on it, which is what makes the classification here
 * checkable rather than a guess about a case nobody had seen.
 *
 * <p>The exceptions are real SDK objects rather than mocks, because what is being read —
 * {@code statusCode()}, {@code errorType()}, the {@code retry-after} header — is the SDK's
 * own parsing of a response, and a mock would agree with whatever this project believed
 * that parsing to be.
 */
class ModelRefusalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Verbatim from a live 400, request id req_011CePbjaNJwP9HEdWP8mZ9n. */
    private static final String NO_CREDIT = """
            {"type":"error","error":{"type":"invalid_request_error",\
            "message":"Your credit balance is too low to access the Anthropic API. \
            Please go to Plans & Billing to upgrade or purchase credits."}}""";

    private static JsonValue body(String json) {
        try {
            return JsonValue.fromJsonNode(JSON.readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    private static JsonValue errorBody(String type, String message) {
        return body("""
                {"type":"error","error":{"type":"%s","message":"%s"}}"""
                .formatted(type, message));
    }

    @Test
    void anExhaustedBalanceIsNotThisServicesFault() {
        // The failure that prompted this class. It arrives as a 400, which the default
        // handling turns into a 500 — a status saying this service is broken and the
        // request should not be repeated, when in fact somebody needs to top up an account.
        var refusal = ModelRefusal.of(
                BadRequestException.builder().headers(Headers.builder().build())
                        .body(body(NO_CREDIT)).build());

        assertThat(refusal).isPresent();
        assertThat(refusal.get().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refusal.get().reason()).contains("credit");
    }

    @Test
    void theProvidersOwnSentenceIsCarriedRatherThanSummarised() {
        // "the model provider is unavailable" describes an exhausted balance, an expired
        // key and a regional outage identically. The sentence that separates them has
        // already been sent, and reading it should not require the API's log.
        var refusal = ModelRefusal.of(
                BadRequestException.builder().headers(Headers.builder().build())
                        .body(body(NO_CREDIT)).build());

        assertThat(refusal.orElseThrow().detail())
                .isEqualTo("Your credit balance is too low to access the Anthropic API. "
                        + "Please go to Plans & Billing to upgrade or purchase credits.");
    }

    @Test
    void aBillingErrorTypeIsRecognisedWithoutMatchingItsWording() {
        // The SDK defines BILLING_ERROR, and the response a live exhausted balance sends
        // does not use it — it sends invalid_request_error. Both are handled, so the
        // classification does not depend on a sentence continuing to be worded the same
        // way. This body carries no "credit balance" text, which is what isolates the
        // type branch from the text one.
        var refusal = ModelRefusal.of(
                BadRequestException.builder().headers(Headers.builder().build())
                        .body(errorBody("billing_error", "payment required")).build());

        assertThat(refusal).isPresent();
        assertThat(refusal.get().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aMalformedRequestKeepsIts500() {
        // A 400 that is not about billing means this code built a request the API would
        // not accept. That is a defect here, and answering 503 would file it under the
        // provider's problems and stop anyone looking.
        var refusal = ModelRefusal.of(
                BadRequestException.builder().headers(Headers.builder().build())
                        .body(errorBody("invalid_request_error",
                                "messages.0.content.0.text: Field required")).build());

        assertThat(refusal).isEmpty();
    }

    @Test
    void aRateLimitIsRetryableAndCarriesTheWaitTheProviderChose() {
        var refusal = ModelRefusal.of(
                RateLimitException.builder()
                        .headers(Headers.builder().put("retry-after", "30").build())
                        .body(errorBody("rate_limit_error", "slow down")).build());

        assertThat(refusal).isPresent();
        assertThat(refusal.get().status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refusal.get().retryAfterSeconds()).isEqualTo(30L);
    }

    @Test
    void aRateLimitWithoutAWaitDoesNotInventOne() {
        // A fabricated wait is worse than none: a client honours it as though the provider
        // had chosen it, and every capped caller then retries on the same schedule.
        var refusal = ModelRefusal.of(
                RateLimitException.builder().headers(Headers.builder().build())
                        .body(errorBody("rate_limit_error", "slow down")).build());

        assertThat(refusal.orElseThrow().retryAfterSeconds()).isNull();
    }

    @Test
    void aRetryAfterGivenAsADateIsNotReportedAsSeconds() {
        // Retry-After permits an HTTP date as well as a delay. Parsed loosely, the leading
        // digits of "Wed, 21 Oct 2015 07:28:00 GMT" would become a 21-second wait.
        var refusal = ModelRefusal.of(
                RateLimitException.builder()
                        .headers(Headers.builder()
                                .put("retry-after", "Wed, 21 Oct 2015 07:28:00 GMT").build())
                        .body(errorBody("rate_limit_error", "slow down")).build());

        assertThat(refusal.orElseThrow().retryAfterSeconds()).isNull();
    }

    @Test
    void anOverloadedProviderIsRetryable() {
        // 529 is the status the API uses when it is overloaded, and it is not one of the
        // SDK's named exceptions — it arrives as an unexpected status code.
        var refusal = ModelRefusal.of(
                UnexpectedStatusCodeException.builder().statusCode(529)
                        .headers(Headers.builder().build())
                        .body(errorBody("overloaded_error", "Overloaded")).build());

        assertThat(refusal.orElseThrow().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aFaultInsideTheProviderIsNotReportedAsAFaultHere() {
        var refusal = ModelRefusal.of(
                InternalServerException.builder().statusCode(500)
                        .headers(Headers.builder().build())
                        .body(errorBody("api_error", "Internal server error")).build());

        assertThat(refusal.orElseThrow().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void refusedCredentialsAreADeploymentStateRatherThanACallersMistake() {
        // Nothing the caller sends can fix an expired key, so 401 must not be passed on as
        // one: it would tell them to authenticate against this service, which they did.
        var refusal = ModelRefusal.of(
                UnauthorizedException.builder().headers(Headers.builder().build())
                        .body(errorBody("authentication_error", "invalid x-api-key")).build());

        assertThat(refusal).isPresent();
        assertThat(refusal.get().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refusal.get().reason()).contains("credentials");
    }

    @Test
    void aRefusalWithNoReadableBodyStillSaysSomething() {
        // A detail of null or "" would render as an empty line where the reason should be.
        AnthropicServiceException empty = InternalServerException.builder().statusCode(503)
                .headers(Headers.builder().build()).body(body("{}")).build();

        assertThat(ModelRefusal.of(empty).orElseThrow().detail()).isNotBlank();
    }
}
