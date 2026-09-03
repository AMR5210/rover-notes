package dev.rovernotes.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends the messages the credential flows depend on.
 *
 * <p>Verification and reset are only as trustworthy as the delivery underneath them: a
 * reset link that is not delivered is an account nobody can recover, and one delivered to
 * the wrong address is an account anybody can take. That makes mail a dependency of this
 * system rather than a detail of it, which is why it is an interface with two deliberately
 * different implementations rather than a call to a library.
 */
interface Mailer {

    void send(String to, String subject, String body);
}

/**
 * Writes the message to the log instead of sending it.
 *
 * <p>Local development only. Printing a reset link is exactly what an attacker with log
 * access would want, which is why this cannot be reached outside the profile that also
 * disables authentication entirely.
 */
@Component
@Profile("local")
class LoggingMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailer.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("""
                Mail not sent (local profile). To: {}
                Subject: {}
                {}""", to, subject, body);
    }
}

/**
 * Sends over SMTP.
 *
 * <p>Requires a configured {@code JavaMailSender}, which Boot creates only when
 * {@code spring.mail.host} is set. Without it the application does not start, which is the
 * intended behaviour: a deployment whose mail is misconfigured should say so on boot rather
 * than at the first password reset somebody needs.
 */
@Component
@Profile("!local")
class SmtpMailer implements Mailer {

    private final JavaMailSender sender;
    private final String from;

    SmtpMailer(JavaMailSender sender,
               @org.springframework.beans.factory.annotation.Value("${rover.identity.mail-from}")
               String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }
}
