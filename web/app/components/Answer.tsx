"use client";

import { Fragment } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

import type { Citation } from "../lib/api";
import { citationHandler, remarkCitations } from "../lib/remark-citations";

/** Matches the bracketed references the model is asked to produce: [1] or [2, 3]. */
const CITATION = /\[(\d+(?:\s*,\s*\d+)*)\]/g;

/**
 * Renders an answer as Markdown, with its citations as controls rather than text.
 *
 * The model writes prose with ordinary Markdown structure — headings, lists, emphasis —
 * and cites sources by bracketed number, which indexes the citation list the API returns
 * alongside the answer. `remarkCitations` splits those brackets into their own nodes
 * during parsing, so a citation survives the Markdown pipeline as something this component
 * can still turn into a button: the reader follows the number to the passage instead of
 * taking the sentence on trust.
 *
 * A reference to a number the response did not supply is rendered as plain text rather
 * than a dead control. The generation eval measures how often that happens — currently
 * never over 128 questions — and an interface that silently rendered it as a working
 * link would hide the failure if it started.
 */

/** A bracket that has been opened but not yet closed at the end of the text so far. */
const PARTIAL_CITATION = /\[\d*(?:\s*,\s*\d*)*$/;

export function Answer({
  content,
  citations,
  streaming = false,
  onCite,
}: {
  content: string;
  citations: Citation[];
  streaming?: boolean;
  onCite: (citation: Citation) => void;
}) {
  // While text is still arriving, a reference split across two chunks would show as a
  // bare "[1" until its closing bracket lands. Holding the fragment back costs a few
  // characters of latency and avoids text that appears to be malformed and then repairs
  // itself.
  const text = streaming ? content.replace(PARTIAL_CITATION, "") : content;

  const byNumber = new Map(citations.map((citation) => [citation.number, citation]));

  return (
    <div className="answer">
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkCitations]}
        remarkRehypeOptions={{ handlers: { citation: citationHandler } }}
        components={{
          citation: ({ children }) => {
            const numbers = String(children)
              .split(",")
              .map((piece) => Number(piece));
            return (
              <>
                [
                {numbers.map((number, index) => {
                  const citation = byNumber.get(number);
                  return (
                    <Fragment key={number}>
                      {index > 0 && ", "}
                      {citation ? (
                        <button
                          className="cite"
                          onClick={() => onCite(citation)}
                          title={`${citation.title}, characters ${citation.charStart} to ${citation.charEnd}`}
                        >
                          {number}
                        </button>
                      ) : (
                        <span className="cite-missing" title="No source was returned for this number">
                          {number}
                        </span>
                      )}
                    </Fragment>
                  );
                })}
                ]
              </>
            );
          },
        }}
      >
        {text}
      </ReactMarkdown>
      {streaming && <span className="caret" aria-hidden="true" />}
    </div>
  );
}

/**
 * Lists the sources behind an answer, cited ones first.
 *
 * Retrieval hands the model ten passages and a measured mean of 1.45 of them are cited,
 * so listing all ten with equal weight would misrepresent which ones the answer rests on.
 * The rest stay available, because a reader checking an answer sometimes wants to see
 * what was considered and not used.
 */
export function Sources({
  citations,
  content,
  onCite,
}: {
  citations: Citation[];
  content: string;
  onCite: (citation: Citation) => void;
}) {
  const referenced = new Set(
    [...content.matchAll(CITATION)].flatMap((match) =>
      match[1].split(",").map((piece) => Number(piece.trim())),
    ),
  );
  const cited = citations.filter((citation) => referenced.has(citation.number));
  const unused = citations.filter((citation) => !referenced.has(citation.number));

  return (
    <div className="sources">
      <h3>
        Cited <span className="count">{cited.length}</span>
      </h3>
      {cited.length === 0 && <p className="muted">This answer cites no sources.</p>}
      <ul>
        {cited.map((citation) => (
          <li key={citation.chunkId}>
            <button className="source" onClick={() => onCite(citation)}>
              <span className="source-number">{citation.number}</span>
              <span className="source-title">{citation.title}</span>
              <span className="source-span">
                {citation.charStart}-{citation.charEnd}
              </span>
            </button>
          </li>
        ))}
      </ul>

      {unused.length > 0 && (
        <details>
          <summary>
            {unused.length} retrieved and not cited
          </summary>
          <ul>
            {unused.map((citation) => (
              <li key={citation.chunkId}>
                <button className="source dim-source" onClick={() => onCite(citation)}>
                  <span className="source-number">{citation.number}</span>
                  <span className="source-title">{citation.title}</span>
                  <span className="source-span">
                    {citation.charStart}-{citation.charEnd}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  );
}
