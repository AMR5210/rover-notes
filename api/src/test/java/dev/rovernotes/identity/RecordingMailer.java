package dev.rovernotes.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Captures what would have been sent.
 *
 * <p>Most of what these flows guarantee is about the message rather than the response:
 * registering with a taken address and registering with a new one return the same thing,
 * and the only place the difference exists is here. A test that could not read the mail
 * could not tell the two apart either, which would make it agree with an implementation
 * that had stopped sending anything.
 */
class RecordingMailer implements Mailer {

    record Message(String to, String subject, String body) {}

    private final List<Message> sent = new ArrayList<>();

    /**
     * Matches the token on a verification or reset link.
     *
     * <p>The path is the interface's, not the API's. Both links addressed
     * {@code /auth/...} — the endpoints — until it was noticed that those take a JSON body
     * over POST, so a browser following one arrived with a GET and was refused.
     */
    private static final Pattern LINK_TOKEN =
            Pattern.compile("/account/\\w+\\?token=([\\w-]+)");

    @Override
    public synchronized void send(String to, String subject, String body) {
        sent.add(new Message(to, subject, body));
    }

    synchronized void clear() {
        sent.clear();
    }

    synchronized List<Message> sent() {
        return List.copyOf(sent);
    }

    synchronized Optional<Message> lastTo(String address) {
        return sent.reversed().stream().filter(m -> m.to().equalsIgnoreCase(address)).findFirst();
    }

    /** The token from the most recent link sent to an address, as a person would click it. */
    synchronized Optional<String> lastTokenFor(String address) {
        return lastTo(address).flatMap(message -> {
            Matcher matcher = LINK_TOKEN.matcher(message.body());
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        });
    }

    @TestConfiguration
    static class Config {

        @Bean
        @Primary
        RecordingMailer recordingMailer() {
            return new RecordingMailer();
        }
    }
}
