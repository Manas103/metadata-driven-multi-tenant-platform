# Metadata-Driven Multi-Tenant Platform

[![CI](https://github.com/Manas103/metadata-driven-multi-tenant-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/Manas103/metadata-driven-multi-tenant-platform/actions/workflows/ci.yml)

A Spring Boot service (Java 21, Kotlin) where tenants define their own objects and fields as
metadata rows instead of database DDL, and query them with a small SQL-like language compiled
to parameterized PostgreSQL. The tenant predicate, and a record-level sharing filter based on a
role hierarchy, are injected into the query's syntax tree before any SQL text exists, so no
query can skip them; an independently written second translator checks the compiler's work.
Around that core: a declarative type platform (one declaration compiles to DDL, a REST surface
and generated Java and TypeScript clients), a bitemporal ledger where a correction is a new
version and past beliefs stay queryable, a React + TypeScript admin console, and a Docker /
Terraform deployment to AWS ECS Fargate with RDS PostgreSQL.

## Results

Every count below was measured on H2 in PostgreSQL mode and again on PostgreSQL 16, with
identical results; raw outputs in `docs/` and `docs/postgres/`.

| check | result |
|---|---|
| adversarial cross-tenant probes | 0 foreign rows across 500 probes |
| compiler vs independent reference translator | 2,000 / 2,000 generated queries identical, row for row |
| sharing filter (owner, hierarchy, explicit share, peer and cross-branch denial) | 0 leaks and 0 wrongful denials across 500 probes; 1,000 / 1,000 queries identical to the reference |
| generated Java vs TypeScript clients, 40 declared types | 2,000 / 2,000 queries identical |
| bitemporal ledger, fast path vs independent recomputation | 250,000 / 250,000 row verdicts and 5,000 / 5,000 position aggregates identical |
| production container on PostgreSQL (`docker compose`), over HTTP | 500 cross-tenant probes, 0 foreign rows; query p50 3.4 ms, p99 6.0 ms |

What running it on real PostgreSQL and through the API found (details in [DESIGN.md](DESIGN.md)):

- Every create through the type platform's REST surface returned HTTP 500 on PostgreSQL: the
  JDBC generated-keys call returned the whole row there, never on H2. Fixed.
- Two users holding the same role could see each other's records, contrary to the documented
  model. The independent oracle had the same rule, and the seed data has one user per role, so
  neither the diff nor the probe suite could catch it; an API test with two peers did. Fixed in
  both implementations.
- The first live ledger correction after a bulk backfill failed on a duplicate primary key: the
  backfill wrote its own ids and never advanced the identity sequence. Fixed.
- The production profile validated the schema but had no migration, so it could not start on an
  empty database. Added a Flyway migration with foreign keys and indexes.

## Architecture

```
 React + TypeScript console  --HTTP-->  Spring Boot service                                   --JDBC-->  PostgreSQL 16
 (schema, query, ledger)                /api/admin   tenants, objects, fields, records, query             Flyway schema
                                        /api/types   40 declared types, generated clients
                                        /api/bookofrecord  versions, corrections, believed-at
                                        query: parse -> AST -> + tenant scope -> + sharing scope -> SQL
```

## Running it

```bash
docker compose up --build                 # service + PostgreSQL 16 + demo data, console at http://localhost:8080
python3 scripts/remote_check.py http://localhost:8080 500   # isolation, latency, write path, ledger over HTTP
```

```bash
mvn test                                  # 43 tests on H2
mvn test -Dspring.profiles.active=pg      # the same on PostgreSQL 16 (Docker), plus the Flyway + validate startup test
mvn test -Dtest=BenchmarkRunner -Dsurefire.excludedGroups=   # also SharingBenchmarkRunner, TypePlatformBenchmarkRunner (Node 22.6+), RestatementBenchmarkRunner
cd web && npm ci && npm test && npm run dev   # console against a backend on :8080
```

AWS (Terraform, Docker, AWS CLI credentials): `scripts/aws_run.sh` creates ECR, RDS PostgreSQL
(reachable only from the service), the database password in SSM Parameter Store, and an ECS
Fargate service reachable only from the caller's IP; pushes the image, runs `remote_check.py`
against it, saves the results to `results/aws/`, then destroys everything and checks that
nothing is left.

## Layout

| path | what |
|---|---|
| `dsl/`, `compiler/`, `sharing/` | parser, AST, tenant and sharing scoping rewriters, SQL compiler |
| `reference/` | the independent second translator used as a diff oracle |
| `typesystem/`, `generated-clients/` | type DSL, DDL emitter, generic REST surface, generated Java and TypeScript clients |
| `bookofrecord/` (Java and Kotlin) | bitemporal ledger, restatement writer, projector, independent recomputation |
| `admin/`, `web/` | admin API, demo data loader, React console |
| `src/main/resources/db/migration` | Flyway schema |
| `Dockerfile`, `docker-compose.yml`, `deploy/aws`, `scripts/` | container, local stack, Terraform, AWS run, HTTP checks |
| `docs/` | raw output behind every number here and in DESIGN.md |

## Scope, stated plainly

- All data is synthetic and seeded deterministically; no real tenant or account exists.
- `generated-clients/` is emitted by the type platform's compiler, not written by hand: 4,121
  lines of Java (about 39% of the repository's Java; 6,446 lines of service and tests are
  hand-written) and 2,280 lines of TypeScript.
- The DSL has no joins between objects and no aggregates. The admin API has no authentication:
  tenant and viewer are request parameters, fine for a demo console, not for production.
- The isolation suites are evidence at the scale measured (hundreds of probes, thousands of
  generated queries), not a proof.
