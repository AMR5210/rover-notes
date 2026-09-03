// Retrieval under concurrency.
//
// Every latency figure in `docs/RESULTS.md` comes from the eval harness, which issues one
// request at a time. A p95 measured serially is not a p95 under load, and the stated
// budget — under 150 ms before the LLM call — is a claim about a system serving people
// concurrently. This is the test that decides whether that claim holds.
//
// Queries are drawn from the golden set rather than repeated, because one query repeated
// measures a warm path: the same embedding, the same index pages, the same plan. Real
// traffic does not do that, and neither should this.
//
//   k6 run -e VUS=20 load/search.js
//
// VUS defaults to 20, which is the concurrency the roadmap's acceptance criterion names.

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const BASE = __ENV.BASE || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 20);
const DURATION = __ENV.DURATION || '60s';
// Optional, for diagnosis: forcing a channel isolates which stage the time is in.
const MODE = __ENV.MODE || '';

// SharedArray parses once and shares across VUs; without it every VU holds its own copy.
const queries = new SharedArray('queries', function () {
  const lines = open('../evals/golden/seed.jsonl').split('\n')
    .concat(open('../evals/golden/long-documents.jsonl').split('\n'));
  return lines
    .filter((line) => line.trim().length > 0)
    .map((line) => JSON.parse(line))
    .filter((q) => !q.unanswerable)
    .map((q) => q.query);
});

// Separated from the built-in http_req_duration so the retrieval number is not diluted
// by any other request this script makes.
const searchDuration = new Trend('search_duration', true);

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      gracefulStop: '10s',
    },
  },
  // Thresholds fail the run rather than producing a number someone has to interpret.
  // 150 ms is the documented budget; the error rate is here because a fast p95 achieved
  // by failing requests is not a pass.
  thresholds: {
    'search_duration': [{ threshold: 'p(95)<150', abortOnFail: false }],
    'http_req_failed': ['rate<0.01'],
  },
};

export default function () {
  const query = queries[Math.floor(Math.random() * queries.length)];
  const mode = MODE ? `&mode=${MODE}` : '';
  const response = http.get(
    `${BASE}/api/search?q=${encodeURIComponent(query)}&limit=10${mode}`,
    { tags: { name: 'search' } },
  );

  searchDuration.add(response.timings.duration);

  check(response, {
    'status is 200': (r) => r.status === 200,
    'body carries results': (r) => {
      if (r.status !== 200) return false;
      const parsed = r.json();
      return parsed !== null && Array.isArray(parsed.results);
    },
  });
}
