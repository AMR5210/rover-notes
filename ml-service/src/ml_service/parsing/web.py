"""Fetches a web page and extracts the part worth indexing.

Two problems, and the second is the one that matters.

**Boilerplate.** A page's navigation, cookie banner, share buttons and footer are text
like any other. Indexed alongside the article, they answer questions with the site's
chrome — every page in a corpus shares a footer, so a query matching one matches all of
them. trafilatura does the extraction; it is a maintained implementation of a problem
with no clean rule.

**Fetching a URL somebody else chose.** This is server-side request forgery, and it is
the reason this module is careful rather than four lines of httpx. A service that
retrieves an arbitrary URL on request can be pointed at things the caller cannot reach
themselves: the cloud metadata endpoint at 169.254.169.254, which hands out credentials;
a database or admin panel on a private address; the service's own loopback interface,
where the local profile disables authentication.

The guard resolves the hostname and checks every address it resolves to before
connecting, then repeats that on each redirect. Both halves are needed. A name that
resolves to a private address is the ordinary attack, and a public URL that redirects to
one is the same attack with a hop in front of it — a check applied only to the URL the
caller supplied would pass it straight through.

The remaining hole is deliberate and named: between the check and the connect, a name
could resolve differently — DNS rebinding. Closing it properly means connecting to the
address that was checked rather than re-resolving the name, which httpx does not offer
without a custom transport. What bounds it here is that the fetch is a single request
whose body is discarded unless extraction succeeds, so the value of winning that race is
a page of text rather than a credential.
"""

from __future__ import annotations

import ipaddress
import logging
import socket
from dataclasses import dataclass
from urllib.parse import urlparse

import httpx

log = logging.getLogger(__name__)

# Schemes worth fetching. Anything else — file:, gopher:, data: — either reads the
# service's own disk or is not a fetch at all.
ALLOWED_SCHEMES = frozenset({"http", "https"})

# A page larger than this is refused rather than truncated. The useful part of an article
# is far below it, and the cases above it are downloads mislabelled as pages.
MAX_BYTES = 10 * 1024 * 1024

# Whole-request budget. A caller is waiting, and a server that accepts a connection and
# then sends nothing would otherwise hold the request until the client gives up.
TIMEOUT_SECONDS = 20.0

# Redirects are followed manually so each hop can be checked. The limit is what stops a
# redirect loop; browsers use a similar figure.
MAX_REDIRECTS = 5

# Below this many characters, what came back is not an article. Extraction rarely returns
# nothing at all — a page that is only navigation yields the navigation — so a check for
# emptiness passes exactly the pages worth refusing. Stored, they become documents whose
# entire content is "Home | About | Contact": retrievable, useless, and sharing their
# text with every other page on the site, so a query matching one matches all of them.
# Roughly a sentence, which is the shortest thing that could answer a question.
MIN_TEXT_CHARS = 64


class UnfetchableUrlError(ValueError):
    """The URL cannot be fetched, and the reason is the caller's to fix."""


@dataclass(frozen=True)
class Clip:
    """A fetched page, reduced to what is worth indexing."""

    url: str
    title: str
    text: str


def _refuse(reason: str) -> UnfetchableUrlError:
    return UnfetchableUrlError(reason)


def check_address(host: str) -> None:
    """Refuses a hostname that resolves anywhere the service should not reach.

    Every address the name resolves to is checked, not just the first. A name with both
    a public and a private record would otherwise pass the check and connect to whichever
    the resolver returned second.
    """
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror as exc:
        raise _refuse(f"could not resolve {host!r}") from exc

    # De-duplicated but kept in the order the resolver returned. A set would lose that
    # order, which makes the refusal message name an arbitrary one of several bad
    # addresses and a test asserting on it pass or fail by chance.
    addresses = list(dict.fromkeys(info[4][0] for info in infos))
    if not addresses:
        raise _refuse(f"could not resolve {host!r}")

    for address in addresses:
        parsed = ipaddress.ip_address(address)
        # is_global is false for loopback, private, link-local, multicast, reserved and
        # unspecified addresses. Naming the categories individually would mean keeping a
        # list in step with the standard library's.
        if not parsed.is_global:
            raise _refuse(f"{host!r} resolves to {address}, which is not a public address")


def check_url(url: str) -> str:
    """Validates a URL and returns it, or explains why it will not be fetched."""
    parsed = urlparse(url)

    if parsed.scheme not in ALLOWED_SCHEMES:
        raise _refuse(f"unsupported scheme {parsed.scheme!r}; expected http or https")
    if not parsed.hostname:
        raise _refuse("the URL has no host")

    check_address(parsed.hostname)
    return url


def fetch(url: str, *, client: httpx.Client | None = None) -> tuple[str, str]:
    """Retrieves a page, checking every redirect hop. Returns the final URL and its body.

    Redirects are followed here rather than by httpx so that each destination goes
    through the same address check as the original. A public URL that redirects to a
    private address is the same attack as a private URL, one hop later.
    """
    owned = client is None
    http = client or httpx.Client(
        timeout=TIMEOUT_SECONDS,
        follow_redirects=False,
        headers={"user-agent": "rover-notes/1.0 (+https://github.com/AMR5210/rover-notes2.0)"},
    )

    try:
        current = check_url(url)
        for _ in range(MAX_REDIRECTS + 1):
            response = http.get(current)

            if response.is_redirect:
                location = response.headers.get("location")
                if not location:
                    raise _refuse("a redirect gave no destination")
                current = check_url(
                    str(response.next_request.url) if response.next_request else location
                )
                continue

            if response.status_code >= 400:
                raise _refuse(f"the page returned {response.status_code}")

            # Checked after the fact as well as by header, since a server may send no
            # content-length or an untrue one.
            if len(response.content) > MAX_BYTES:
                raise _refuse(f"the page is larger than {MAX_BYTES // (1024 * 1024)} MB")

            return current, response.text

        raise _refuse(f"more than {MAX_REDIRECTS} redirects")
    except httpx.HTTPError as exc:
        raise _refuse(f"could not fetch the page: {exc}") from exc
    finally:
        if owned:
            http.close()


def clip(url: str, *, client: httpx.Client | None = None) -> Clip:
    """Fetches a page and reduces it to a title and readable text."""
    import trafilatura

    final_url, html = fetch(url, client=client)

    text = trafilatura.extract(html, url=final_url, include_comments=False) or ""
    metadata = trafilatura.extract_metadata(html, default_url=final_url)
    title = (getattr(metadata, "title", None) or "").strip() if metadata else ""

    text = text.strip()
    if len(text) < MIN_TEXT_CHARS:
        # A page that extracts to nothing, or to its own navigation, would be stored and
        # then answer questions with the site's chrome.
        raise _refuse(
            f"no readable text could be extracted from the page: {len(text)} characters, "
            f"below the {MIN_TEXT_CHARS} a page needs to be worth indexing"
        )

    return Clip(url=final_url, title=title or final_url, text=text)
