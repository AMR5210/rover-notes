import Link from "next/link";
import { Fraunces, Plus_Jakarta_Sans } from "next/font/google";

import { StartHere } from "./components/StartHere";
import "./landing.css";

/**
 * What the product is, for someone who has not seen it before.
 *
 * Static and server-rendered: nothing here needs the API, so it loads on a cold start and
 * without a session. The only client boundary is the call to action, which has to be one
 * because signing in is a redirect rather than a route of this application.
 *
 * Every figure quoted below is one the repository can reproduce — the retrieval scores
 * come from `evals/baseline.json` and the SciFact run, the generation scores from the
 * judged eval, the suite size from `./gradlew test`. Keeping them sourced is what makes
 * them worth putting on a page that is otherwise marketing.
 *
 * Typography is scoped to this page rather than added to the application's font stack:
 * a light-weight display serif for headings and a geometric sans for body copy, loaded
 * here and applied only within `.landing` via the variables below.
 */
const fraunces = Fraunces({ subsets: ["latin"], weight: ["300"], variable: "--landing-serif" });
const jakarta = Plus_Jakarta_Sans({
  subsets: ["latin"],
  weight: ["500", "600"],
  variable: "--landing-sans",
});

const STEPS = [
  {
    n: "01",
    title: "Point it at your documents",
    body: "PDFs, Markdown, plain text, or the address of a web page. Each one is split into overlapping windows, and every piece keeps the character offsets it came from.",
  },
  {
    n: "02",
    title: "Two indexes, one query",
    body: "Every piece is embedded for meaning and indexed for words. A question runs against both and the rankings are fused, so a paraphrase and an exact identifier both land.",
  },
  {
    n: "03",
    title: "Ask it, or connect it",
    body: "Ask in the browser and read the answer. Or connect it over MCP, and Claude Code and other assistants can search the same documents as one of their tools.",
  },
  {
    n: "04",
    title: "Check any claim",
    body: "Every sentence carries the number of the passage behind it, and every number opens that passage highlighted at the exact characters it occupies in the original.",
  },
];

const EXAMPLES = [
  {
    kind: "An exact term",
    question: "What is PARROTVALVE?",
    note: "A name that appears once and means nothing to a model. Word matching finds it; embeddings on their own often do not.",
  },
  {
    kind: "Different words, same thing",
    question: "How does it recover when two depots block each other?",
    note: "Shares no vocabulary with the document, which calls it deadlock recovery. Meaning matching finds it; word matching does not.",
  },
  {
    kind: "Spread across sections",
    question: "Which sentinel load is inserted, and what does it mean in a manifest?",
    note: "The answer is in two places. The agent loop searches again rather than answering from the first passage it sees.",
  },
  {
    kind: "Not in your documents",
    question: "What is the parental leave policy?",
    note: "Nothing covers it, so it declines. Over the eight unanswerable questions in the eval set, it declines to all eight.",
  },
];

const USES = [
  {
    title: "On-call, at three in the morning",
    body: "Ask the runbook what to do and get the paragraph that says it. A step you can check is one you can act on without waking someone.",
  },
  {
    title: "Engineering handbooks",
    body: "Design docs, ADRs and postmortems answered with the passage that decided it, so a claim can be verified rather than trusted.",
  },
  {
    title: "Research libraries",
    body: "Scored on SciFact — 5,183 abstracts and 300 claims that were written by somebody else — not only on the corpus it was tuned against.",
  },
  {
    title: "Inside the assistant you already use",
    body: "The same retrieval is exposed over MCP as search, get_document and list_documents, so your own corpus becomes a tool your assistant can cite.",
  },
];

const NUMBERS = [
  { figure: "0.6804", label: "nDCG@10 on SciFact", note: "a public benchmark: 5,183 abstracts, 300 claims" },
  { figure: "0.9723", label: "citation precision", note: "0.9688 faithfulness, LLM-judged" },
  { figure: "0.8254", label: "nDCG@10, own corpus", note: "128 queries over 42 documents" },
  { figure: "8 of 8", label: "unanswerable declined", note: "questions the corpus does not cover" },
  { figure: "406", label: "JVM tests", note: "against a real pgvector container" },
];

const FEATURES = [
  {
    title: "A tool for your assistant",
    body: "An MCP server over streamable HTTP exposing search, get_document and list_documents. Point a client at the URL and your documents become something it can query and quote.",
  },
  {
    title: "Citations with offsets",
    body: "A citation is a character range in a document, not a filename. That is what lets the interface open the source at the exact span instead of at the top of the page.",
  },
  {
    title: "Hybrid retrieval",
    body: "A dense channel over pgvector's HNSW index and a lexical channel over Postgres full-text search, fused with reciprocal rank fusion. The pair scores above either alone on both corpora.",
  },
  {
    title: "Answers it can support",
    body: "The model is given the retrieved passages and nothing else, and is scored on whether each sentence follows from them and whether each citation supports the sentence attached to it.",
  },
  {
    title: "Your documents stay yours",
    body: "Postgres for storage and vectors, embeddings served from a container you run, and OAuth 2.1 with PKCE in front. Nothing is sent to a third-party index.",
  },
  {
    title: "Measured, not asserted",
    body: "Retrieval changes are recorded with their measured delta and a paired significance test. A change that does not clear the gate does not land.",
  },
];

export default function LandingPage() {
  return (
    <div className={`landing ${fraunces.variable} ${jakarta.variable}`}>
      <section className="hero">
        <div className="hero-orbit" aria-hidden="true">
          <span className="hero-blob" style={{ background: "var(--pill-yellow)", top: "8%", left: "40%" }} />
          <span className="hero-blob" style={{ background: "var(--pill-mint)", top: "18%", right: "22%" }} />
          <span className="hero-blob" style={{ background: "var(--pill-periwinkle)", top: "30%", left: "16%" }} />
          <span className="hero-blob" style={{ background: "var(--pill-salmon)", bottom: "10%", left: "2%" }} />
          <span className="hero-blob" style={{ background: "var(--pill-sage)", bottom: "14%", right: "20%" }} />

          {FEATURES.map((feature, i) => (
            <span
              key={feature.title}
              className={`hero-pill pill-${i + 1} ${i === 2 || i === 3 ? "hero-pill-mid" : "hero-pill-corner"}`}
              style={{ animationDelay: `${i * 0.3}s` }}
            >
              {feature.title}
            </span>
          ))}
        </div>

        <div className="hero-inner">
          <p className="eyebrow">
            <span className="dot" /> A grounded search layer for documents you already have
          </p>
          <h1>
            Make your documents
            <br />
            <span className="gradient">answerable, and checkable.</span>
          </h1>
          <p className="lede">
            Point Rover Notes at your PDFs, notes and web pages. Ask it questions in the
            browser, or connect it to Claude Code and other assistants over MCP so they can
            search your documents as a tool. Every answer names the passages it used, and
            every citation opens the source at the exact characters it came from.
          </p>
          <StartHere />
          <p className="fineprint">
            No account needed to try the demo corpus. Sign in to add your own documents.
          </p>
        </div>
      </section>

      <section className="band" id="demo">
        <div className="band-inner">
          <h2>A question, its sources, and the exact words</h2>
          <p className="section-lede">
            The passages are chosen before a word is written, so a reference is followable
            as it appears. Clicking one opens the document at the span it names.
          </p>
          {/* Plain <img>: an animated GIF is not something next/image optimises, and the
              asset is committed at a size that does not need it. */}
          <img
            className="demo"
            src="/demo.gif"
            alt="A question is asked, passages appear before the answer begins, and clicking a citation opens the source document with the cited span highlighted."
            width={800}
            height={475}
          />
        </div>
      </section>

      <section className="band alt" id="how">
        <div className="band-inner">
          <h2>How it works</h2>
          <p className="section-lede">
            Four stages, each of which is measured on its own rather than only at the end.
          </p>
          <ol className="steps">
            {STEPS.map((step) => (
              <li key={step.n}>
                <span className="step-n">{step.n}</span>
                <h3>{step.title}</h3>
                <p>{step.body}</p>
              </li>
            ))}
          </ol>
        </div>
      </section>

      <section className="band" id="examples">
        <div className="band-inner">
          <h2>The questions it is built for</h2>
          <p className="section-lede">
            Word matching and meaning matching fail in different places, so both run and
            their rankings are fused. These are the four cases that separates.
          </p>
          <ul className="examples">
            {EXAMPLES.map((example) => (
              <li key={example.question}>
                <span className="kind">{example.kind}</span>
                <p className="question">{example.question}</p>
                <p className="note">{example.note}</p>
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section className="band alt" id="features">
        <div className="band-inner">
          <h2>What is underneath</h2>
          <ul className="features">
            {FEATURES.map((feature) => (
              <li key={feature.title}>
                <h3>{feature.title}</h3>
                <p>{feature.body}</p>
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section className="band" id="uses">
        <div className="band-inner">
          <h2>Who it is for</h2>
          <ul className="uses">
            {USES.map((use) => (
              <li key={use.title}>
                <h3>{use.title}</h3>
                <p>{use.body}</p>
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section className="band alt" id="numbers">
        <div className="band-inner">
          <h2>Measured, and reproducible from this repository</h2>
          <p className="section-lede">
            Retrieval quality is scored against SciFact — a public benchmark, someone
            else's documents and someone else's labels — as well as against the corpus it
            was tuned on. Every figure comes from a command in this repository, and the
            full table naming what was run for each is in the README.
          </p>
          <ul className="numbers">
            {NUMBERS.map((stat) => (
              <li key={stat.label}>
                <span className="figure">{stat.figure}</span>
                <span className="figure-label">{stat.label}</span>
                <span className="figure-note">{stat.note}</span>
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section className="closer">
        <div className="band-inner">
          <h2>Point it at your own documents</h2>
          <p className="section-lede">
            The demo corpus is loaded and answerable without signing in. An account lets
            you add your own — drop in a PDF, paste a link — and connect an assistant to
            them over MCP.
          </p>
          <StartHere tone="quiet" />
        </div>
      </section>

      <footer className="landing-foot">
        <span>Rover Notes</span>
        <span className="foot-links">
          <Link href="/ask">Ask</Link>
          <Link href="/search">Search</Link>
          <Link href="/notes">Library</Link>
          <Link href="/usage">Usage</Link>
        </span>
      </footer>
    </div>
  );
}
