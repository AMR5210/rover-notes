# The modular monolith

The application is one deployable divided into six Spring Modulith modules: api, notes,
ingestion, retrieval, agent, and mcp.

Each module exposes a package-level API and keeps the rest internal. A ModularityTests
case runs Modulith's verification on every build and fails when one module references
another's internal package, which makes the boundary a compile-time property rather than
a review convention.

Cross-module calls go through published application events or an explicitly exported
interface. The retrieval module never imports from notes; it consumes an event instead.
That keeps each module testable with the rest of the application context stubbed out.

Running in one process removes a network hop between retrieval and notes, gives one
transaction boundary, and produces a single trace per request. The cost is that all six
modules scale together, which holds while no single module is the bottleneck. Ingestion
during a bulk import is the first candidate for extraction, and the enforced seam is what
makes that a deployment change rather than a rewrite.
