"use client";

import { useCallback, useRef, useState } from "react";

import { clipUrl, createNote, uploadFile, type Topic } from "../lib/api";
import { TopicPicker } from "./TopicPicker";

/**
 * Getting documents in.
 *
 * The API has taken files and web pages since the parsing service landed, but the only
 * route to either was curl, so through a browser the corpus could only be typed. This is
 * that capability given a surface.
 *
 * Three ways in, offered in the order people have documents: a file they already have, a
 * page on the web, or something they want to write now. Dropping a file is the primary
 * one and is the whole panel rather than a button inside it — a drop target that is only
 * as large as its label is a target people miss.
 *
 * Every file reports its own outcome. A batch that half worked is the normal case: PDFs
 * that are scans without a text layer fail where the ones beside them succeed, and a
 * single summary line would leave the reader to work out which of eight files is missing.
 *
 * The topic chosen here applies to everything added from this panel until it is changed.
 * Dropping eight papers on one subject is the case worth being quick, and picking the
 * same topic eight times is the version of it that is not.
 */

/** What the parsing service reads. Text and markdown never reach it — see `ingest`. */
const TEXT_TYPES = [".txt", ".md", ".markdown", ".text"];

type Stage = "working" | "done" | "failed";

interface Progress {
  key: string;
  name: string;
  stage: Stage;
  detail?: string;
}

/**
 * Turns the API's problem document into a sentence.
 *
 * The body is JSON with a `detail` when the API rejected the request and a bare string
 * when something upstream did. Showing the raw body puts a brace and a status code in
 * front of somebody who wanted to add a file.
 */
function explain(cause: unknown): string {
  const raw = cause instanceof Error ? cause.message : String(cause);
  try {
    const parsed = JSON.parse(raw);
    return parsed.detail ?? parsed.title ?? raw;
  } catch {
    return raw;
  }
}

export function AddDocuments({
  onAdded,
  topics,
  topicId,
  onTopicChange,
  onTopicCreated,
}: {
  onAdded: () => void;
  topics: Topic[];
  topicId: string | null;
  onTopicChange: (topicId: string | null) => void;
  onTopicCreated: (topic: Topic) => void;
}) {
  const [over, setOver] = useState(false);
  const [progress, setProgress] = useState<Progress[]>([]);
  const [link, setLink] = useState("");
  const [linking, setLinking] = useState(false);
  const [linkError, setLinkError] = useState<string | null>(null);
  const picker = useRef<HTMLInputElement>(null);

  const mark = useCallback((key: string, stage: Stage, detail?: string) => {
    setProgress((rows) =>
      rows.map((row) => (row.key === key ? { ...row, stage, detail } : row)),
    );
  }, []);

  /**
   * A PDF is sent as it is; text and markdown are read here and posted as a note.
   *
   * Reading them in the browser rather than uploading them is not a shortcut — the
   * parsing endpoint takes PDFs, and text arriving there would be refused as unreadable.
   * The file is already text, so there is nothing to extract.
   */
  const ingest = useCallback(
    async (files: File[]) => {
      if (files.length === 0) return;

      const queued = files.map((file, index) => ({
        key: `${Date.now()}-${index}-${file.name}`,
        name: file.name,
        stage: "working" as Stage,
      }));
      setProgress((rows) => [...queued, ...rows]);

      // Sequential rather than parallel. Each upload parses a document server-side, and
      // eight at once on a small box is how a drop of a folder becomes a timeout.
      for (let index = 0; index < files.length; index++) {
        const file = files[index];
        const key = queued[index].key;
        const name = file.name.toLowerCase();

        try {
          if (name.endsWith(".pdf")) {
            await uploadFile(file, undefined, topicId);
          } else if (TEXT_TYPES.some((suffix) => name.endsWith(suffix))) {
            const text = await file.text();
            if (!text.trim()) throw new Error("the file is empty");
            await createNote(file.name.replace(/\.[^.]+$/, ""), text, topicId);
          } else {
            throw new Error("only PDF, text and markdown files can be read");
          }
          mark(key, "done");
        } catch (cause) {
          mark(key, "failed", explain(cause));
        }
      }

      onAdded();
    },
    [mark, onAdded, topicId],
  );

  async function submitLink(event: React.FormEvent) {
    event.preventDefault();
    const address = link.trim();
    if (!address || linking) return;

    setLinking(true);
    setLinkError(null);
    try {
      await clipUrl(address, topicId);
      setLink("");
      onAdded();
    } catch (cause) {
      setLinkError(explain(cause));
    } finally {
      setLinking(false);
    }
  }

  return (
    <section className="adder">
      <div
        className={`dropzone${over ? " dropzone-over" : ""}`}
        onDragOver={(event) => {
          event.preventDefault();
          setOver(true);
        }}
        onDragLeave={() => setOver(false)}
        onDrop={(event) => {
          event.preventDefault();
          setOver(false);
          void ingest(Array.from(event.dataTransfer.files));
        }}
      >
        <svg className="dropzone-icon" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 16V4m0 0L7 9m5-5 5 5" />
          <path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" />
        </svg>
        <p className="dropzone-title">Drop your documents here</p>
        <p className="dropzone-hint">PDFs, text files and Markdown</p>

        {/* The button is what makes this reachable without a pointer: a drop target
            cannot be operated from a keyboard, and the file picker it opens can. */}
        <button type="button" className="dropzone-pick" onClick={() => picker.current?.click()}>
          Choose files
        </button>
        <input
          ref={picker}
          type="file"
          multiple
          accept=".pdf,.txt,.md,.markdown,.text"
          className="visually-hidden"
          aria-label="Choose files to add"
          onChange={(event) => {
            void ingest(Array.from(event.target.files ?? []));
            // Cleared so choosing the same file twice fires the change event again.
            event.target.value = "";
          }}
        />
      </div>

      <TopicPicker
        topics={topics}
        value={topicId}
        onChange={onTopicChange}
        onCreated={onTopicCreated}
        label="File under"
      />

      <form className="cliprow" onSubmit={submitLink}>
        <input
          type="url"
          value={link}
          onChange={(event) => setLink(event.target.value)}
          placeholder="…or paste a link to a web page"
          aria-label="Link to a web page"
        />
        <button type="submit" disabled={!link.trim() || linking}>
          {linking ? "Reading…" : "Add"}
        </button>
      </form>

      {linkError && <p className="error">{linkError}</p>}

      {progress.length > 0 && (
        <ul className="progress" aria-live="polite">
          {progress.map((row) => (
            <li key={row.key} className={`progress-${row.stage}`}>
              <span className="progress-name">{row.name}</span>
              <span className="progress-stage">
                {row.stage === "working" && "Reading…"}
                {row.stage === "done" && "Added"}
                {row.stage === "failed" && (row.detail ?? "Could not be read")}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
