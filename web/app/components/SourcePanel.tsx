"use client";

import { useEffect, useRef, useState } from "react";

import { getNote, type Note } from "../lib/api";

/**
 * Shows a cited document with the cited span highlighted.
 *
 * This is what the character offsets on every citation are for. The API returns a span
 * rather than a copy of the passage text, so the interface can show the claim in the
 * place it was made — surrounded by the sentences before and after it, which is what
 * lets a reader judge whether the claim was fairly drawn.
 *
 * Offsets are clamped rather than trusted. They address the document as stored, and a
 * document edited between indexing and reading would leave them pointing at the wrong
 * text or past the end. The eval harness checks the same property from the other side.
 */
export function SourcePanel({
  documentId,
  charStart,
  charEnd,
  onClose,
}: {
  documentId: string;
  charStart: number;
  charEnd: number;
  onClose: () => void;
}) {
  const [note, setNote] = useState<Note | null>(null);
  const [error, setError] = useState<string | null>(null);
  const highlight = useRef<HTMLElement>(null);

  useEffect(() => {
    let active = true;
    setNote(null);
    setError(null);
    getNote(documentId)
      .then((loaded) => active && setNote(loaded))
      .catch((cause: Error) => active && setError(cause.message));
    return () => {
      active = false;
    };
  }, [documentId]);

  // The cited passage is often below the fold of a long document, and a panel that opens
  // at the top asks the reader to find it themselves. The scroll is instant rather than
  // smooth: the panel is already a new surface, so animating it means the passage is
  // briefly absent from a view the reader is looking at.
  useEffect(() => {
    highlight.current?.scrollIntoView({ block: "center", behavior: "instant" });
  }, [note, charStart]);

  useEffect(() => {
    const escape = (event: KeyboardEvent) => event.key === "Escape" && onClose();
    window.addEventListener("keydown", escape);
    return () => window.removeEventListener("keydown", escape);
  }, [onClose]);

  return (
    <aside className="panel" aria-label="Cited source">
      <header className="panel-head">
        <span className="panel-title">{note?.title ?? "Loading…"}</span>
        <button className="icon" onClick={onClose} aria-label="Close source">
          ✕
        </button>
      </header>

      {error && <p className="error">{error}</p>}

      {note && (
        <div className="document">
          {(() => {
            const text = note.content;
            const from = Math.min(Math.max(charStart, 0), text.length);
            const to = Math.min(Math.max(charEnd, from), text.length);
            return (
              <>
                <span className="dim">{text.slice(0, from)}</span>
                <mark ref={highlight}>{text.slice(from, to)}</mark>
                <span className="dim">{text.slice(to)}</span>
              </>
            );
          })()}
        </div>
      )}

      {note && (
        <footer className="panel-foot">
          characters {charStart} to {charEnd} of {note.content.length}
        </footer>
      )}
    </aside>
  );
}
