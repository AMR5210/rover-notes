"use client";

import Link from "next/link";
import { useState } from "react";

import { SourcePanel } from "../components/SourcePanel";
import { search, type Hit, type SearchResponse } from "../lib/api";

const MODES = [
  { value: "", label: "Default" },
  { value: "hybrid", label: "Hybrid" },
  { value: "dense", label: "Dense" },
  { value: "lexical", label: "Lexical" },
];

/**
 * Retrieval on its own, without generation.
 *
 * The channel controls are here because the answer to "why did it return that" is often
 * "because of which channel ran". The response reports the mode that actually answered,
 * which is not always the one requested: the router sends identifier-shaped queries to
 * the lexical channel, and search falls back to lexical when the embedding server is
 * unreachable. Showing the mode is how a thin result explains itself.
 */
export default function SearchPage() {
  const [query, setQuery] = useState("");
  const [mode, setMode] = useState("");
  const [rerank, setRerank] = useState(false);
  const [response, setResponse] = useState<SearchResponse | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [elapsed, setElapsed] = useState<number | null>(null);
  const [open, setOpen] = useState<Hit | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!query.trim() || pending) return;

    setPending(true);
    setError(null);
    setOpen(null);
    const started = performance.now();
    try {
      setResponse(await search(query, { mode: mode || undefined, rerank, limit: 10 }));
      setElapsed(Math.round(performance.now() - started));
    } catch (cause) {
      setResponse(null);
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setPending(false);
    }
  }

  const requested = mode ? mode.toUpperCase() : null;
  const answered = response?.mode ?? null;
  const routed = requested !== null && answered !== null && requested !== answered;

  return (
    <div className={open ? "split" : undefined}>
      <div className="column">
        <header className="page-head">
          <h1>Search your library</h1>
          <p className="muted">
            Finds the passages themselves, with no answer written over them. For an answer
            in prose, use <Link href="/ask">Ask</Link>.
          </p>
        </header>

        <form onSubmit={submit} className="asker">
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search passages"
            aria-label="Search query"
            autoFocus
          />
          <button type="submit" disabled={pending || !query.trim()}>
            {pending ? "Searching…" : "Search"}
          </button>
        </form>

        <details className="advanced">
          <summary>Advanced options</summary>
          <div className="controls">
          <label>
            Channel
            <select value={mode} onChange={(event) => setMode(event.target.value)}>
              {MODES.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label className="check">
            <input
              type="checkbox"
              checked={rerank}
              onChange={(event) => setRerank(event.target.checked)}
            />
            Cross-encoder rerank
            <span className="hint">off by default, measured at ~950 ms</span>
          </label>
          </div>
        </details>

        {error && <p className="error">{error}</p>}

        {response && !pending && (
          <>
            <div className="result-head">
              <span className={`badge badge-${response.mode.toLowerCase()}`}>
                {response.mode}
              </span>
              {response.reranked && <span className="badge badge-rerank">reranked</span>}
              <span className="muted">
                {response.count} passages · {elapsed} ms
              </span>
              {routed && (
                <span className="note">
                  answered by {answered}, not the {requested} that was asked for
                </span>
              )}
            </div>

            <ul className="hits">
              {response.results.map((hit, index) => (
                <li key={hit.chunkId}>
                  <button className="hit" onClick={() => setOpen(hit)}>
                    <div className="hit-head">
                      <span className="rank">{index + 1}</span>
                      <span className="hit-title">{hit.title}</span>
                      <span className="score">{hit.score.toFixed(4)}</span>
                    </div>
                    <p className="hit-snippet">
                      {hit.snippet.length > 320
                        ? `${hit.snippet.slice(0, 320)}…`
                        : hit.snippet}
                    </p>
                    <span className="hit-span">
                      characters {hit.charStart} to {hit.charEnd}
                    </span>
                  </button>
                </li>
              ))}
            </ul>

            {response.results.length === 0 && (
              <p className="muted">Nothing matched. Try fewer or more general words.</p>
            )}
          </>
        )}
      </div>

      {open && (
        <SourcePanel
          documentId={open.documentId}
          charStart={open.charStart}
          charEnd={open.charEnd}
          onClose={() => setOpen(null)}
        />
      )}
    </div>
  );
}
