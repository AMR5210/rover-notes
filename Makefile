.DEFAULT_GOAL := help
SHELL := /bin/bash

API := ./api

# `.env` is where the quick start says to put the API key, the issuer and the mail
# settings, and nothing was reading it. A cold clone that followed those instructions
# exactly answered `/api/ask` with "x-api-key header is required" — a setup step that
# looked like it had worked, and a process that never saw the file.
#
# Sourced rather than pulled in with `include`, because make would read a `#` inside a
# value as the start of a comment and a `$` as a variable reference. Both are legal in a
# generated key or a mail password, and the failure would be a truncated secret rather
# than an error.
#
# Exported as environment variables rather than passed as properties, so Spring's relaxed
# binding applies: `SPRING_MAIL_HOST` reaches `spring.mail.host` only by that route. A
# properties file holding the same names would not bind at all.
DOTENV = set -a; [ -f .env ] && . ./.env; set +a;

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------- local stack

.PHONY: up
up: ## Start Postgres+pgvector, MinIO, and the model servers
	docker compose up -d
	@echo "waiting for postgres..."
	@until docker compose exec -T postgres pg_isready -U rover -d rover >/dev/null 2>&1; do sleep 1; done
	@echo "ready."

.PHONY: down
down: ## Stop the local stack
	docker compose down

.PHONY: nuke
nuke: ## Stop the local stack and delete all volumes
	docker compose down -v

.PHONY: psql
psql: ## Open a psql shell against the local database
	docker compose exec postgres psql -U rover -d rover

# ---------------------------------------------------------------- application

# Flyway runs inside the application at startup, so `make api` also applies any
# pending migrations. CI applies the same files with psql to check them in isolation.
.PHONY: api
api: ## Run the Spring Boot application (applies migrations on startup)
	$(DOTENV) cd $(API) && ./gradlew bootRun --args='--spring.profiles.active=local'

.PHONY: build
build: ## Compile and test everything
	cd $(API) && ./gradlew build

.PHONY: test
test: ## Run the JVM test suite (includes the module-boundary test)
	cd $(API) && ./gradlew test

# Runs what CI's schema job runs, in the same order against the same database. The
# files share state — retrieval-smoke.sql truncates before seeding for that reason —
# so checking one on its own can pass where the sequence fails.
.PHONY: smoke
smoke: ## Replay the CI schema job: migration + both smoke tests, on a clean database
	docker compose exec -T postgres psql -U rover -d rover -q \
		-c 'drop schema public cascade; create schema public; grant all on schema public to rover;'
	@for f in $(API)/src/main/resources/db/migration/V1__baseline.sql \
	          $(API)/src/test/resources/db/schema-smoke.sql \
	          $(API)/src/test/resources/db/retrieval-smoke.sql; do \
		echo "--- $$f"; \
		docker compose exec -T postgres psql -U rover -d rover -v ON_ERROR_STOP=1 -q < $$f || exit 1; \
	done
	@echo "schema smoke tests passed."

# Installed on demand rather than assumed. `make web` and `make gate` both ran npm scripts
# against a clone that had never installed anything: a fresh checkout answers
# `next: not found`, and `npm run lint` — where a global tsc happens to be on PATH — type
# checks the browser suite without @playwright/test present, reporting an error on every
# test parameter. CI runs `npm ci` as its own step; nothing outside CI did.
#
# `npm ci` rather than `npm install`, so what is installed is what the lock file pins.
web/node_modules: web/package-lock.json
	cd web && npm ci
	@touch web/node_modules

.PHONY: web
web: web/node_modules ## Run the Next.js dev server
	cd web && npm run dev

.PHONY: web-test
web-test: web/node_modules ## Run the browser tests against a running API (needs `make up` + `make api`)
	cd web && npm test

.PHONY: demo-seed
demo-seed: ## Fill a running instance with the demo library (needs `make up` + `make api`)
	demo/seed.py

.PHONY: ml
ml: ## Run the Python ML service (embeddings, parsing, the eval harness)
	# --extra parsing, because the upload and clip endpoints need it. Without it the
	# service still starts and answers 501 on those two paths, which is the right
	# behaviour for a deployment that does not ingest files and the wrong default for a
	# developer following this Makefile.
	#
	# OCR additionally needs the tesseract binary, which is a system package rather than
	# a Python one: `apt install tesseract-ocr` or `brew install tesseract`. Without it a
	# scanned PDF parses to nothing and says which pages produced none.
	cd ml-service && uv run --extra parsing uvicorn ml_service.main:app --reload --port 8000

# ---------------------------------------------------------------- quality

# The same commands CI's cheap jobs run, in the same order, on this machine. CI is
# dispatched rather than triggered on push — a full run bills 26 billable minutes against
# a 2,000-minute monthly allowance — so this is what a commit is checked against.
#
# It covers four of the five jobs. The eval gate is left out because it needs a seeded
# corpus behind a running API: `make eval-gate` after `make up && make api`.
.PHONY: gate
gate: web/node_modules ## Run what CI runs, locally, before committing
	cd ml-service && uv sync --dev --extra parsing
	cd ml-service && uv run ruff check .
	cd ml-service && uv run ruff format --check .
	cd ml-service && uv run mypy src
	cd ml-service && uv run pytest
	cd ml-service && uv run python -m ml_service.evals.build_known_item --check
	cd web && npm run lint
	cd $(API) && ./gradlew build
	@echo
	@echo "  Local gate passed. The eval gate and the browser tests need a running API:"
	@echo "    make up && make api    then    make eval-gate  /  make web-test"

.PHONY: lint
lint: web/node_modules ## Lint every language in the repo
	cd $(API) && ./gradlew check -x test
	cd ml-service && uv run ruff check . && uv run mypy src
	cd web && npm run lint

# The harness refuses to score a corpus whose document count is not the one it seeded,
# so switching between suites means emptying the tables first. event_publication is
# cleared alongside them: a completed publication left behind is harmless, but an
# incomplete one keeps the indexing backlog gauge above zero, which is what the harness
# waits on.
.PHONY: eval-reset
eval-reset: ## Empty documents, chunks, and the event registry before seeding a suite
	docker compose exec -T postgres psql -U rover -d rover -q \
		-c 'truncate documents, chunks, event_publication cascade;'

.PHONY: eval-seed
eval-seed: ## Ingest evals/corpus into a running API, then score it
	cd ml-service && uv run python -m ml_service.evals.run --seed

.PHONY: eval
eval: ## Score retrieval against the golden set (needs `make up` + `make api`)
	cd ml-service && uv run python -m ml_service.evals.run

.PHONY: eval-baseline
eval-baseline: ## Record the current run as evals/baseline.json
	cd ml-service && uv run python -m ml_service.evals.run --write-baseline

.PHONY: eval-gate
eval-gate: ## Fail if nDCG@10 regressed more than 3% vs evals/baseline.json
	cd ml-service && uv run python -m ml_service.evals.run --gate --threshold 0.03

# Scores the answer rather than the passages retrieved for it. Needs /api/ask to reach a
# model, so the API process needs ANTHROPIC_API_KEY; the judge reaches its own model
# through the API when a key is set, or the Claude Code CLI otherwise. See evals/README.md.
.PHONY: eval-generation
eval-generation: ## Score faithfulness, citation precision, and abstention
	cd ml-service && uv run python -m ml_service.evals.generation

.PHONY: eval-generation-smoke
eval-generation-smoke: ## The same, over one query of each kind
	cd ml-service && uv run python -m ml_service.evals.generation --limit 1

# Reports whether both documents a two-hop question needs reach the generator, which
# recall at a cutoff does not answer on this slice: every query has two or more relevant
# documents, so retrieving one scores 0.5 where a single-document query scores 1.0.
.PHONY: eval-multihop
eval-multihop: ## Whether a two-hop question's sources both reach the model
	cd ml-service && uv run python -m ml_service.evals.multihop

# The four questions that survived depth, the cross-encoder and query expansion. Asking
# whether the loop reaches them needs no judge — which document was retrieved is not a
# question about the prose — so this is the cheapest thing credit buys and the one whose
# answer decides whether the full comparison is worth running.
HARD ?= M030,M032,M033,M034

.PHONY: eval-loop-probe
eval-loop-probe: ## Does the agent loop reach what nothing deterministic could? (no judge)
	cd ml-service && uv run python -m ml_service.evals.multihop --agent --only $(HARD)

.PHONY: eval-known-item
eval-known-item: ## Score the known-item slice against its own baseline
	cd ml-service && uv run python -m ml_service.evals.run \
		--golden ../evals/golden-known-item --baseline ../evals/baseline-known-item.json

.PHONY: eval-known-item-build
eval-known-item-build: ## Regenerate the known-item slice from the corpus
	cd ml-service && uv run python -m ml_service.evals.build_known_item

# A second collection, large enough to resolve differences the 42-document one cannot,
# and judged by people outside this project. Downloads on first use; see
# ml_service/evals/build_beir.py for what is pinned.
.PHONY: eval-scifact-build
eval-scifact-build: ## Download and build the SciFact suite into evals/
	cd ml-service && uv run python -m ml_service.evals.build_beir scifact

.PHONY: eval-scifact-seed
eval-scifact-seed: eval-reset ## Empty the database, ingest SciFact, then score it
	cd ml-service && uv run python -m ml_service.evals.run --seed \
		--corpus ../evals/corpus-scifact \
		--golden ../evals/golden-scifact \
		--baseline ../evals/baseline-scifact.json

.PHONY: eval-scifact
eval-scifact: ## Score the SciFact suite already seeded in the database
	cd ml-service && uv run python -m ml_service.evals.run \
		--corpus ../evals/corpus-scifact \
		--golden ../evals/golden-scifact \
		--baseline ../evals/baseline-scifact.json

.PHONY: eval-known-item-heldout
eval-known-item-heldout: ## Score the held-out identifier suite against its own baseline
	cd ml-service && uv run python -m ml_service.evals.run \
		--golden ../evals/golden-known-item-heldout \
		--baseline ../evals/baseline-known-item-heldout.json

.PHONY: load
load: ## Load-test /api/search at 20 concurrent users (needs `make up` + `make api`)
	k6 run -e VUS=20 load/search.js

.PHONY: mcp-check
mcp-check: ## Drive /mcp from a real Claude Code client and check the answer (see mcp/README.md)
	bash mcp/acceptance.sh

# --------------------------------------------------------- the agent measurement

# The loop against the single pass, over the questions built to tell them apart. This is
# the only thing in this repository that spends money, and the ways to waste it are not
# visible from the command: an unseeded corpus answers every question with "nothing in
# your notes covers this", the judge costs about three times the run when it goes through
# the API rather than the CLI, and a generator left on the committed default costs four
# times what the budget one does. Each of those produces a complete, plausible, worthless
# run. See "Before a paid run" in evals/README.md.

# Haiku rather than the committed Sonnet default. What is under test is the retrieval
# strategy, not the model, and holding the model constant across both halves is what makes
# the comparison valid whichever one it is. Measured at 4.2x cheaper over identical work —
# more than the 3:1 price ratio, because it tokenised the same prompts 28% shorter. See
# `docs/RESULTS.md`.
AGENT_MODEL  ?= claude-haiku-4-5
AGENT_GOLDEN ?= ../evals/golden-multihop
AGENT_RUNS   ?= ../evals/runs

.PHONY: api-measure
api-measure: ## Run the API with the budget generator, for a measurement rather than a demo
	$(DOTENV) cd $(API) && SPRING_AI_ANTHROPIC_CHAT_OPTIONS_MODEL=$(AGENT_MODEL) \
		./gradlew bootRun --args='--spring.profiles.active=local'

# Reads what the model calls actually cost, from the rows they wrote. The model column is
# the point as much as the cost: the override above is applied when the API starts, so a
# forgotten restart is otherwise invisible until the bill.
.PHONY: eval-agent-cost
eval-agent-cost: ## What the model calls of the last hour cost, by task and model
	@docker compose exec -T postgres psql -U rover -d rover -c \
		"select task, model_id, count(*) as calls, sum(input_tokens) as input, \
		        sum(output_tokens) as output, sum(cache_read_tokens) as cache_read, \
		        round(sum(cost_usd), 4) as usd \
		   from llm_usage where created_at > now() - interval '1 hour' \
		  group by task, model_id order by usd desc nulls last;"

.PHONY: eval-agent-smoke
eval-agent-smoke: eval-reset ## One question down each path, to price the real run first
	cd ml-service && uv run python -m ml_service.evals.run --seed >/dev/null
	cd ml-service && uv run python -m ml_service.evals.generation \
		--golden $(AGENT_GOLDEN) --judge cli --limit 1 \
		--out $(AGENT_RUNS)/smoke-single-pass.json
	cd ml-service && uv run python -m ml_service.evals.generation \
		--golden $(AGENT_GOLDEN) --judge cli --limit 1 --agent \
		--out $(AGENT_RUNS)/smoke-agent.json
	@$(MAKE) --no-print-directory eval-agent-cost
	@echo
	@echo "  Multiply the figures above by the number of questions in $(AGENT_GOLDEN)"
	@echo "  before running 'make eval-agent'. Check model_id is $(AGENT_MODEL)."

.PHONY: eval-agent
eval-agent: eval-reset ## Score the loop against the single pass, and report the delta
	cd ml-service && uv run python -m ml_service.evals.run --seed >/dev/null
	cd ml-service && uv run python -m ml_service.evals.generation \
		--golden $(AGENT_GOLDEN) --judge cli \
		--out $(AGENT_RUNS)/agent-comparison-single-pass.json
	cd ml-service && uv run python -m ml_service.evals.generation \
		--golden $(AGENT_GOLDEN) --judge cli --agent \
		--out $(AGENT_RUNS)/agent-comparison-agent.json
	cd ml-service && uv run python -m ml_service.evals.compare \
		$(AGENT_RUNS)/agent-comparison-single-pass.json \
		$(AGENT_RUNS)/agent-comparison-agent.json \
		--metric faithfulness
	@$(MAKE) --no-print-directory eval-agent-cost
