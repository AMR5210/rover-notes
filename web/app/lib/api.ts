/**
 * Typed client for the Spring API.
 *
 * Requests go to same-origin `/api/*`, which `next.config.ts` rewrites to the Spring
 * service. The service owns the API contract; nothing is reimplemented here.
 *
 * The types mirror the Java records exactly, including fields the interface chooses not
 * to show. `charStart` and `charEnd` are the reason this file exists in a typed form: a
 * citation addresses a span of its document's text, and the interface is what turns that
 * from a pair of integers into a highlighted sentence.
 */

import { silentSignIn, token } from "./auth";

export type RetrievalMode = "HYBRID" | "DENSE" | "LEXICAL";

export interface Hit {
  chunkId: string;
  documentId: string;
  title: string;
  snippet: string;
  charStart: number;
  charEnd: number;
  score: number;
}

export interface SearchResponse {
  query: string;
  /** The channel that actually answered, which is not always the one asked for. */
  mode: RetrievalMode;
  reranked: boolean;
  count: number;
  results: Hit[];
}

export interface Citation {
  number: number;
  chunkId: string;
  documentId: string;
  title: string;
  charStart: number;
  charEnd: number;
  score: number;
}

export interface AnswerResponse {
  content: string;
  citations: Citation[];
}

export interface Note {
  id: string;
  title: string;
  content: string;
  /** The topic this document sits in, or null when it has not been filed. */
  topicId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Topic {
  id: string;
  name: string;
  createdAt: string;
  documentCount: number;
}

/**
 * Which slice of the library to list: everything, one topic, or the unfiled documents.
 *
 * `undefined` and `"none"` are different requests and the API reads them that way — the
 * first is every document, the second is only those with no topic.
 */
export type TopicSelection = string | "none" | undefined;

export interface NotePage {
  items: Note[];
  total: number;
  limit: number;
  offset: number;
}

/**
 * Adds the bearer token, if one is held.
 *
 * Absent where the API permits unauthenticated requests, which the local development
 * profile does. Sending no header is then correct rather than a degraded case.
 */
function authorized(headers: HeadersInit | undefined): HeadersInit {
  const held = token();
  return {
    "content-type": "application/json",
    ...(held ? { authorization: `Bearer ${held}` } : {}),
    ...(headers ?? {}),
  };
}

/**
 * Sends the request, and on a 401 tries once to obtain a token and send it again.
 *
 * The retry is what stands in for a refresh token, which a public client does not get. An
 * access token lasts fifteen minutes while the sign-in cookie lasts far longer, so the
 * first request after a token expires is the natural moment to quietly get another.
 *
 * Exactly one retry. A 401 that survives a fresh token is a real refusal, and repeating
 * would turn an expired session into a loop.
 */
async function send(path: string, init?: RequestInit): Promise<Response> {
  const first = await fetch(path, { ...init, headers: authorized(init?.headers) });
  if (first.status !== 401) return first;

  const renewed = await silentSignIn();
  if (!renewed) return first;
  return fetch(path, { ...init, headers: authorized(init?.headers) });
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await send(path, init);
  if (!response.ok) {
    // The API answers errors as a problem document; its body is more useful than the
    // status alone, and the interface shows it rather than a generic failure message.
    const body = await response.text();
    throw new Error(body || `${response.status} ${response.statusText}`);
  }
  return (await response.json()) as T;
}

export interface SearchOptions {
  limit?: number;
  mode?: string;
  rerank?: boolean;
  route?: boolean;
}

export function search(query: string, options: SearchOptions = {}): Promise<SearchResponse> {
  const params = new URLSearchParams({ q: query });
  if (options.limit) params.set("limit", String(options.limit));
  if (options.mode) params.set("mode", options.mode);
  if (options.rerank !== undefined) params.set("rerank", String(options.rerank));
  if (options.route !== undefined) params.set("route", String(options.route));
  return request<SearchResponse>(`/api/search?${params}`);
}

export function ask(question: string): Promise<AnswerResponse> {
  return request<AnswerResponse>("/api/ask", {
    method: "POST",
    body: JSON.stringify({ question }),
  });
}

export interface StreamHandlers {
  /** Called once, before any text: the passages the answer may cite. */
  onCitations: (citations: Citation[]) => void;
  onDelta: (text: string) => void;
}

/** The `error` event's payload: why the answer stopped, in the provider's words too. */
interface Refused {
  reason: string;
  detail?: string;
  retryAfterSeconds?: number;
}

/**
 * Asks the question and reports the answer as it is written.
 *
 * `EventSource` is not used because it only issues GET requests, and the question belongs
 * in a body rather than a query string. This reads the response body directly and parses
 * the event framing, which is a few lines and avoids reshaping the API around a browser
 * class's limitation.
 *
 * Rejects on an `error` event, which the API sends when the model provider refuses
 * mid-answer. The status is already 200 by then — it is sent with the citations, before
 * the first token is asked for — so the refusal can only arrive in the stream. Ignoring
 * the event leaves a caller with a short answer and nothing to show for it: the stream
 * ended, and an SSE stream ending is what success looks like. Rejecting puts it where
 * every other failure of this call already goes.
 */
export async function askStream(
  question: string,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const response = await send("/api/ask/stream", {
    method: "POST",
    headers: { accept: "text/event-stream" },
    body: JSON.stringify({ question }),
    signal,
  });
  if (!response.ok || !response.body) {
    throw new Error((await response.text()) || `${response.status} ${response.statusText}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    // Events are separated by a blank line. The last piece is usually a partial event,
    // so it stays in the buffer until the rest of it arrives.
    const frames = buffer.split("\n\n");
    buffer = frames.pop() ?? "";

    for (const frame of frames) {
      let name = "message";
      const data: string[] = [];
      for (const line of frame.split("\n")) {
        if (line.startsWith("event:")) name = line.slice(6).trim();
        // A colon-space is optional in the framing, so only the colon is stripped here.
        else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
      }
      if (data.length === 0) continue;

      const payload = JSON.parse(data.join("\n"));
      if (name === "citations") handlers.onCitations(payload as Citation[]);
      else if (name === "delta") handlers.onDelta((payload as { text: string }).text);
      else if (name === "error") {
        const refused = payload as Refused;
        // Cancelled before throwing, so the request does not stay open on a body nobody
        // is going to read.
        await reader.cancel();
        throw new Error(
          refused.detail ? `${refused.reason}: ${refused.detail}` : refused.reason,
        );
      }
    }
  }
}

export interface ModelTotals {
  modelId: string;
  calls: number;
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
}

export interface DayTotals {
  day: string;
  calls: number;
  costUsd: number;
}

/** `capUsd` and `remainingUsd` are null where no cap is configured. */
export interface UsageSummary {
  windowHours: number;
  capUsd: number | null;
  spentUsd: number;
  remainingUsd: number | null;
  calls: number;
  inputTokens: number;
  outputTokens: number;
  byModel: ModelTotals[];
  daily: DayTotals[];
}

export function usage(): Promise<UsageSummary> {
  return request<UsageSummary>("/api/usage");
}

/** Documents whose indexing has not finished. Zero in the steady state. */
export function indexingPending(): Promise<{ pending: number }> {
  return request<{ pending: number }>("/api/ingestion/status");
}

export function listNotes(
  limit = 50,
  offset = 0,
  topic?: TopicSelection,
): Promise<NotePage> {
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (topic) params.set("topic", topic);
  return request<NotePage>(`/api/notes?${params}`);
}

export function listTopics(): Promise<Topic[]> {
  return request<Topic[]>("/api/topics");
}

export function createTopic(name: string): Promise<Topic> {
  return request<Topic>("/api/topics", {
    method: "POST",
    body: JSON.stringify({ name }),
  });
}

export function renameTopic(id: string, name: string): Promise<Topic> {
  return request<Topic>(`/api/topics/${id}`, {
    method: "PUT",
    body: JSON.stringify({ name }),
  });
}

/** Removes the topic. The documents in it are kept and become unfiled. */
export async function deleteTopic(id: string): Promise<void> {
  const response = await send(`/api/topics/${id}`, { method: "DELETE" });
  if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
}

export function getNote(id: string): Promise<Note> {
  return request<Note>(`/api/notes/${id}`);
}

export function createNote(
  title: string,
  content: string,
  topicId?: string | null,
): Promise<Note> {
  return request<Note>("/api/notes", {
    method: "POST",
    body: JSON.stringify({ title, content, topicId: topicId ?? null }),
  });
}

/**
 * Ingests a file — a PDF, or anything else the parsing service can read.
 *
 * Deliberately does not go through `authorized`, which sets a JSON content type on every
 * request. A multipart body carries a boundary token in its content type, and the browser
 * is the only thing that knows it; setting the header by hand produces a body the server
 * cannot split. Omitting it entirely is what lets `fetch` fill it in.
 */
export async function uploadFile(
  file: File,
  title?: string,
  topicId?: string | null,
): Promise<Note> {
  const body = new FormData();
  body.append("file", file);
  if (title?.trim()) body.append("title", title.trim());

  const held = token();
  const headers: HeadersInit = held ? { authorization: `Bearer ${held}` } : {};

  // The topic rides in the query string rather than the body: the endpoint takes a
  // multipart form and adding a field to it would mean the server parsing one to read it.
  const path = topicId ? `/api/notes/upload?topicId=${topicId}` : "/api/notes/upload";

  let response = await fetch(path, { method: "POST", body, headers });
  if (response.status === 401 && (await silentSignIn())) {
    const renewed = token();
    response = await fetch(path, {
      method: "POST",
      body,
      headers: renewed ? { authorization: `Bearer ${renewed}` } : {},
    });
  }

  if (!response.ok) throw new Error((await response.text()) || `${response.status}`);
  return (await response.json()) as Note;
}

/** Captures a web page as a document. Fetched server-side, so the URL is all that goes. */
export function clipUrl(url: string, topicId?: string | null): Promise<Note> {
  return request<Note>("/api/notes/clip", {
    method: "POST",
    body: JSON.stringify({ url, topicId: topicId ?? null }),
  });
}

export function updateNote(
  id: string,
  title: string,
  content: string,
  topicId?: string | null,
): Promise<Note> {
  return request<Note>(`/api/notes/${id}`, {
    method: "PUT",
    body: JSON.stringify({ title, content, topicId: topicId ?? null }),
  });
}

export async function deleteNote(id: string): Promise<void> {
  const response = await send(`/api/notes/${id}`, { method: "DELETE" });
  if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
}
