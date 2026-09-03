"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";

import { Answer, Sources } from "../components/Answer";
import { SourcePanel } from "../components/SourcePanel";
import { askStream, listNotes, type Citation } from "../lib/api";

/**
 * Questions built from the corpus that is actually loaded.
 *
 * Fixed examples would name documents nobody here has. These are mechanical rather than
 * clever, and that is the point: they are valid against whatever is in the library, and
 * their job is to show what a question looks like and that the box is safe to press.
 */
function suggestions(titles: string[]): string[] {
  const [first, second] = titles;
  if (!first) return [];
  const asked = [`Summarise ${first}`];
  if (second) {
    asked.push(`What is ${second} about?`);
    asked.push(`How do ${first} and ${second} relate?`);
  }
  return asked;
}

/**
 * Ask a question of the corpus.
 *
 * The answer is the product; the citations are what make it usable. Every claim carries
 * the number of the passage it came from, and every number opens that passage at the
 * exact span it occupies in its document.
 *
 * The answer streams. Its citations do not wait for it: the passages are chosen before
 * generation starts, so they render first and every reference is followable the moment it
 * appears rather than once the answer is complete.
 */
export default function AskPage() {
  const [question, setQuestion] = useState("");
  const [citations, setCitations] = useState<Citation[]>([]);
  const [content, setContent] = useState("");
  const [asked, setAsked] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<Citation | null>(null);
  // What is in the library, so an empty one can say so rather than presenting a box that
  // cannot answer anything. `null` is "not yet known" and renders neither branch.
  const [corpus, setCorpus] = useState<{ total: number; titles: string[] } | null>(null);
  const abort = useRef<AbortController | null>(null);

  useEffect(() => {
    let live = true;
    listNotes(3, 0)
      .then((page) => {
        if (!live) return;
        setCorpus({ total: page.total, titles: page.items.map((note) => note.title) });
      })
      // A library that cannot be listed is not worth a second error on this page: the
      // asking box still works, and a failed question reports its own failure.
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, []);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!question.trim() || streaming) return;

    abort.current = new AbortController();
    setStreaming(true);
    setError(null);
    setOpen(null);
    setCitations([]);
    setContent("");
    setAsked(question);

    try {
      await askStream(
        question,
        {
          onCitations: setCitations,
          onDelta: (text) => setContent((sofar) => sofar + text),
        },
        abort.current.signal,
      );
    } catch (cause) {
      if ((cause as Error).name !== "AbortError") {
        setError(cause instanceof Error ? cause.message : String(cause));
      }
    } finally {
      setStreaming(false);
    }
  }

  function stop(event: React.MouseEvent<HTMLButtonElement>) {
    // Without this, the click still lands on a `type="button"` element, but `setStreaming`
    // re-renders this same position as the `type="submit"` Ask button before the browser
    // decides whether to submit the form — so it does, resubmitting the still-filled
    // question as a second, uncontrolled request `abort.current` never referenced.
    event.preventDefault();
    abort.current?.abort();
    setStreaming(false);
  }

  const started = asked !== "";

  return (
    <div className={open ? "split" : undefined}>
      <div className="column">
        <form onSubmit={submit} className="asker">
          <input
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            placeholder="Ask a question about your documents"
            aria-label="Question"
            autoFocus
          />
          {streaming ? (
            <button type="button" onClick={stop}>
              Stop
            </button>
          ) : (
            <button type="submit" disabled={!question.trim()}>
              Ask
            </button>
          )}
        </form>

        {error && (
          <p className="error">
            {error}
            <span className="hint">
              {" "}
              Generation needs <code>ANTHROPIC_API_KEY</code> set for the API process.
            </span>
          </p>
        )}

        {started && !error && (
          <>
            <p className="asked">{asked}</p>

            {/* Citations arrive before the first word, so the sources a reader can check
                are on screen while the answer is still being written. */}
            {content === "" && streaming && (
              <p className="muted">
                {citations.length > 0
                  ? `${citations.length} passages retrieved, writing the answer…`
                  : "Retrieving passages…"}
              </p>
            )}

            {content !== "" && (
              <Answer
                content={content}
                citations={citations}
                streaming={streaming}
                onCite={setOpen}
              />
            )}

            {citations.length > 0 && (
              <Sources citations={citations} content={content} onCite={setOpen} />
            )}
          </>
        )}

        {!started && !error && corpus?.total === 0 && (
          <div className="firstrun">
            <h2>Add a document to get started</h2>
            <p>
              Questions are answered from your own documents, so there is nothing to
              answer from yet. Drop in a PDF, a text file, or the address of a web page.
            </p>
            <Link className="firstrun-go" href="/notes">
              Go to your library
            </Link>
          </div>
        )}

        {!started && !error && corpus !== null && corpus.total > 0 && (
          <div className="firstrun">
            <p className="firstrun-lede">
              Ask anything about the {corpus.total}{" "}
              {corpus.total === 1 ? "document" : "documents"} in your library. Every
              answer quotes the passages it used, and every citation opens the document at
              the exact words it came from. Where your documents do not cover something,
              it says so instead of guessing.
            </p>

            {suggestions(corpus.titles).length > 0 && (
              <>
                <p className="firstrun-try">Try one of these:</p>
                <ul className="examples-inline">
                  {suggestions(corpus.titles).map((example) => (
                    <li key={example}>
                      {/* Fills the box rather than submitting. Seeing the question land
                          in the field is what teaches that the field is the thing. */}
                      <button type="button" onClick={() => setQuestion(example)}>
                        {example}
                      </button>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </div>
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
