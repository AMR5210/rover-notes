"use client";

import { useEffect, useState } from "react";

import { usage, type UsageSummary } from "../lib/api";

/** Two decimals is below the cost of a single call, so smaller figures need more. */
function money(usd: number): string {
  const value = Number(usd);
  if (value === 0) return "$0.00";
  return value < 0.01 ? `$${value.toFixed(4)}` : `$${value.toFixed(2)}`;
}

function count(n: number): string {
  return Number(n).toLocaleString("en-US");
}

/**
 * What the caller has spent, against the cap their next request is checked against.
 *
 * The window here is the cap's window, read from the same configuration the limit is
 * enforced with, so a refusal cannot quote a figure this page never showed. The bar is
 * the share of the cap consumed; with no cap configured there is nothing to draw and the
 * page reports spend alone.
 */
export default function UsagePage() {
  const [summary, setSummary] = useState<UsageSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    usage()
      .then(setSummary)
      .catch((cause) => setError(cause instanceof Error ? cause.message : String(cause)));
  }, []);

  if (error) {
    return (
      <div className="column">
        <p className="error">{error}</p>
      </div>
    );
  }

  if (!summary) {
    return (
      <div className="column">
        <p className="muted">Loading usage…</p>
      </div>
    );
  }

  const capped = summary.capUsd !== null;
  // Clamped so an over-spend fills the bar rather than overflowing its track.
  const share = capped ? Math.min(1, summary.spentUsd / summary.capUsd!) : 0;

  return (
    <div className="column">
      <section className="usage-head">
        <div>
          <h2 className="usage-total" data-testid="usage-spent">
            {money(summary.spentUsd)}
          </h2>
          <p className="muted">
            {capped ? (
              <>
                of {money(summary.capUsd!)} over the last {summary.windowHours}h ·{" "}
                <span data-testid="usage-remaining">{money(summary.remainingUsd!)}</span> left
              </>
            ) : (
              <>over the last {summary.windowHours}h · no cap configured</>
            )}
          </p>
        </div>
        <dl className="usage-figures">
          <div>
            <dt>Calls</dt>
            <dd data-testid="usage-calls">{count(summary.calls)}</dd>
          </div>
          <div>
            <dt>Input tokens</dt>
            <dd>{count(summary.inputTokens)}</dd>
          </div>
          <div>
            <dt>Output tokens</dt>
            <dd>{count(summary.outputTokens)}</dd>
          </div>
        </dl>
      </section>

      {capped && (
        <div
          className="meter"
          role="meter"
          aria-label="Share of the spend cap used"
          aria-valuenow={Math.round(share * 100)}
          aria-valuemin={0}
          aria-valuemax={100}
        >
          <span className={share >= 1 ? "meter-fill meter-full" : "meter-fill"} style={{ width: `${share * 100}%` }} />
        </div>
      )}

      <h3 className="usage-section">By model</h3>
      {summary.byModel.length === 0 ? (
        <p className="muted">No model calls in this window.</p>
      ) : (
        <table className="figures">
          <thead>
            <tr>
              <th>Model</th>
              <th className="num">Calls</th>
              <th className="num">In</th>
              <th className="num">Out</th>
              <th className="num">Cost</th>
            </tr>
          </thead>
          <tbody>
            {summary.byModel.map((model) => (
              <tr key={model.modelId}>
                <td>
                  <code>{model.modelId}</code>
                </td>
                <td className="num">{count(model.calls)}</td>
                <td className="num">{count(model.inputTokens)}</td>
                <td className="num">{count(model.outputTokens)}</td>
                <td className="num">{money(model.costUsd)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h3 className="usage-section">Last seven days</h3>
      {summary.daily.length === 0 ? (
        <p className="muted">No model calls in the last seven days.</p>
      ) : (
        <table className="figures">
          <thead>
            <tr>
              <th>Day</th>
              <th className="num">Calls</th>
              <th className="num">Cost</th>
            </tr>
          </thead>
          <tbody>
            {summary.daily.map((day) => (
              <tr key={day.day}>
                <td>{day.day}</td>
                <td className="num">{count(day.calls)}</td>
                <td className="num">{money(day.costUsd)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <p className="hint">
        Days with no spend are absent rather than reported as zero, and the daily table
        covers seven days regardless of the cap window above.
      </p>
    </div>
  );
}
