# homelabs-hippocampus

STM + LTM memory service for AI harnesses: short-term memory (raw session
events) and long-term memory (pgvector chunks + embeddings, hybrid semantic +
lexical retrieval), with a one-way consolidation path (`promote` /
`import_file`). The service is a plain REST API with OAuth 2.1 (JWT)
authentication; its MCP tool definitions ship separately as an adapter module
that an MCP gateway composes. Single-user, self-hosted.

## Modules

| Module | Artifact | What it is |
|---|---|---|
| `app` | `de.homelabs:homelabs-hippocampus` | the runnable memory service: domain, persistence, REST API |
| `hippocampus-mcp` | `de.homelabs:hippocampus-mcp` | MCP tool definitions plus the typed client that binds them to the REST API — composed by an MCP gateway, not part of the service image |

The two modules share no code: the adapter compiles against the HTTP + JSON
contract, never against the service's classes. The service stays a classic API
server; the MCP surface is one adapter among the ones a gateway can compose.

## Memory operations

Each operation is one REST endpoint; the adapter exposes the same operations as
MCP tools with identical semantics.

| Operation | Endpoint | What it does |
|---|---|---|
| `ping` | `GET /api/ping` | liveness check, answers `pong: hippocampus alive` |
| `store` | `POST /api/stm/events` | store an event in short-term memory (actor, kind, content, optional meta; warns on deprecated/unregistered meta keys) |
| `update` | `PUT /api/stm/events/{id}` | correct an existing event by id — writes a new event and marks the old one superseded, never overwrites |
| `find` | `GET /api/stm/events?query=…` | case-insensitive keyword search over memory, newest first; superseded events excluded unless `includeSuperseded` |
| `list` | `GET /api/stm/events/recent` | most recent events, newest first; superseded events excluded unless `includeSuperseded` |
| `promote` | `POST /api/ltm/promote` | copy an STM event into long-term memory (chunked + embedded); re-promoting the same id is a no-op |
| `import_file` | `POST /api/ltm/imports` | import a txt/md/PDF document into long-term memory (chunked + embedded); unchanged re-import is a no-op |
| `ltm_find` | `GET /api/ltm/search?query=…` | hybrid semantic + lexical search over long-term memory, returns short excerpts (never whole entries) |
| `ltm_get` | `GET /api/ltm/entries/{id}` | one long-term entry — full content or a 1-based chunk slice (`fromSeq`/`toSeq`) |
| `ltm_list` | `GET /api/ltm/entries` | long-term memory index, newest first (id, title, origin, chunk count — no content) |

`GET /health` is the open liveness probe for container healthchecks (no memory
data, no credentials). Domain errors answer `400` with `{"error":"<message>"}`;
a foreign id answers exactly like an absent one.

## Build and test

```bash
mvn verify                # both modules: unit tests (surefire) + `*IT` integration tests (failsafe; needs a Docker daemon via Testcontainers)
mvn package -DskipTests
java --enable-native-access=ALL-UNNAMED -jar app/target/homelabs-hippocampus-0.0.1-SNAPSHOT.jar
```

`mvn spring-boot:run` runs from an exploded classpath and segfaults once the
LTM embedding natives (ONNX Runtime + DJL tokenizer JNI) load — run the
packaged jar instead (JEP 472, JDK 24+; the flag alone does not fix the
segfault — only the packaged form is stable). `OrtSession.run` is not
thread-safe; fine on the single-user sequential path.

The service needs a PostgreSQL 17 + pgvector database and an OAuth 2.0
authorization server (tested against Keycloak 26.8); both are bound via Spring
config or env (see Configuration). Flyway runs the schema migrations
automatically (`app/src/main/resources/db/migration/`).

## Configuration

All knobs have localhost defaults in `app/src/main/resources/application.yml`:

| Env var | Meaning |
|---|---|
| `HIPPOCAMPUS_ISSUER_URI` | JWT issuer (default `http://localhost:8091/realms/hippocampus`) |
| `HIPPOCAMPUS_RESOURCE` | expected token audience — the MCP gateway's resource URI (default `http://localhost:8090/mcp`) |
| `HIPPOCAMPUS_LTM_EMBEDDING_MODEL_PATH` | ONNX model file (default `~/.local/share/hippocampus/models/multilingual-MiniLM/model.onnx`) |
| `HIPPOCAMPUS_LTM_EMBEDDING_TOKENIZER_PATH` | Tokenizer file (same dir, `tokenizer.json`) |
| `HIPPOCAMPUS_LTM_EMBEDDING_PROVIDER` | `onnx` (default, eagerly loaded) or `mock` (deterministic, for tests) |

The MCP adapter reads one property of its own:
`hippocampus.mcp.api.base-url` (default `http://localhost:8092`, the service's
internal address).

Tests must not depend on the real model: set
`hippocampus.ltm.embedding.provider=mock` (the full-context ITs do exactly
that) — real-model verification happens at every production start (eager
init, see below).

## Stage 1 LTM (embedding model)

Embeddings run in-process with `paraphrase-multilingual-MiniLM-L12-v2` (384d
ONNX Runtime). The ~449 MB model + ~17 MB tokenizer are deployment
data and are never committed — fetch both files into
`~/.local/share/hippocampus/models/multilingual-MiniLM/` (or point
`HIPPOCAMPUS_LTM_EMBEDDING_*` at your own location):

- <https://huggingface.co/onnx-models/paraphrase-multilingual-MiniLM-L12-v2-onnx/resolve/main/model.onnx>
- <https://huggingface.co/onnx-models/paraphrase-multilingual-MiniLM-L12-v2-onnx/resolve/main/tokenizer.json>

Model and tokenizer load **eagerly at startup**; missing or broken artifacts
abort startup ("APPLICATION FAILED TO START") with the install hint — the
service never comes up with a silently broken LTM. A wrong-dimension model
file fails at boot too, not at the first chunk insert.

## Authentication

Every `/api/**` endpoint requires a Bearer token: the service is an OAuth 2.0
resource server whose caller is the MCP gateway (a configured server, not a
browser client — no discovery document, no Protected Resource Metadata).

- Token validation: issuer (`iss`) plus audience — `aud` must contain
  `HIPPOCAMPUS_RESOURCE`, the gateway's resource URI (RFC 8707 resource-location
  binding, written through Keycloak's audience protocol mapper). The audience is
  never user authorization.
- Ownership: every `store` writes the token `sub` as the event's `owner`, and
  every read path is scoped to the token `sub` — rows that predate
  authentication belong to no token until they are backfilled.
- `source` (the client identity) comes only from the internal `X-Mcp-Source`
  header that the adapter sets from the MCP handshake — never from a request
  body.

A ready-made docker compose orchestration (Postgres + Keycloak, realm export,
model install helper, LAN/SSH-tunnel recipes) ships with the operator's
deployment — deliberately kept out of this repository.

## Docker image

`docker build -f homelabs-hippocampus/Dockerfile -t homelabs-hippocampus <projects-dir>`
packages the same runtime as the jar command above. The build context is the
PARENT `projects/` directory (the layout the deployment compose files use); the
image is built from this repo's `app` module alone — no sibling checkout is
needed. The multi-stage Maven build has no `spring-boot:run` step (the
exploded-classpath segfault does not exist inside the image), runs as non-root
(uid 1000) on `eclipse-temurin:25-jre-noble` — glibc, since the ONNX/DJL
natives are glibc builds (no alpine/musl variant) — and expects the embedding
model mounted at `/opt/hippocampus/models/multilingual-MiniLM`.

## Conventions

- English for code and docs; Java 25 records; constructor injection
- Schema changes only via Flyway migrations (`app/src/main/resources/db/migration/`)
- `actor` is an open vocabulary, not a closed enum: `user` (content
  from the user), `ai` (the agent's own findings), `tool` (tool output) — later
  a person's name or email. Documented in prose, not enforced by the schema.
- Meta keys are governed by `meta_key_registry` (V005): established keys
  (topic, project, relates_to, supersedes, typ, week, stage, origin) are
  validated; deprecated keys warn; unknown keys are accepted and surfaced as
  curation candidates. Registry contents change only via Flyway migrations.
- Ownership is strictly scoped to the token `sub` on every read and write; rows
  that predate authentication become visible only once backfilled.

## Commit messages

Commit messages are short and describe the change itself. They deliberately
avoid references to internal planning artifacts (ADRs, work packages): those
documents are not part of this public repository.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
