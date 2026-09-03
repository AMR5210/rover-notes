"use client";

import { useState } from "react";

import { createTopic, type Topic } from "../lib/api";

/**
 * Choosing where a document sits, with a way to make a new place for it.
 *
 * Creating a topic from inside the picker rather than sending people to a separate screen
 * to manage them first. The moment somebody knows a document needs its own topic is the
 * moment they are filing it, and a trip elsewhere to create one is where the intention
 * gets lost.
 *
 * A `select` rather than a combo box or a tag input. There is one topic per document, the
 * list is short, and a native control is the one that already works with a keyboard, a
 * screen reader and a phone.
 */

/** The option value that opens the "new topic" field. Not a valid id, so it cannot collide. */
const NEW = "__new";

export function TopicPicker({
  topics,
  value,
  onChange,
  onCreated,
  label = "Topic",
}: {
  topics: Topic[];
  /** The selected topic id, or null for "no topic". */
  value: string | null;
  onChange: (topicId: string | null) => void;
  /** Called after a topic is created here, so the caller can refresh its own list. */
  onCreated: (topic: Topic) => void;
  label?: string;
}) {
  const [naming, setNaming] = useState(false);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function add() {
    const clean = name.trim();
    if (!clean || busy) return;

    setBusy(true);
    setError(null);
    try {
      const topic = await createTopic(clean);
      onCreated(topic);
      onChange(topic.id);
      setName("");
      setNaming(false);
    } catch (cause) {
      // The API answers a duplicate name with a sentence saying so; showing it is more
      // use than "409", and it is the one failure people will actually hit.
      const raw = cause instanceof Error ? cause.message : String(cause);
      try {
        setError(JSON.parse(raw).detail ?? raw);
      } catch {
        setError(raw);
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="topicpick">
      <label className="topicpick-label">
        <span>{label}</span>
        <select
          value={naming ? NEW : (value ?? "")}
          onChange={(event) => {
            const chosen = event.target.value;
            if (chosen === NEW) {
              setNaming(true);
              return;
            }
            setNaming(false);
            onChange(chosen === "" ? null : chosen);
          }}
        >
          <option value="">No topic</option>
          {topics.map((topic) => (
            <option key={topic.id} value={topic.id}>
              {topic.name}
            </option>
          ))}
          <option value={NEW}>+ New topic…</option>
        </select>
      </label>

      {naming && (
        <div className="topicpick-new">
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="Name the topic"
            aria-label="New topic name"
            autoFocus
            onKeyDown={(event) => {
              // Enter would otherwise submit whatever form this sits inside, saving a
              // half-written note on the way to naming a topic.
              if (event.key === "Enter") {
                event.preventDefault();
                void add();
              }
              if (event.key === "Escape") {
                setNaming(false);
                setName("");
                setError(null);
              }
            }}
          />
          {/* Named for what it adds. The panel this sits in already has an "Add" that
              captures a web page, and two buttons of that name are one word of context
              short for anyone reading them out of order. */}
          <button type="button" onClick={() => void add()} disabled={!name.trim() || busy}>
            {busy ? "Adding…" : "Add topic"}
          </button>
          <button
            type="button"
            className="linklike"
            onClick={() => {
              setNaming(false);
              setName("");
              setError(null);
            }}
          >
            Cancel
          </button>
        </div>
      )}

      {error && <p className="error">{error}</p>}
    </div>
  );
}
