"""Fetching a URL somebody else chose.

The extraction half is a library call. The half worth testing is the guard around it:
this service retrieves an arbitrary URL on request, so without one it is a proxy into
whatever the caller cannot reach themselves — the cloud metadata endpoint that hands out
credentials, an admin panel on a private address, its own loopback interface where the
local profile disables authentication.

The cases below are the shapes that attack takes. Each is written against the address the
name resolves to rather than against the text of the URL, because a blocklist of
hostnames is defeated by any name that points at the same place.
"""

from __future__ import annotations

import socket

import httpx
import pytest

from ml_service.parsing import web

ARTICLE = """
<html>
  <head><title>Retrieval fuses ranked lists</title></head>
  <body>
    <nav>Home | About | Contact</nav>
    <article>
      <h1>Retrieval fuses ranked lists</h1>
      <p>Reciprocal rank fusion combines two ranked lists without comparing their
         scores, which is what makes it usable across channels that score differently.
         The read path budget is 150 milliseconds and reranking is off by default.</p>
      <p>A second paragraph so the extractor has enough text to consider this an
         article rather than a stub page with a heading on it.</p>
    </article>
    <footer>Copyright 2026. Cookie preferences. Subscribe to our newsletter.</footer>
  </body>
</html>
"""


def resolving_to(monkeypatch, address: str) -> None:
    """Makes every hostname resolve to one address, whatever it is called."""
    monkeypatch.setattr(
        web.socket,
        "getaddrinfo",
        lambda host, port, *a, **k: [(socket.AF_INET, None, None, "", (address, 0))],
    )


def client_returning(*responses: httpx.Response) -> httpx.Client:
    """A client that answers each request with the next scripted response."""
    remaining = list(responses)

    def handler(request: httpx.Request) -> httpx.Response:
        response = remaining.pop(0)
        response.request = request
        return response

    return httpx.Client(transport=httpx.MockTransport(handler), follow_redirects=False)


class TestAddressesTheServiceMustNotReach:
    @pytest.mark.parametrize(
        ("address", "what"),
        [
            ("127.0.0.1", "loopback, where the local profile disables authentication"),
            ("169.254.169.254", "the cloud metadata endpoint, which hands out credentials"),
            ("10.0.0.5", "a private network"),
            ("192.168.1.1", "a home or office network"),
            ("172.16.0.1", "the other private range"),
            ("0.0.0.0", "unspecified, which routes to localhost on some stacks"),
        ],
    )
    def test_a_name_resolving_to_a_reserved_address_is_refused(
        self, monkeypatch, address: str, what: str
    ) -> None:
        resolving_to(monkeypatch, address)

        with pytest.raises(web.UnfetchableUrlError) as refusal:
            web.check_url("https://looks-fine.example/article")

        assert address in str(refusal.value), what

    def test_ipv6_loopback_is_refused(self, monkeypatch) -> None:
        monkeypatch.setattr(
            web.socket,
            "getaddrinfo",
            lambda host, port, *a, **k: [(socket.AF_INET6, None, None, "", ("::1", 0, 0, 0))],
        )

        with pytest.raises(web.UnfetchableUrlError):
            web.check_url("https://looks-fine.example/article")

    def test_every_address_a_name_resolves_to_is_checked(self, monkeypatch) -> None:
        # A name with both a public and a private record. Checking only the first would
        # pass, then connect to whichever the resolver happened to return next.
        monkeypatch.setattr(
            web.socket,
            "getaddrinfo",
            lambda host, port, *a, **k: [
                (socket.AF_INET, None, None, "", ("93.184.216.34", 0)),
                (socket.AF_INET, None, None, "", ("127.0.0.1", 0)),
            ],
        )

        with pytest.raises(web.UnfetchableUrlError) as refusal:
            web.check_url("https://looks-fine.example/article")

        assert "127.0.0.1" in str(refusal.value)

    def test_a_private_address_behind_a_public_one_is_still_caught(self, monkeypatch) -> None:
        # The order that matters. Checking only the first address the resolver returns
        # passes this, and then connects to whichever record the connect happens to use.
        monkeypatch.setattr(
            web.socket,
            "getaddrinfo",
            lambda host, port, *a, **k: [
                (socket.AF_INET, None, None, "", ("93.184.216.34", 0)),
                (socket.AF_INET, None, None, "", ("169.254.169.254", 0)),
            ],
        )

        with pytest.raises(web.UnfetchableUrlError) as refusal:
            web.check_url("https://looks-fine.example/article")

        assert "169.254.169.254" in str(refusal.value)

    def test_a_public_address_is_allowed(self, monkeypatch) -> None:
        resolving_to(monkeypatch, "93.184.216.34")

        assert web.check_url("https://example.com/article") == "https://example.com/article"


class TestSchemesAndShapes:
    @pytest.mark.parametrize(
        "url",
        [
            "file:///etc/passwd",
            "gopher://example.com/",
            "data:text/html,<h1>hi</h1>",
        ],
    )
    def test_only_http_and_https_are_fetched(self, url: str) -> None:
        # file: reads the service's own disk; the others are not fetches this can make
        # sense of. None of them should reach the resolver at all.
        with pytest.raises(web.UnfetchableUrlError, match="scheme"):
            web.check_url(url)

    def test_a_url_with_no_host_is_refused(self) -> None:
        with pytest.raises(web.UnfetchableUrlError, match="no host"):
            web.check_url("https:///article")

    def test_a_name_that_does_not_resolve_is_refused(self, monkeypatch) -> None:
        def fail(*args: object, **kwargs: object) -> None:
            raise socket.gaierror("nope")

        monkeypatch.setattr(web.socket, "getaddrinfo", fail)

        with pytest.raises(web.UnfetchableUrlError, match="could not resolve"):
            web.check_url("https://nowhere.invalid/article")


class TestRedirects:
    def test_a_redirect_into_a_private_address_is_refused(self, monkeypatch) -> None:
        # The same attack as a private URL, one hop later. A guard applied only to the
        # URL the caller supplied passes this straight through.
        addresses = iter(["93.184.216.34", "169.254.169.254"])
        monkeypatch.setattr(
            web.socket,
            "getaddrinfo",
            lambda host, port, *a, **k: [(socket.AF_INET, None, None, "", (next(addresses), 0))],
        )

        client = client_returning(
            httpx.Response(302, headers={"location": "http://metadata.internal/latest/meta-data/"})
        )

        with pytest.raises(web.UnfetchableUrlError) as refusal:
            web.fetch("https://example.com/article", client=client)

        assert "169.254.169.254" in str(refusal.value)

    def test_a_redirect_to_a_public_address_is_followed(self, monkeypatch) -> None:
        resolving_to(monkeypatch, "93.184.216.34")
        client = client_returning(
            httpx.Response(301, headers={"location": "https://example.com/moved"}),
            httpx.Response(200, text=ARTICLE),
        )

        final, body = web.fetch("https://example.com/article", client=client)

        assert final.endswith("/moved")
        assert "Reciprocal rank fusion" in body

    def test_a_redirect_loop_stops(self, monkeypatch) -> None:
        resolving_to(monkeypatch, "93.184.216.34")
        client = client_returning(
            *[
                httpx.Response(302, headers={"location": "https://example.com/round"})
                for _ in range(web.MAX_REDIRECTS + 1)
            ]
        )

        with pytest.raises(web.UnfetchableUrlError, match="redirects"):
            web.fetch("https://example.com/article", client=client)


class TestFetching:
    def test_an_error_page_is_not_indexed(self, monkeypatch) -> None:
        resolving_to(monkeypatch, "93.184.216.34")
        client = client_returning(httpx.Response(404, text="<h1>Not found</h1>"))

        with pytest.raises(web.UnfetchableUrlError, match="404"):
            web.fetch("https://example.com/gone", client=client)

    def test_a_page_larger_than_the_cap_is_refused(self, monkeypatch) -> None:
        resolving_to(monkeypatch, "93.184.216.34")
        client = client_returning(httpx.Response(200, content=b"x" * (web.MAX_BYTES + 1)))

        with pytest.raises(web.UnfetchableUrlError, match="larger than"):
            web.fetch("https://example.com/huge", client=client)


class TestExtraction:
    def test_the_article_survives_and_the_chrome_does_not(self, monkeypatch) -> None:
        # Every page on a site shares a footer, so a query matching one matches all of
        # them. That is what makes boilerplate worse than merely useless in a corpus.
        resolving_to(monkeypatch, "93.184.216.34")
        client = client_returning(httpx.Response(200, text=ARTICLE))

        clipped = web.clip("https://example.com/article", client=client)

        assert "Reciprocal rank fusion" in clipped.text
        assert "150 milliseconds" in clipped.text
        assert "Cookie preferences" not in clipped.text
        assert "Home | About | Contact" not in clipped.text

    def test_the_title_comes_from_the_page(self, monkeypatch) -> None:
        resolving_to(monkeypatch, "93.184.216.34")
        client = client_returning(httpx.Response(200, text=ARTICLE))

        assert web.clip("https://example.com/article", client=client).title == (
            "Retrieval fuses ranked lists"
        )

    def test_the_recorded_url_is_where_the_page_actually_came_from(self, monkeypatch) -> None:
        # After a redirect the useful URL is the destination, since that is what a reader
        # following the citation should open.
        resolving_to(monkeypatch, "93.184.216.34")
        client = client_returning(
            httpx.Response(301, headers={"location": "https://example.com/moved"}),
            httpx.Response(200, text=ARTICLE),
        )

        assert web.clip("https://example.com/article", client=client).url == (
            "https://example.com/moved"
        )

    def test_a_page_that_is_only_navigation_is_refused(self, monkeypatch) -> None:
        # Extraction rarely returns nothing at all: a page that is only navigation yields
        # the navigation. Stored, it becomes a document whose entire content is the site's
        # chrome — text every other page shares, so a query matching one matches all.
        resolving_to(monkeypatch, "93.184.216.34")
        client = client_returning(
            httpx.Response(200, text="<html><body><nav>Home | About | Contact</nav></body></html>")
        )

        with pytest.raises(web.UnfetchableUrlError, match="no readable text"):
            web.clip("https://example.com/empty", client=client)

    def test_a_page_with_nothing_at_all_is_refused(self, monkeypatch) -> None:
        resolving_to(monkeypatch, "93.184.216.34")
        client = client_returning(httpx.Response(200, text="<html><body></body></html>"))

        with pytest.raises(web.UnfetchableUrlError, match="no readable text"):
            web.clip("https://example.com/blank", client=client)

    def test_an_article_comfortably_clears_the_threshold(self, monkeypatch) -> None:
        # The threshold has to refuse chrome without refusing short articles.
        resolving_to(monkeypatch, "93.184.216.34")
        client = client_returning(httpx.Response(200, text=ARTICLE))

        assert len(web.clip("https://example.com/article", client=client).text) > 200
