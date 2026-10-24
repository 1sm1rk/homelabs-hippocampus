# homelabs-hippocampus

STM + LTM memory service for AI harnesses: short-term memory (raw session
events via MCP tools) and long-term memory (pgvector chunks + embeddings,
hybrid semantic + lexical retrieval), with a one-way consolidation path
(`promote` / `import_file`). Exposed via MCP (Streamable-HTTP) with OAuth 2.1
authentication. Single-user, self-hosted.

## Tools

| Tool | What it does |
|---|---|
| `ping` | liveness check, answers `pong: hippocampus alive` |
| `store` | store an event in short-term memory (actor, kind, content, optional meta; warns on deprecated/unregistered meta keys) |
| `update` | correct an existing event by id — writes a new event and marks the old one superseded, never overwrites |
| `find` | case-insensitive keyword search over memory, newest first; superseded events excluded unless `includeSuperseded` |
| `list` | most recent events, newest first; superseded events excluded unless `includeSuperseded` |
| `promote` | copy an STM event into long-term memory (chunked + embedded); re-promoting the same id is a no-op |
| `import_file` | import a txt/md/PDF document into long-term memory (chunked + embedded); unchanged re-import is a no-op |
| `ltm_find` | hybrid semantic + lexical search over long-term memory, returns short excerpts (never whole entries) |
| `ltm_get` | fetch one long-term entry by id — full content or a 1-based chunk slice |
| `ltm_list` | long-term memory index, newest first (id, title, origin, chunk count — no content) |

## Build and test

```bash
mvn verify                # unit tests (surefire) + `*IT` integration tests (failsafe; needs a Docker daemon via Testcontainers)
mvn package -DskipTests
java --enable-native-access=ALL-UNNAMED -jar target/homelabs-hippocampus-0.0.1-SNAPSHOT.jar
```

`mvn spring-boot:run` runs from an exploded classpath and segfaults once the
LTM embedding natives (ONNX Runtime + DJL tokenizer JNI) load — run the
packaged jar instead (JEP 472, JDK 24+; the flag alone does not fix the
segfault — only the packaged form is stable). `OrtSession.run` is not
thread-safe; fine on the single-user sequential path.

The service needs a PostgreSQL 17 + pgvector database and an OAuth 2.1
authorization server (tested against Keycloak 26.8); both are bound via Spring
config or env (see Configuration). Flyway runs the schema migrations
automatically (`src/main/resources/db/migration/`).

## Configuration

All knobs have localhost defaults in `src/main/resources/application.yml`:

| Env var | Meaning |
|---|---|
| `HIPPOCAMPUS_ISSUER_URI` | JWT issuer (default `http://localhost:8091/realms/hippocampus`) |
| `HIPPOCAMPUS_RESOURCE` | MCP resource identifier + audience binding (default `http://localhost:8090/mcp`) |
| `HIPPOCAMPUS_RESOURCE_METADATA_URL` | RFC-9728 PRM URL (default `http://localhost:8090/.well-known/oauth-protected-resource`) |
| `HIPPOCAMPUS_LTM_EMBEDDING_MODEL_PATH` | ONNX model file (default `~/.local/share/hippocampus/models/multilingual-MiniLM/model.onnx`) |
| `HIPPOCAMPUS_LTM_EMBEDDING_TOKENIZER_PATH` | Tokenizer file (same dir, `tokenizer.json`) |
| `HIPPOCAMPUS_LTM_EMBEDDING_PROVIDER` | `onnx` (default, eagerly loaded) or `mock` (deterministic, for tests) |

Tests must not depend on the real model: set
`hippocampus.ltm.embedding.provider=mock` (the full-context ITs do exactly
that) — real-model verification happens at every production start (eager
init, see below).

## Stage 1 LTM (embedding model)

Embeddings run in-process with `paraphrase-multilingual-MiniLM-L12-v2` (384d
ONNX Runtime, ADR 0005). The ~449 MB model + ~17 MB tokenizer are deployment
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

The MCP endpoint (`POST /mcp`) requires a Bearer token: hippocampus is an
OAuth 2.1 resource server (MCP authorization spec, 2026-07-28 revision).

- `GET /.well-known/oauth-protected-resource` → RFC 9728 Protected Resource
  Metadata (`resource`, `authorization_servers`, `scopes_supported`)
- Unauthenticated `POST /mcp` → `401` +
  `WWW-Authenticate: Bearer resource_metadata="…"` — the healthy state
- Token validation: issuer + audience binding via RFC 8707 (`aud` must contain
  `HIPPOCAMPUS_RESOURCE`, written through Keycloak's audience protocol mapper)
- Every `store` writes the token `sub` as the event's `owner` (write side
  only); read-side scoping is parked until a second user exists (ADR 0004)

A ready-made docker compose orchestration (Postgres + Keycloak, realm export,
model install helper, LAN/SSH-tunnel recipes) ships with the operator's
deployment — deliberately kept out of this repository.

## Docker image

`docker build -t homelabs-hippocampus .` packages the same runtime as the jar
command above: multi-stage Maven build with no `spring-boot:run` step (the
exploded-classpath segfault does not exist inside the image), non-root
(uid 1000) on `eclipse-temurin:25-jre-noble` — glibc, since the ONNX/DJL
natives are glibc builds (no alpine/musl variant).

## Conventions

- English for code and docs; Java 25 records; constructor injection
- Schema changes only via Flyway migrations (`src/main/resources/db/migration/`)
- No vector column until Stage 1 (ADR 0003)
- `actor` is an open vocabulary, not a closed enum (ADR 0002): `user` (content
  from the user), `ai` (the agent's own findings), `tool` (tool output) — later
  a person's name or email. Documented in prose, not enforced by the schema.
- Meta keys are governed by `meta_key_registry` (V005): established keys
  (topic, project, relates_to, supersedes, typ, week, stage, origin) are
  validated; deprecated keys warn; unknown keys are accepted and surfaced as
  curation candidates. Registry contents change only via Flyway migrations.
- OAuth 2.1 resource server (AP-7): `POST /mcp` requires a Bearer JWT; the
  owner column carries the token `sub` on the write side only. Read-side
  scoping is parked until the ADR-0004 trigger (second user / beyond-LAN).

## License

Apache License 2.0 — see [LICENSE](LICENSE).