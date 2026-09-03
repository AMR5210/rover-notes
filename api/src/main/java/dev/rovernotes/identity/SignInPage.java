package dev.rovernotes.identity;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

/**
 * The sign-in and sign-out pages, served by the service that owns the session.
 *
 * <p>These have to come from here rather than from the Next application, because the
 * session a sign-in establishes lives on this side: the authorization endpoint reads it
 * when the code grant resumes. A page served by the interface could post here, but the
 * cookie it set would then be the interface's to explain, and the redirect back into the
 * grant would cross an origin for no reason.
 *
 * <h2>Why the markup is built here rather than in a template</h2>
 *
 * <p>This application has no template engine. Adding one for two pages would put a
 * rendering stack, its starter and its conventions into a service whose every other
 * response is JSON. Spring Authorization Server takes the same view for the page beside
 * these: {@code DefaultConsentPage}, which renders the consent screen this system already
 * shows to self-registered agents, is HTML assembled in Java and written as
 * {@code text/html}.
 *
 * <h2>Nothing untrusted reaches the markup</h2>
 *
 * <p>The only value interpolated into either page is the CSRF token, which the framework
 * generated. In particular the submitted address is <em>not</em> echoed back after a failed
 * attempt: preserving it would save retyping an email and would put caller-controlled text
 * into the response, which is the whole of the surface these pages could otherwise have.
 * The escaping below is therefore belt to that brace rather than the thing keeping it safe.
 */
@Controller
class SignInPage {

    /**
     * Both pages carry their own styling.
     *
     * <p>The interface's stylesheet is compiled by Next and is not reachable from here, and
     * the one Spring serves alongside its generated pages
     * ({@code DefaultResourcesFilter.css}) is registered only while those pages are — a
     * custom login page removes both. The tokens are the interface's own, so the two look
     * like one application, and they invert for a dark preference the same way.
     */
    private static final String STYLE = """
            :root {
              --bg: #f7f7f5; --surface: #fdfdfc; --border: #e2e2df;
              --fg: #18181b; --muted: #6b6b73; --accent: #a84e08; --danger: #b3261e;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #131316; --surface: #1a1a1f; --border: #2c2c33;
                --fg: #ececee; --muted: #9a9aa3; --accent: #fbbf24; --danger: #f87171;
              }
            }
            * { box-sizing: border-box; }
            body {
              margin: 0; min-height: 100vh; display: flex; align-items: center;
              justify-content: center; padding: 2rem 1.5rem;
              background: var(--bg); color: var(--fg);
              font: 16px/1.5 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
            }
            main { width: 100%; max-width: 22rem; }
            h1 { font-size: 1.4rem; margin: 0 0 1.25rem; letter-spacing: -0.01em; }
            form { display: flex; flex-direction: column; gap: 0.35rem; }
            label { font-size: 0.85rem; font-weight: 560; margin-top: 0.6rem; }
            input[type="email"], input[type="password"] {
              font: inherit; width: 100%; padding: 0.6rem 0.8rem; color: var(--fg);
              background: var(--surface); border: 1px solid var(--border); border-radius: 10px;
            }
            input:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
            button {
              font: inherit; margin-top: 1.25rem; padding: 0.6rem 1rem; cursor: pointer;
              color: var(--bg); background: var(--fg); border: 1px solid var(--fg);
              border-radius: 10px;
            }
            button:hover { opacity: 0.85; }
            a { color: var(--accent); }
            .hint { color: var(--muted); font-size: 0.82rem; margin: 1.25rem 0 0; }
            .notice, .error {
              font-size: 0.9rem; padding: 0.6rem 0.9rem; border-radius: 10px;
              margin: 0 0 1.25rem;
            }
            .error { color: var(--danger); border: 1px solid var(--danger); }
            .notice { color: var(--muted); border: 1px solid var(--border); }
            """;

    /**
     * The sign-in form.
     *
     * <p>{@code username} rather than {@code email} because that is the field Spring
     * Security's filter reads, and renaming it would mean configuring the filter to match a
     * label. What the person types is an address; what the account is keyed on internally is
     * an identifier the form never sees.
     */
    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String signIn(HttpServletRequest request,
                  @RequestParam(required = false) String error,
                  @RequestParam(required = false) String logout) {
        String message = "";
        if (error != null) {
            // Deliberately one message for both causes. Saying which of the address and the
            // password was wrong answers "is this address registered" to anyone who asks,
            // which is the question every other endpoint here is careful not to answer.
            message = """
                    <p class="error" role="alert">Those details were not recognised.</p>
                    """;
        } else if (logout != null) {
            message = """
                    <p class="notice">You are signed out.</p>
                    """;
        }

        return page("Sign in", """
                %s<form method="post" action="/login">
                  %s
                  <label for="username">Email</label>
                  <input id="username" name="username" type="email" autocomplete="username"
                         required autofocus>
                  <label for="password">Password</label>
                  <input id="password" name="password" type="password"
                         autocomplete="current-password" required>
                  <button type="submit">Sign in</button>
                </form>
                <p class="hint">
                  <a href="/account/register">Create an account</a>
                  &middot;
                  <a href="/account/reset-request">Forgotten your password?</a>
                </p>
                """.formatted(message, csrfField(request)));
    }

    /**
     * Sign-out, which is a form rather than a link.
     *
     * <p>Spring Security serves a page of its own here, but only while it is also serving
     * the generated sign-in form: {@code DefaultLoginPageConfigurer} registers the logout
     * page filter inside the same branch, so naming a login page removes both. Without this
     * the interface's sign-out — a plain navigation to {@code /logout} — would reach
     * nothing.
     *
     * <p>It stays a POST behind a CSRF token rather than becoming a link that acts. A
     * signed-out session is not a disaster, but a GET that changes state is one any other
     * site can trigger with an image tag.
     */
    @GetMapping(value = "/logout", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    String signOut(HttpServletRequest request) {
        return page("Sign out", """
                <p class="notice">
                  This ends the session on both sides: the interface loses the token it is
                  holding, and the sign-in it was obtained from is closed here too.
                </p>
                <form method="post" action="/logout">
                  %s
                  <button type="submit">Sign out</button>
                </form>
                <p class="hint"><a href="/">Back to your notes</a></p>
                """.formatted(csrfField(request)));
    }

    /**
     * The hidden field the form posts back.
     *
     * <p>{@code name} before {@code value}, which is how Spring's own generated form wrote
     * it and what the tests walking the code grant read the token out of.
     */
    private static String csrfField(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            // Only reachable if CSRF were switched off for this chain, in which case the
            // form still posts and the field would be ignored.
            return "";
        }
        return "<input type=\"hidden\" name=\"%s\" value=\"%s\">".formatted(
                HtmlUtils.htmlEscape(token.getParameterName()),
                HtmlUtils.htmlEscape(token.getToken()));
    }

    private static String page(String title, String body) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s — Rover Notes</title>
                  <style>%s</style>
                </head>
                <body>
                  <main>
                    <h1>%s</h1>
                    %s
                  </main>
                </body>
                </html>
                """.formatted(HtmlUtils.htmlEscape(title), STYLE, HtmlUtils.htmlEscape(title), body);
    }
}
