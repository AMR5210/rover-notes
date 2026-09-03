package dev.rovernotes.agent;

import java.math.BigDecimal;
import java.util.Optional;

import com.anthropic.errors.AnthropicServiceException;

import dev.rovernotes.CurrentOwner;
import dev.rovernotes.usage.SpendLimitExceeded;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Question answering over the indexed corpus.
 *
 * <p>Returns the answer alongside its numbered citations, so a client can render each
 * bracketed reference as a link into the exact span of its source document.
 */
@RestController
@RequestMapping("/api/ask")
class AskController {

    private static final Logger log = LoggerFactory.getLogger(AskController.class);

    private final AnswerService answers;
    private final AgentAnswerService agent;
    private final CurrentOwner owner;
    private final boolean agentByDefault;

    AskController(AnswerService answers, AgentAnswerService agent, CurrentOwner owner,
                  @org.springframework.beans.factory.annotation.Value(
                          "${rover.agent.enabled:false}") boolean agentByDefault) {
        this.answers = answers;
        this.agent = agent;
        this.owner = owner;
        this.agentByDefault = agentByDefault;
    }

    /**
     * One question, answered by whichever path is asked for.
     *
     * <p>{@code ?agent=true} runs the loop instead of the single retrieval pass. Requested
     * per query rather than switched on globally, so the generation eval can score both
     * over the same questions in one run — the shape {@code ?rerank=} and {@code ?route=}
     * already use, and the reason each of those moved or stayed on measured evidence
     * rather than on how promising it sounded.
     */
    @PostMapping
    AnswerService.Answer ask(@RequestBody AskRequest request,
                             @RequestParam(required = false) Boolean agent) {
        boolean useAgent = agent == null ? agentByDefault : agent;
        return useAgent
                ? this.agent.answer(owner.id(), request.question())
                : answers.answer(owner.id(), request.question());
    }

    /**
     * The same answer as a stream of server-sent events.
     *
     * <p>Four event types. {@code citations} arrives first and carries the whole list,
     * because the passages are chosen before generation starts — a client can render every
     * citation control before the first word, so a bracketed reference is followable as it
     * appears. {@code delta} carries each piece of text. {@code done} closes the answer,
     * which a client needs because an SSE stream ending and a connection dropping look the
     * same from the other side.
     *
     * <p>{@code error} carries a refusal from the model provider, and it exists because a
     * status cannot. The response is committed with 200 and the citations before the first
     * token is asked for, so a refusal arriving mid-generation cannot change the status the
     * client already has — it ended the stream, and an SSE stream ending is what success
     * looks like from the other side. The client saw a short answer and no reason for it.
     * Sent before {@code done} rather than instead of it, so a client that closes on
     * {@code done} still closes.
     *
     * <p>A separate path rather than content negotiation on {@code /api/ask}. Two handlers
     * differing only by {@code produces} are chosen by the {@code Accept} header, and a
     * caller sending a wildcard, which is what most HTTP clients default to and what the
     * eval harness sends, would get whichever the framework happened to prefer.
     *
     * <p>Deltas are JSON objects rather than bare strings so that a newline inside the
     * answer survives. SSE frames text by line, and a raw multi-line payload arrives as
     * several {@code data:} lines that a client has to rejoin correctly to reproduce the
     * answer exactly.
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<Flux<ServerSentEvent<Object>>> stream(@RequestBody AskRequest request) {
        // Resolved on the request thread: the owner comes from the security context, which
        // is not attached to whatever thread the model's chunks arrive on.
        AnswerService.Stream answer = answers.stream(owner.id(), request.question());

        Flux<ServerSentEvent<Object>> events = Flux.concat(
                Flux.just(event("citations", answer.citations())),
                answer.text()
                        .map(chunk -> event("delta", new Delta(chunk)))
                        .onErrorResume(AnthropicServiceException.class,
                                failure -> Flux.just(event("error", refusalBody(failure)))),
                Flux.just(event("done", new Delta(""))));

        // no-transform asks intermediaries not to re-encode the body. Without it the
        // development proxy gzips the stream, and a compressor holds bytes back until it
        // has enough to be worth emitting: the browser received the whole answer as one
        // 2,606-byte chunk after 4.6 s, where curl, which does not request compression,
        // saw the same answer arrive in pieces. The stream was correct and the transport
        // was undoing it.
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(events);
    }

    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder().event(name).data(data).build();
    }

    /**
     * Answers a capped owner with 429 and the numbers behind the refusal.
     *
     * <p>{@code Retry-After} carries the same wait as the body, in seconds, because a
     * client comparing an absolute timestamp against its own clock is comparing two
     * clocks. Both are sent: the header is what a generic HTTP client honours, and the
     * body is what an interface can explain to a person.
     *
     * <p>The content type is set rather than negotiated. A client of the streaming
     * endpoint sends {@code Accept: text/event-stream}, and a JSON body cannot be written
     * against that: the refusal came back as 500 with the retry header attached, which
     * tells a caller to retry a request that did not fail. A refusal is not the media type
     * the request asked for, and saying so explicitly is what makes the status readable on
     * both endpoints.
     */
    @ExceptionHandler(SpendLimitExceeded.class)
    ResponseEntity<CapReached> capReached(SpendLimitExceeded exceeded) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exceeded.retryAfterSeconds()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CapReached(exceeded.spent(), exceeded.cap(),
                        exceeded.retryAfterSeconds()));
    }

    /**
     * Answers a refusal from the model provider with what it means for the caller.
     *
     * <p>Without this every refusal is a 500, which tells a caller that this service
     * failed and that repeating the request is pointless. Neither is true of a rate limit,
     * an exhausted balance or an overloaded provider, and the three are not distinguishable
     * from a 500 either — the generation eval reported {@code Server error '500'} for a
     * whole run whose only fault was an account with no credit on it.
     *
     * <p>A refusal that {@link ModelRefusal} does not recognise is a request this code
     * built and the API would not accept, which is a defect here. It keeps its 500, and is
     * logged with its stack trace rather than only summarised into the body, because that
     * trace is what locates the request that was wrong.
     *
     * <p>The content type is set for the reason the spend-cap handler above sets it: a
     * caller of the streaming endpoint sends {@code Accept: text/event-stream}, against
     * which a JSON body cannot be written, and the refusal would come back as the 500 it
     * was replacing.
     */
    @ExceptionHandler(AnthropicServiceException.class)
    ResponseEntity<Refused> modelRefused(AnthropicServiceException failure) {
        Optional<ModelRefusal> refusal = ModelRefusal.of(failure);

        if (refusal.isEmpty()) {
            log.error("the model provider rejected a request this service built", failure);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Refused("the request this service sent was rejected",
                            failure.getMessage(), null));
        }

        ModelRefusal refused = refusal.get();
        log.warn("model provider refused with {}: {}", refused.status(), refused.reason());

        ResponseEntity.BodyBuilder response = ResponseEntity.status(refused.status())
                .contentType(MediaType.APPLICATION_JSON);
        if (refused.retryAfterSeconds() != null) {
            response = response.header(HttpHeaders.RETRY_AFTER,
                    Long.toString(refused.retryAfterSeconds()));
        }
        return response.body(refusalBody(failure));
    }

    private static Refused refusalBody(AnthropicServiceException failure) {
        return ModelRefusal.of(failure)
                .map(r -> new Refused(r.reason(), r.detail(), r.retryAfterSeconds()))
                .orElseGet(() -> new Refused("the request this service sent was rejected",
                        failure.getMessage(), null));
    }

    record AskRequest(@NotBlank String question) {}

    /**
     * Why the question could not be answered, in the provider's words as well as this
     * service's.
     *
     * <p>{@code detail} is the sentence the provider sent. Carried rather than dropped
     * because {@code reason} alone describes an exhausted balance, an expired key and a
     * regional outage identically.
     */
    record Refused(String reason, String detail, Long retryAfterSeconds) {}

    record CapReached(BigDecimal spentUsd, BigDecimal capUsd, long retryAfterSeconds) {}

    record Delta(String text) {}
}
