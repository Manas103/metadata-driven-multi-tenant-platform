# Metadata-Driven Multi-Tenant Object Platform

A Spring Boot service where tenants define custom objects and fields as metadata rows instead of database DDL, with a hand-written SOQL-style DSL compiled to parameterized SQL by a real recursive-descent parser and a code generator that injects the tenant predicate directly into the WHERE clause's abstract syntax tree, structurally, before any SQL text is produced. Java 21, Spring Boot 3.3.4, Spring Data JPA, PostgreSQL (H2 in PostgreSQL-compatibility mode for tests). Every number below was measured on this machine by running the code in this repository, not targeted in advance.

## Why this exists

Metadata-driven multi-tenancy over a relational substrate, where adding a "column" is a metadata row rather than an `ALTER TABLE`, and every tenant's data lives behind a query layer that cannot forget to scope by tenant, is the architecture underneath large multi-tenant SaaS platforms. This repository is a small, honest version of that idea: real tenants, real custom objects and fields declared as data, a real parser and compiler for the query language, and a real second implementation used only to check the first one's work.

## Honest framing, up front

- **This is a SOQL-style DSL, not SOQL.** The grammar (`SELECT fields FROM Object WHERE predicate`, AND/OR, six comparison operators, parenthesized grouping) is deliberately SQL/SOQL-shaped for familiarity, not a compatibility target for any real query language.
- **This is a tenancy mechanism, not a database engine.** There is no query planner, no indexing strategy beyond what Postgres/H2 already do on the underlying tables, and no transaction isolation work beyond what JPA gives for free.
- **The data is entirely synthetic.** `DataSeeder` builds a deterministic, seeded dataset of 6 fictional tenants (Acme, Globex, Initech, Umbrella, Stark, Wayne), each declaring the same three shared object names (`Contact`, `Deal`, `Task`, deliberately colliding across tenants at the metadata level) plus one object exclusive to itself, populated with synthetic field values at an 85% presence rate so the EAV storage's sparse rows and `LEFT JOIN`/`NULL` semantics are actually exercised, not just the happy path. There is no real customer or tenant behind any of it.
- **Tests and the measurement runs use H2 in PostgreSQL-compatibility mode** (`MODE=PostgreSQL`), not real PostgreSQL: `src/main/resources/application.yml` is the real production profile (real PostgreSQL, `ddl-auto: validate`); `src/test/resources/application.yml` is the only place H2 is configured, and no real PostgreSQL instance was reachable in the environment this project was built in.
- **Machine and toolchain.** 8 physical / 16 logical cores, Windows 11 Home. JDK 21 (Temurin), Maven 3.9.9, Spring Boot 3.3.4, Spring Data JPA, H2 2.x, PostgreSQL JDBC driver (runtime only, unused by any test in this repository).

## Architecture

```
src/main/java/com/manas/metaplatform/
  MetaPlatformApplication.java          Spring Boot entry point
  domain/
    Tenant.java                         a tenant: nothing more than a name
    ObjectDefinition.java               a tenant-scoped custom object name (metadata row, not a table)
    FieldDefinition.java                a name + FieldType on an ObjectDefinition (metadata row, not a column)
    FieldType.java                      STRING | NUMBER | BOOLEAN
    RecordEntity.java                   one generic record: tenant id + object definition id, no other columns
    FieldValueEntity.java               one sparse EAV row: (record, field) -> typed value
  catalog/
    SchemaCatalog.java                  tenant-scoped object/field name resolution, shared by the compiler and the reference interpreter
    UnknownObjectException.java / UnknownFieldException.java
  dsl/
    lexer/Lexer.java, Token.java, TokenType.java     hand-rolled character-by-character tokenizer
    Parser.java                         recursive-descent parser producing a real AST
    ast/Expr.java, Comparison.java, AndExpr.java, OrExpr.java, Literal.java, Operator.java, TenantScopeNode.java, SelectQuery.java
    ParsedQuery.java / DslSyntaxException.java
  compiler/
    TenantScopingRewriter.java          the one and only place a TenantScopeNode is created and ANDed at the WHERE root
    AstInvariants.java                  structural proof that a TenantScopeNode is reachable from the root through AND-only descent
    ScopedQuery.java                    package-private constructor: only the rewriter can produce one
    SqlCompiler.java                    lowers a ScopedQuery to parameterized SQL against the EAV schema
    QueryExecutor.java                  chains parse -> scope -> compile -> execute
    CompiledSql.java / DslCompileException.java
  reference/
    ReferenceInterpreter.java           an independently written second translator, used only as a diff oracle
  generator/
    DataSeeder.java                     builds the seeded 6-tenant synthetic dataset
    QueryGenerator.java                 builds syntactically valid random DSL queries against the seed
    AdversarialQueryGenerator.java      builds cross-tenant-literal and injection-style adversarial probes
    SeedDataset.java / GeneratedQuery.java / AdversarialProbe.java
  repository/                           five plain Spring Data JPA repositories
src/main/resources/application.yml      production profile: real PostgreSQL
src/test/java/com/manas/metaplatform/
  dsl/ParserTest.java                   lexer/parser grammar coverage and syntax-error cases
  compiler/AstInvariantsTest.java       structural AST assertions, no database involved
  integration/PlatformIntegrationTest.java   Spring context + H2, fast always-on wiring checks
  bench/BenchmarkRunner.java            the 2,000-query and 500-probe measurement run (tagged, opt-in)
src/test/resources/application.yml      test profile: H2 in PostgreSQL-compatibility mode
```

**Why the tenant predicate is injected into the AST, not appended to a SQL string.** `TenantScopingRewriter.scope` takes a tenant-less `ParsedQuery` and returns a `ScopedQuery` whose WHERE tree has a `TenantScopeNode` ANDed in at the root, as a real node in the same `Expr` hierarchy as the user's own predicates, before `SqlCompiler` ever runs. A naive implementation that rendered the user's WHERE clause to a SQL string first and then concatenated `"tenant_id = ? AND " + userSql` would get the tenant scoping wrong the moment the user's own top-level operator was OR (`tenant_id = ? AND a = 1 OR b = 2` parses as `(tenant_id = ? AND a = 1) OR b = 2`, which does not restrict the OR branch at all). Because `SqlCompiler.emit` always renders whichever AST node it is given, and every AND/OR node is emitted as an explicitly parenthesized `(left OP right)`, the tenant predicate binds the same way regardless of how deep or how OR-heavy the user's clause is. This is checkable independently of any SQL text: `AstInvariants.assertTenantScopedAtRoot` walks the tree and asserts a `TenantScopeNode` is reachable from the root through AND-only descent, never buried under an OR.

**Why `ScopedQuery`'s constructor is package-private.** `SqlCompiler.compile` accepts a `ScopedQuery`, never a bare `ParsedQuery`, and the only code in the entire repository that can construct one is `TenantScopingRewriter.scope`. There is structurally no path from DSL text to a `PreparedStatement` that skips the rewriter; it does not compile otherwise. `ScopedQuery`'s constructor also re-checks `AstInvariants.assertTenantScopedAtRoot` itself, as defense in depth, so a future bug in the rewriter would fail loudly at construction time.

**Why EAV storage rather than per-tenant tables or JSONB.** `RecordEntity` (tenant id, object definition id) plus sparse `FieldValueEntity` rows (record id, field definition id, one of three typed columns) means declaring a new object or field is one metadata row, never DDL, and it works identically for a tenant's first record and its millionth. The cost, paid deliberately: every referenced field costs one `LEFT JOIN` in the compiled SQL (see `SqlCompiler.compile`), so a query touching many fields produces a wide join; that tradeoff is accepted here in exchange for zero-DDL schema evolution, which is the property this project exists to demonstrate.

**Why the reference interpreter is a genuinely independent second implementation.** `ReferenceInterpreter` shares nothing with `dsl.Lexer`, `dsl.Parser`, `compiler.SqlCompiler`, or the `dsl.ast` class hierarchy: it tokenizes with one regex pass instead of a hand-rolled scanner, it builds a `Predicate<Map<String,Object>>` by functional composition instead of an AST class hierarchy, and it evaluates by pulling every (record, field) row for the tenant and object into memory and filtering in Java, instead of pushing the WHERE clause down into SQL the way the main compiler does. It enforces its own tenant scoping independently, in its own query. The two implementations only share `SchemaCatalog` (metadata lookups, not query logic) and the database schema itself, which is why a diff between them is evidence about the compiler's correctness rather than a comparison of two copies of the same logic.

## Validation

Default test run (parser, AST invariants, integration wiring), 21 tests, all passing (`docs/test_output.txt`):

```
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- AstInvariantsTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- ParserTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- PlatformIntegrationTest
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`ParserTest` covers select-list parsing, all six WHERE shapes (none, single, AND, OR, parenthesized), all six numeric operators, escaped-quote string literals, and four distinct syntax-error cases (missing `FROM`, trailing garbage, unterminated string, unbalanced parenthesis).

`AstInvariantsTest` is a pure structural test with no database: it builds `Expr` trees by hand and asserts `AstInvariants.assertTenantScopedAtRoot` accepts a bare tenant node, a tenant node ANDed with a user clause, and a tenant node three ANDs deep, while rejecting a missing tenant node, a tenant node buried under an OR (the exact shape a string-concatenation bug could produce), a wrong tenant id, and duplicate tenant nodes.

`PlatformIntegrationTest` seeds the real dataset through the real Spring context against H2, then checks that the compiler and the reference interpreter agree on a live query, that two tenants' identically-named `Contact` objects never share a row, that querying another tenant's exclusive object name is rejected with `UnknownObjectException`, and that the compiled SQL text never contains the tenant id as a standalone numeric token (a word-boundary check, since column aliases like `fv1` can coincidentally contain the same digits).

`BenchmarkRunner` (tagged `benchmark`, excluded from the default run) is the scale measurement behind the two resume claims; see "Measured results" below for its output.

## Findings

The first version of the tenant-scoped "compiled SQL never contains the tenant id" test used a bare substring check (`doesNotContain(String.valueOf(tenantId))`) and failed on the very first run, not because of a tenant-scoping bug but because the compiler's own generated column alias for the first joined field, `fv1`, coincidentally contains the digit `1`, which was also the seeded tenant id under test. The measurement that discriminated a real leak from a coincidental digit collision was switching to a word-boundary regex (`(?<![A-Za-z0-9_])1(?![0-9])`) that only matches a standalone numeric token, plus a positive check that `r.tenant_id = ?` (the placeholder form) is present in the SQL text. This is exactly the kind of thing a real measurement is supposed to catch: the first assertion was too strong in the wrong way, and weakening it precisely (rather than deleting it) is what makes the surviving assertion still mean something.

## Measured results

Machine: 8 physical / 16 logical cores, Windows 11 Home, JDK 21 Temurin, Maven 3.9.9, Spring Boot 3.3.4, H2 2.x in PostgreSQL-compatibility mode. Run: `mvn test -Dtest=BenchmarkRunner -Dsurefire.excludedGroups=` (raw output in `docs/benchmark_output.txt`).

Dataset: 6 synthetic tenants, 3 shared object names per tenant (150 records each) plus 1 exclusive object per tenant (20 records), 85% field-presence rate, seed `1001`.

| Metric | Measured | Claim | Meets claim |
|---|---|---|---|
| **Adversarial cross-tenant isolation** | **0 foreign rows across 500 probes** (250 cross-tenant literal-collision probes, 250 parameterization-breaking literal probes) | 500 adversarial cross-tenant queries returned 0 foreign rows | yes |
| **Reference-translator diff** | **2,000 / 2,000 generated queries matched the independent reference interpreter exactly**, row for row, field for field | 2,000 generated queries matched an independent reference translator exactly | yes |

Both figures are counted, not sampled: every one of the 500 probes and 2,000 generated queries in the run that produced `docs/benchmark_output.txt` is included in the totals above. The adversarial suite's "foreign row" check looks up each returned row's true `tenant_id` directly against the `records` table, independent of whatever the query claimed to be scoped to, so a leak would show up as a nonzero count rather than needing to be spotted by eye. The reference-translator diff compares full result rows (record id, every selected field's value) between the main compiler's execution path and `ReferenceInterpreter`'s independently-implemented execution path, not just row counts or SQL text.

What this does not measure: query performance or planner behavior (out of scope, see "Honest framing"), concurrent multi-tenant load (the measurement run is single-threaded), and correctness against a real PostgreSQL instance rather than H2 in compatibility mode (see "Limitations").

## Building and running

```
export PATH="/c/Users/Manas/tools/apache-maven-3.9.9/bin:$PATH"   # or the equivalent mvn on PATH
mvn test                                              # 21 tests, H2 in-memory, ~5s
mvn test -Dtest=BenchmarkRunner -Dsurefire.excludedGroups=   # the 2,000-query / 500-probe measurement run, ~4s
```

Against a real PostgreSQL instance (not exercised for any number in this README):

```
createdb metaplatform
export METAPLATFORM_DB_PASSWORD=...
mvn spring-boot:run
```

## Sibling comparison

This is the first repository in the portfolio with a tenancy model or a query compiler. [`securities-settlement-matching`](https://github.com/Manas103/securities-settlement-matching) and `card-authorization-gateway` are the nearest existing Java/Spring Boot services by stack (Spring Boot, Spring Data JPA, H2-in-PostgreSQL-compatibility-mode testing), but both are single-tenant domain services: every row in those services already belongs to the one system running them, so there is no tenant predicate to inject and no adversarial cross-tenant suite to run. Their "diffed against a reference" pattern (a linear-scan reference oracle, in `securities-settlement-matching`'s case) is the same validation idea this project reuses for `ReferenceInterpreter`, applied here to query translation instead of settlement matching.

## Limitations

No real PostgreSQL instance was exercised for any number in this README; every measured figure comes from the H2-in-PostgreSQL-compatibility-mode test path. The DSL supports a single flat table's worth of fields per object (no relationships or joins between custom objects), six comparison operators, and no aggregate functions (`COUNT`, `SUM`, etc.), all deliberately out of scope for a small version of the idea. Concurrency correctness under simultaneous writers is whatever Spring Data JPA and the underlying database already provide; nothing in this project adds tenant-aware locking or isolation beyond that. The adversarial suite covers cross-tenant literal collisions and parameterization-breaking string literals; it does not include a fuzzer or a formal proof, so it is evidence of correctness at the scale measured (500 probes), not a guarantee for every possible adversarial input.
