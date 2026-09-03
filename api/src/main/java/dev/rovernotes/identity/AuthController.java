package dev.rovernotes.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints an account is created and recovered through.
 *
 * <p>Mounted at {@code /auth} rather than under {@code /api}, because these are the only
 * endpoints in the system that must answer a caller with no identity. Keeping them outside
 * that prefix means {@code /api/**} stays uniformly authenticated and the exception is
 * visible in the path rather than buried in a matcher.
 *
 * <p>Every response here is deliberately uninformative. Registration returns 202 whether or
 * not an account was created, a reset request returns 202 whether or not the address is
 * known, and neither says which. The work is real either way; what differs is the message
 * sent, and only the holder of the mailbox sees that.
 */
@RestController
@RequestMapping("/auth")
class AuthController {

    private final RegistrationService registration;

    AuthController(RegistrationService registration) {
        this.registration = registration;
    }

    /**
     * 202 rather than 201: whether an account was created is exactly the fact this must not
     * report, and "accepted" is also the honest description of what happened, since the
     * account cannot be used until the address is confirmed.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void register(@Valid @RequestBody RegisterRequest request) {
        registration.register(request.email(), request.password(), request.displayName());
    }

    /**
     * Reports whether the link worked, which is not a disclosure: the caller is holding the
     * token, so the only thing being revealed is whether the thing they already have is
     * still good. Showing a success page for a link that did nothing would be worse.
     */
    @PostMapping("/verify")
    VerifyResponse verify(@Valid @RequestBody TokenRequest request) {
        return new VerifyResponse(registration.verify(request.token()));
    }

    @PostMapping("/reset-request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestReset(@Valid @RequestBody ResetRequest request) {
        registration.requestPasswordReset(request.email());
    }

    @PostMapping("/reset")
    VerifyResponse reset(@Valid @RequestBody ResetPasswordRequest request) {
        return new VerifyResponse(
                registration.resetPassword(request.token(), request.password()));
    }

    /**
     * A minimum length and nothing else.
     *
     * <p>Composition rules — a digit, a symbol, mixed case — push people towards short
     * passwords built to satisfy a checker, and NIST dropped them for that reason. Length
     * is the parameter that matters, and the storage cost of a long password is the same as
     * a short one under Argon2.
     */
    record RegisterRequest(@Email @NotBlank String email,
                           @NotBlank @Size(min = 12, max = 200) String password,
                           @Size(max = 100) String displayName) {}

    record TokenRequest(@NotBlank String token) {}

    record ResetRequest(@Email @NotBlank String email) {}

    record ResetPasswordRequest(@NotBlank String token,
                                @NotBlank @Size(min = 12, max = 200) String password) {}

    /** {@code false} means the link was unknown, expired, already used, or for another flow. */
    record VerifyResponse(boolean ok) {}
}
