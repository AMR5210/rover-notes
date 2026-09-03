"use client";

import { useCallback, useEffect, useState } from "react";

import { AddDocuments } from "../components/AddDocuments";
import { TopicPicker } from "../components/TopicPicker";
import {
  createNote,
  deleteNote,
  indexingPending,
  listNotes,
  listTopics,
  updateNote,
  type Note,
  type Topic,
  type TopicSelection,
} from "../lib/api";

/**
 * Adds a topic to the held list in the order the API returns them.
 *
 * The list comes back sorted by name, and a topic created here is placed rather than
 * appended so the filter bar does not reshuffle the next time it is fetched.
 */
function withTopic(held: Topic[], added: Topic): Topic[] {
  return [...held, added].sort((left, right) => left.name.localeCompare(right.name));
}

/** The note being edited, held separately so a cancel leaves the list untouched. */
interface Draft {
  id: string;
  title: string;
  content: string;
  topicId: string | null;
}

/**
 * The corpus itself: what has been written, what can be added, and what can be corrected.
 *
 * A new note is searchable shortly after it is written rather than immediately. Indexing
 * runs off the write path through the outbox, so the write returns as soon as the row is
 * committed and embedding happens after. Saying so is more useful than a spinner that
 * implies the work is finished when the list refreshes. An edit takes the same path: the
 * text changes at once, and the chunks behind it are replaced when indexing catches up.
 *
 * Topics filter this page and nothing else. Searching and asking stay across the whole
 * library, because a question is usually the moment somebody does not know which topic
 * holds the answer. See docs/ARCHITECTURE.md.
 */
export default function NotesPage() {
  const [notes, setNotes] = useState<Note[]>([]);
  const [total, setTotal] = useState(0);
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);
  // Typing a document is the least common of the three ways in, so it is the one that
  // is folded away. Offering all three at once makes the panel a form to read rather
  // than a target to drop on.
  const [writing, setWriting] = useState(false);
  const [indexing, setIndexing] = useState(0);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [topics, setTopics] = useState<Topic[]>([]);
  /** Which slice the list is showing: everything, one topic, or the unfiled documents. */
  const [showing, setShowing] = useState<TopicSelection>(undefined);
  /** Where documents added from the panel are filed. Independent of what is on screen. */
  const [filing, setFiling] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const page = await listNotes(50, 0, showing);
      setNotes(page.items);
      setTotal(page.total);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }, [showing]);

  const refreshTopics = useCallback(async () => {
    try {
      setTopics(await listTopics());
    } catch {
      // The list still works without them; a topic bar that failed to load is not worth
      // an error banner over the documents it was meant to filter.
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    void refreshTopics();
  }, [refreshTopics]);

  /*
   * Indexing runs off the write path, so a document is listed before it is searchable.
   * Polled rather than pushed: the gap is a few seconds for a note and under a minute for
   * a long PDF, which is not worth a socket, and the poll stops as soon as it reaches
   * zero rather than running for the life of the page.
   */
  useEffect(() => {
    let live = true;
    let timer: ReturnType<typeof setTimeout>;

    const check = async () => {
      try {
        const { pending } = await indexingPending();
        if (!live) return;
        setIndexing(pending);
        if (pending > 0) timer = setTimeout(() => void check(), 1500);
      } catch {
        // Not worth an error on this page: the count is reassurance, and the documents
        // themselves are listed either way.
        if (live) setIndexing(0);
      }
    };

    void check();
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [notes.length]);

  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (!title.trim() || !content.trim() || saving) return;

    setSaving(true);
    setError(null);
    try {
      await createNote(title, content, filing);
      setTitle("");
      setContent("");
      await refresh();
      await refreshTopics();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSaving(false);
    }
  }

  async function saveDraft(event: React.FormEvent) {
    event.preventDefault();
    if (!draft || !draft.title.trim() || !draft.content.trim() || saving) return;

    setSaving(true);
    setError(null);
    try {
      await updateNote(draft.id, draft.title, draft.content, draft.topicId);
      setDraft(null);
      await refresh();
      await refreshTopics();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSaving(false);
    }
  }

  async function remove(id: string) {
    setError(null);
    try {
      await deleteNote(id);
      await refresh();
      await refreshTopics();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }

  return (
    <div className="column">
      <header className="page-head">
        <h1>Your library</h1>
        <p className="muted">
          {total === 0
            ? "Add documents here. Once they are in, you can ask questions about them and every answer will point back to the page it came from."
            : `${total} ${total === 1 ? "document" : "documents"}. Ask a question and every answer points back to the page it came from.`}
        </p>
      </header>

      {indexing > 0 && (
        <p className="indexing" aria-live="polite">
          <span className="indexing-dot" />
          Indexing {indexing} {indexing === 1 ? "document" : "documents"}. They are listed
          below already and become searchable as this finishes.
        </p>
      )}

      <AddDocuments
        onAdded={() => {
          void refresh();
          void refreshTopics();
        }}
        topics={topics}
        topicId={filing}
        onTopicChange={setFiling}
        onTopicCreated={(topic) => setTopics((held) => withTopic(held, topic))}
      />

      {topics.length > 0 && (
        <nav className="topicbar" aria-label="Filter by topic">
          <button
            type="button"
            className={`topicchip${showing === undefined ? " topicchip-on" : ""}`}
            aria-pressed={showing === undefined}
            onClick={() => setShowing(undefined)}
          >
            All
          </button>
          {topics.map((topic) => (
            <button
              key={topic.id}
              type="button"
              className={`topicchip${showing === topic.id ? " topicchip-on" : ""}`}
              aria-pressed={showing === topic.id}
              onClick={() => setShowing(topic.id)}
            >
              {topic.name}
              <span className="topicchip-count">{topic.documentCount}</span>
            </button>
          ))}
          <button
            type="button"
            className={`topicchip${showing === "none" ? " topicchip-on" : ""}`}
            aria-pressed={showing === "none"}
            onClick={() => setShowing("none")}
          >
            No topic
          </button>
        </nav>
      )}

      <div className="write-toggle">
        <button type="button" className="linklike" onClick={() => setWriting((open) => !open)}>
          {writing ? "Never mind" : "…or write one yourself"}
        </button>
      </div>

      {writing && (
      <form onSubmit={save} className="composer">
        <input
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          placeholder="Title"
          aria-label="Note title"
        />
        <textarea
          value={content}
          onChange={(event) => setContent(event.target.value)}
          placeholder="Write the note. It becomes searchable once indexing catches up."
          aria-label="Note content"
          rows={6}
        />
        <div className="composer-foot">
          {/* Says where this will land rather than offering a second control for it.
              The picker in the panel above sets the same value, and two selects with
              one name between them is a choice people make twice. */}
          <span className="muted">
            {filing
              ? `Filed under ${topics.find((topic) => topic.id === filing)?.name ?? "a topic"}. `
              : "No topic. "}
            Searchable once indexing catches up.
          </span>
          <button type="submit" disabled={saving || !title.trim() || !content.trim()}>
            {saving ? "Saving…" : "Add document"}
          </button>
        </div>
      </form>
      )}

      {error && <p className="error">{error}</p>}

      <ul className="notes">
        {notes.map((note) =>
          draft?.id === note.id ? (
            <li key={note.id}>
              <form onSubmit={saveDraft} className="composer">
                <input
                  value={draft.title}
                  onChange={(event) => setDraft({ ...draft, title: event.target.value })}
                  aria-label="Edit title"
                />
                <textarea
                  value={draft.content}
                  onChange={(event) => setDraft({ ...draft, content: event.target.value })}
                  aria-label="Edit content"
                  rows={8}
                />
                <TopicPicker
                  topics={topics}
                  value={draft.topicId}
                  onChange={(topicId) => setDraft({ ...draft, topicId })}
                  onCreated={(topic) => setTopics((held) => withTopic(held, topic))}
                />
                <div className="composer-foot">
                  <span className="muted">
                    Saved text changes at once; the index catches up after.
                  </span>
                  <span className="edit-actions">
                    <button type="button" onClick={() => setDraft(null)}>
                      Cancel
                    </button>
                    <button
                      type="submit"
                      disabled={saving || !draft.title.trim() || !draft.content.trim()}
                    >
                      {saving ? "Saving…" : "Save"}
                    </button>
                  </span>
                </div>
              </form>
            </li>
          ) : (
            <li key={note.id}>
              <div className="note-head">
                <button
                  className="note-title"
                  onClick={() => setExpanded(expanded === note.id ? null : note.id)}
                  aria-expanded={expanded === note.id}
                >
                  {note.title}
                </button>
                {note.topicId && (
                  <span className="badge badge-topic">
                    {topics.find((topic) => topic.id === note.topicId)?.name ?? "Topic"}
                  </span>
                )}
                <span className="muted">{note.content.length} chars</span>
                <button
                  className="icon"
                  onClick={() => {
                    setDraft({
                      id: note.id,
                      title: note.title,
                      content: note.content,
                      topicId: note.topicId,
                    });
                    setExpanded(null);
                  }}
                  aria-label={`Edit ${note.title}`}
                >
                  Edit
                </button>
                <button
                  className="icon icon-danger"
                  onClick={() => remove(note.id)}
                  aria-label={`Delete ${note.title}`}
                >
                  ✕
                </button>
              </div>
              {expanded === note.id && <div className="document">{note.content}</div>}
            </li>
          ),
        )}
      </ul>

      {notes.length === 0 && !error && (
        <p className="muted">
          {showing === undefined
            ? "Nothing here yet. Drop a file above to get started."
            : "Nothing filed here yet. Documents keep their place in the library either way — choose All to see them."}
        </p>
      )}
    </div>
  );
}
