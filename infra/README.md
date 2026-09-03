# Deploying to Azure

Three container apps, a managed PostgreSQL with pgvector, a storage account for uploaded
originals, and a container registry. Everything lives in one resource group, so teardown
is complete and nothing is left billing.

## What gets created

| Resource | Purpose |
|---|---|
| Container Apps environment | Runs the three services, with logs to Log Analytics |
| `*-web` | The frontend. The only public ingress |
| `*-api` | The Spring service. Internal ingress; reached through the frontend |
| `*-ml` | Embeddings, reranking and parsing. Internal ingress |
| PostgreSQL Flexible Server | `Standard_B1ms`, PostgreSQL 17, with `VECTOR` and `PG_TRGM` allow-listed |
| Storage account | One private container for the originals of uploaded files |
| Container registry | Basic tier, no admin user |

## Credentials

The API reads and writes blobs using a **system-assigned managed identity** holding
Storage Blob Data Contributor on that account alone. No storage key is issued, configured
or rotated. Each container app pulls its image with its own identity holding AcrPull, so
the registry has no admin user and no password.

Three secrets are supplied at deploy time and stored as container app secrets: the
database password, the Anthropic API key, and the key-encryption key that protects the
token signing keys at rest.

## Deploying

```bash
export POSTGRES_PASSWORD='...'                       # Azure enforces its own complexity rules
export ANTHROPIC_API_KEY='...'                       # console.anthropic.com
export ROVER_KEY_ENCRYPTION_KEY="$(openssl rand -base64 32)"
export MAIL_HOST='smtp.example.com'                  # account email; see below

az login
infra/deploy.sh
```

`MAIL_HOST` is required because the API will not start without it. Verification and
password-reset email is sent by a component that is active outside the local profile, and
it depends on a mail sender Spring builds only when a host is configured, so a deployment
with no mail fails on boot rather than at the first person who needs a reset. `MAIL_PORT`
(587), `MAIL_USERNAME`, `MAIL_PASSWORD` and `MAIL_FROM` go alongside it and have defaults.

Three values the template derives rather than takes: the issuer, the interface URL and the
registered redirect URI are all the frontend's public origin. That is the address a browser
reaches, since sign-in, the discovery documents and the JWKS are all proxied through it,
so a token issued under the API's internal name would be rejected by the client that
received it. The origin is built from the environment's domain, because the API is declared
before the frontend and referencing it the other way would be a cycle.

The script creates the resource group, deploys the registry, builds and pushes the three
images tagged with the current commit, then deploys everything else. Run it again after a
change and it rebuilds, pushes new tags and updates the apps in place.

Override defaults with `RESOURCE_GROUP`, `LOCATION`, `NAME_PREFIX` or `TAG`.

## Tearing down

```bash
infra/destroy.sh
```

Deleting the resource group is the only reliable way to stop the charges. A stopped
container app still bills for its environment, and the database bills whether or not
anything connects to it.

## Cost

Roughly **$3–4 a day** left running, dominated by the Container Apps environment and the
burstable database. A demo that is deployed, recorded and torn down within a day costs a
few dollars.

## Design choices

**Public networking on the database, with a firewall rule for Azure services.** A private
endpoint is the appropriate choice for a deployment holding production data. It also needs
a virtual network, a private DNS zone and a NAT gateway, which together cost more than
everything else here combined.

**One replica of the API and the ML service.** The rate limiter keys per caller in
Postgres and behaves correctly across replicas, so this is a cost and comparability
choice: a single instance keeps the deployed latency figures comparable to the local
measurements in `docs/RESULTS.md`.

**The ML image does not include late-interaction reranking.** It brings torch and
sentence-transformers, which add gigabytes to the image for a reranker that is off by
default. Build with `EXTRAS="--extra parsing --extra late-interaction"` for an image that
serves it.
