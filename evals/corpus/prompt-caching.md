# Prompt caching

Repeated prompt prefixes are marked with cache_control so the provider stores the
processed tokens instead of re-processing them on every call.

An entry has a five-minute sliding time to live, refreshed on each read. Cache writes
bill at 1.25 times the input rate and reads at 0.1 times, so a marked prefix pays for
itself on its second use. The minimum cacheable prefix is 1024 tokens for Sonnet and
2048 for Haiku; below that the marker is ignored and the request bills normally.

Two prefixes are marked: the retrieval system prompt with its citation instructions, and
the source document used during contextual annotation. Breakpoints are placed after the
last static block, because everything following a breakpoint is re-processed on every
call.

Cache hit rate is exported as a counter alongside the token counters. A hit rate below
60 percent usually means a dynamic value (a timestamp, a request identifier, a reordered
tool list) has drifted into what was meant to be the static prefix.
