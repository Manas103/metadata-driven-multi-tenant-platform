# Metadata-Driven Multi-Tenant Object Platform, Declarative Type Platform, and Bitemporal Book of Record

A Spring Boot service where tenants define custom objects and fields as metadata rows instead of database DDL, with a hand-written SOQL-style DSL compiled to parameterized SQL by a real recursive-descent parser and a code generator that injects the tenant predicate, and a record-level sharing filter, directly into the WHERE clause's abstract syntax tree, structurally, before any SQL text is produced. Extended with a declarative type platform (an entity declared once compiles to PostgreSQL DDL, a generic REST surface, and typed Java/TypeScript client SDKs) and, most recently, with a bitemporal investment book of record: a correction to a fund account's position or realized P&L writes a new as-of version instead of mutating history, verified against an independent recomputation at 250,000-row scale. Java 21, Kotlin 2.0, Spring Boot 3.3.4, Spring Data JPA, PostgreSQL (H2 in PostgreSQL-compatibility mode for tests), TypeScript/React for the generated client and the restatement console. Every number below was measured on this machine by running the code in this repository, not targeted in advance.

## Why this exists

Metadata-driven multi-tenancy over a relational substrate, where adding a "column" is a metadata row rather than an `ALTER TABLE`, and every tenant's data lives behind a query layer that cannot forget to scope by tenant, is the architecture underneath large multi-tenant SaaS platforms. This repository is a small, honest version of that idea: real tenants, real custom objects and fields declared as data, a real parser and compiler for the query language, and a real second implementation used only to check the first one's work. It was later extended with a second, independent scoping layer: within one tenant, an individual record is visible only to the users a sharing model actually grants it to, not to everyone in the tenant, which is the record-level half of the same isolation idea. Most recently it was extended again with a bitemporal book of record on top of that same multi-tenant substrate: a post-trade system of record where a back-dated correction is never allowed to silently rewrite what the system previously believed, because a reader who asked "what did we believe as of last Tuesday" has to get last Tuesday's answer, forever.

## Honest framing, up front

- **This is a SOQL-style DSL, not SOQL.** The grammar (`SELECT fields FROM Object WHERE predicate`, AND/OR, six comparison operators, parenthesized grouping) is deliberately SQL/SOQL-shaped for familiarity, not a compatibility target for any real query language.
- **This is a tenancy mechanism, not a database engine.** There is no query planner, no indexing strategy beyond what Postgres/H2 already do on the underlying tables, and no transaction isolation work beyond what JPA gives for free.
- **The sharing model is a role hierarchy plus explicit grants, not the full Force.com sharing model.** No sharing rules keyed on criteria, no public groups, no manual-share reason codes; ownership, "a manager sees a subordinate's records" through a role hierarchy, and an explicit per-record grant table are the three visibility paths, chosen because together they already force the AST-injection design to answer the same question the tenant predicate answers (where does the predicate go, and can it be skipped), not because they are the whole model.
- **The data is entirely synthetic.** `DataSeeder` builds a deterministic, seeded dataset of 6 fictional tenants (Acme, Globex, Initech, Umbrella, Stark, Wayne), each declaring the same three shared object names (`Contact`, `Deal`, `Task`, deliberately colliding across tenants at the metadata level) plus one object exclusive to itself, populated with synthetic field values at an 85% presence rate so the EAV storage's sparse rows and `LEFT JOIN`/`NULL` semantics are actually exercised, not just the happy path. `SharingDataSeeder` builds a second, entirely separate synthetic dataset for the sharing filter: one fictional company ("SharingCo") with a four-level, eleven-role hierarchy (CEO over a VP of Sales and a VP of Support, each over one manager, each over five reps), 200 `Opportunity` records owned one-per-rep-tier, and 40 explicit `record_shares` grants crossing the Sales/Support branch boundary. Neither seeder touches the other's tenants, objects, or `Random` sequence. There is no real customer, tenant, or company behind any of it.
- **Tests and the measurement runs use H2 in PostgreSQL-compatibility mode** (`MODE=PostgreSQL`), not real PostgreSQL: `src/main/resources/application.yml` is the real production profile (real PostgreSQL, `ddl-auto: validate`); `src/test/resources/application.yml` is the only place H2 is configured, and no real PostgreSQL instance was reachable in the environment this project was built in.
- **Machine and toolchain.** 8 physical / 16 logical cores, Windows 11 Home. JDK 21 (Temurin), Maven 3.9.9, Spring Boot 3.3.4, Spring Data JPA, H2 2.x, PostgreSQL JDBC driver (runtime only, unused by any test in this repository).
- **The type platform is a separate subsystem from the SOQL-style DSL above, sharing only `domain.FieldType`.** The SOQL DSL queries data already stored in the EAV schema; the type platform declares new entity types from scratch, each backed by its own real table. Neither subsystem's tenant/sharing model applies to the other; the type platform has no tenant concept at all, on purpose, to keep the two subsystems' scopes honest and non-overlapping.
- **The 40 declared types are a synthetic operations/business-object catalog** (Asset, Sensor, WorkOrder, Invoice, Employee, and so on; the full list is `src/main/resources/typesystem/declared-types.dsl`), not connected to the SOQL DSL's tenants.
- **The generated TypeScript client is run with Node's experimental type-stripping** (`node --experimental-strip-types`), not compiled by `tsc` first; this is a deliberate choice to keep the differential benchmark to one toolchain per side (`javac`/`java` for Java, `node` for TypeScript) rather than adding a separate compile step whose failure mode would be invisible to `mvn test`.
- **The book of record is a third, separate subsystem, sharing only `Tenant`.** It does not re-run the EAV DSL's or the sharing filter's own adversarial suites; it inherits their isolation claims (0 foreign rows across 500 probes, 2,000/2,000 and 1,000/1,000 reference-translator diffs) unchanged, re-verified by running the existing `BenchmarkRunner` and `SharingBenchmarkRunner` after this extension, and adds a mechanism those subsystems don't need: transaction-time (as-of) versioning on top of business-time (effective date), not record-level visibility.
- **500 accounts and 250,000 ledger rows are a bulk backfill, not 250,000 individual API calls.** `BookOfRecordDataSeeder` constructs the full bitemporal end state (which versions are open, which are closed and when) and loads it with one batched JDBC insert per 1,000 rows, deliberately bypassing JPA's per-row `IDENTITY` round trip at that volume. The live single-correction path (close one version, insert the next) is `RestatementWriter`, exercised at small scale by `RestatementWriterTest`, not by the seeder.
- **Fund accounts, symbols, trades and corporate actions are entirely synthetic.** Ten real ticker symbols are used as labels only; there is no real price data, no real account, and no real P&L behind any number below.
- **The React console reads a fixture file, not a live backend.** `ConsoleFixtureExporterTest` books a few real corrected facts through the real `RestatementWriter` and the real `RestatementDiff.of`, Jackson-serializes the result, and writes it to `web/src/fixtures/restatements.json` as part of the default `mvn test` run; the console only renders whatever that file contains, so the demo can never show a diff the backend didn't actually compute, but it is also not wired to a running HTTP server for this build.

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
    Scoped.java                         the interface both ScopedQuery and sharing.SharingScopedQuery implement, so SqlCompiler lowers either with the same code
    ScopedQuery.java                    package-private constructor: only the rewriter can produce one
    SqlCompiler.java                    lowers a Scoped query (tenant-only, or tenant-plus-sharing) to parameterized SQL against the EAV schema
    QueryExecutor.java                  chains parse -> scope -> compile -> execute, and parse -> scope -> sharing-scope -> compile -> execute
    CompiledSql.java / DslCompileException.java
  sharing/
    RoleHierarchyResolver.java          computes, for a viewer, every owner user id visible through ownership or the role hierarchy
    SharingScopingRewriter.java         the one and only place a SharingScopeNode is created and ANDed alongside the tenant predicate
    SharingScopedQuery.java             package-private constructor: only the rewriter can produce one; implements Scoped
    SharingAstInvariants.java           structural proof that a SharingScopeNode is reachable from the root through AND-only descent
  dsl/ast/SharingScopeNode.java         the record-level sharing predicate AST node (see dsl/ tree entry below)
  reference/
    ReferenceInterpreter.java           an independently written second translator, used only as a diff oracle; executeWithSharing computes the same OR-of-ownership/hierarchy/explicit-share visibility from the opposite direction
  generator/
    DataSeeder.java                     builds the seeded 6-tenant synthetic dataset
    QueryGenerator.java                 builds syntactically valid random DSL queries against the seed
    AdversarialQueryGenerator.java      builds cross-tenant-literal and injection-style adversarial probes
    SeedDataset.java / GeneratedQuery.java / AdversarialProbe.java
    SharingDataSeeder.java              builds the seeded role-hierarchy, ownership and explicit-share dataset
    SharingProbeGenerator.java          builds owner/ancestor/explicit-share/sibling-denied/cross-branch-denied adversarial probes
    SharingQueryGenerator.java          builds syntactically valid random sharing-scoped DSL queries against the seed
    SharingSeedDataset.java / SharingProbe.java / GeneratedSharingQuery.java
  repository/                           eight plain Spring Data JPA repositories (five original, plus AppUser, Role, RecordShare)
src/main/resources/application.yml      production profile: real PostgreSQL
src/test/java/com/manas/metaplatform/
  dsl/ParserTest.java                   lexer/parser grammar coverage and syntax-error cases
  compiler/AstInvariantsTest.java       structural AST assertions, no database involved
  sharing/SharingAstInvariantsTest.java structural sharing-AST assertions, no database involved
  integration/PlatformIntegrationTest.java   Spring context + H2, fast always-on tenant-scoping wiring checks
  integration/SharingIntegrationTest.java    Spring context + H2, fast always-on sharing-filter wiring checks
  bench/BenchmarkRunner.java            the 2,000-query and 500-probe tenant-isolation measurement run (tagged, opt-in)
  bench/SharingBenchmarkRunner.java     the 1,000-query and 500-probe sharing-filter measurement run (tagged, opt-in)
src/test/resources/application.yml      test profile: H2 in PostgreSQL-compatibility mode
```

Note on `dsl/ast/Expr.java`: the `TenantScopeNode` entry above the tree listing has a sibling, `SharingScopeNode` (same package, `dsl/ast/`), carrying the sharing predicate exactly the way `TenantScopeNode` carries the tenant predicate.

**Why the tenant predicate is injected into the AST, not appended to a SQL string.** `TenantScopingRewriter.scope` takes a tenant-less `ParsedQuery` and returns a `ScopedQuery` whose WHERE tree has a `TenantScopeNode` ANDed in at the root, as a real node in the same `Expr` hierarchy as the user's own predicates, before `SqlCompiler` ever runs. A naive implementation that rendered the user's WHERE clause to a SQL string first and then concatenated `"tenant_id = ? AND " + userSql` would get the tenant scoping wrong the moment the user's own top-level operator was OR (`tenant_id = ? AND a = 1 OR b = 2` parses as `(tenant_id = ? AND a = 1) OR b = 2`, which does not restrict the OR branch at all). Because `SqlCompiler.emit` always renders whichever AST node it is given, and every AND/OR node is emitted as an explicitly parenthesized `(left OP right)`, the tenant predicate binds the same way regardless of how deep or how OR-heavy the user's clause is. This is checkable independently of any SQL text: `AstInvariants.assertTenantScopedAtRoot` walks the tree and asserts a `TenantScopeNode` is reachable from the root through AND-only descent, never buried under an OR.

**Why `ScopedQuery`'s constructor is package-private.** `SqlCompiler.compile` accepts a `ScopedQuery`, never a bare `ParsedQuery`, and the only code in the entire repository that can construct one is `TenantScopingRewriter.scope`. There is structurally no path from DSL text to a `PreparedStatement` that skips the rewriter; it does not compile otherwise. `ScopedQuery`'s constructor also re-checks `AstInvariants.assertTenantScopedAtRoot` itself, as defense in depth, so a future bug in the rewriter would fail loudly at construction time.

**Why EAV storage rather than per-tenant tables or JSONB.** `RecordEntity` (tenant id, object definition id) plus sparse `FieldValueEntity` rows (record id, field definition id, one of three typed columns) means declaring a new object or field is one metadata row, never DDL, and it works identically for a tenant's first record and its millionth. The cost, paid deliberately: every referenced field costs one `LEFT JOIN` in the compiled SQL (see `SqlCompiler.compile`), so a query touching many fields produces a wide join; that tradeoff is accepted here in exchange for zero-DDL schema evolution, which is the property this project exists to demonstrate.

**Why the reference interpreter is a genuinely independent second implementation.** `ReferenceInterpreter` shares nothing with `dsl.Lexer`, `dsl.Parser`, `compiler.SqlCompiler`, or the `dsl.ast` class hierarchy: it tokenizes with one regex pass instead of a hand-rolled scanner, it builds a `Predicate<Map<String,Object>>` by functional composition instead of an AST class hierarchy, and it evaluates by pulling every (record, field) row for the tenant and object into memory and filtering in Java, instead of pushing the WHERE clause down into SQL the way the main compiler does. It enforces its own tenant scoping independently, in its own query. The two implementations only share `SchemaCatalog` (metadata lookups, not query logic) and the database schema itself, which is why a diff between them is evidence about the compiler's correctness rather than a comparison of two copies of the same logic.

**Why the sharing filter is a second AST node, not a second compiler.** `SharingScopeNode` is injected by `SharingScopingRewriter.scope`, the record-level mirror of `TenantScopingRewriter.scope`: it takes an already tenant-scoped `ScopedQuery` and returns a `SharingScopedQuery` (implements the same `Scoped` interface `ScopedQuery` does) whose WHERE tree has the sharing predicate ANDed in at the root, as a real node, before `SqlCompiler` ever runs. `SqlCompiler.emit` dispatches on AST node type, so `SharingScopeNode` is lowered by the exact same code generator that already lowers `TenantScopeNode`, `Comparison`, `AndExpr` and `OrExpr`, not a second parallel compiler that could disagree with the first about parenthesization or operator precedence. `SharingScopedQuery`'s constructor is package-private, exactly like `ScopedQuery`'s, and re-checks both `AstInvariants.assertTenantScopedAtRoot` and `SharingAstInvariants.assertSharingScopedAtRoot` at construction time: there is structurally no path from DSL text to a sharing-filtered `PreparedStatement` that skips either rewriter.

**Why the sharing predicate is computed downward from the viewer, then checked upward from the owner.** `RoleHierarchyResolver.visibleOwnerUserIds` walks the role tree breadth-first *downward* from the viewer's own role (a manager's visible set includes every descendant role, so a manager sees a subordinate's records but a peer or an unrelated branch does not) and hands the resulting owner-id list to `SharingScopingRewriter`, which lowers it to `r.owner_user_id IN (...) OR EXISTS (record_shares ...)` directly against the physical columns, the same way `TenantScopeNode` lowers directly against `r.tenant_id`. `ReferenceInterpreter.executeWithSharing` computes the identical predicate the opposite direction, walking *upward* from each candidate row's owner's role one parent hop at a time to see whether the viewer's role is reached, over data pulled by its own `JdbcTemplate` queries that share no code with `RoleHierarchyResolver` or its repositories. Two implementations computing the same set from opposite directions, over independently-queried data, is why a diff between them means something.

## The type platform

```
src/main/java/com/manas/metaplatform/typesystem/
  dsl/            TypeLexer, TypeParser, TypeDecl, FieldDecl: a small hand-written parser for
                   "type Name { field: TYPE ... }" declarations, sibling to the SOQL DSL's own
                   lexer/parser but a separate grammar for a separate purpose
  compiler/        TypePlatformCompiler (front door), DdlEmitter (CREATE TABLE per type),
                   JavaClientEmitter / TypeScriptClientEmitter (one model + client class per type)
  client/          GeneratedTypeClient (the uniform interface all 40 generated clients implement),
                   TypeClientSupport (the one hand-written HTTP+JSON helper every generated Java
                   client calls into)
  web/             TypeSystemBootstrap (runs the DDL at context startup, registers types),
                   TypeRegistry, GenericTypeController (the one REST surface all 40 types share)
  codegen/         GenerateClients: writes the emitted Java and TypeScript source to generated-clients/
src/main/resources/typesystem/declared-types.dsl   the single declaration point: 40 types
generated-clients/java/.../generated/{model,client}/    40 generated Java model + client pairs
generated-clients/typescript/{model,client}/            40 generated TypeScript model + client pairs
generated-clients/typescript/index.ts                   buildClients(): the TS-side client registry
generated-clients/typescript/runtime/                   hand-written HTTP helper + the differential
                                                          benchmark's TypeScript-side query runner
src/test/java/com/manas/metaplatform/typesystem/
  TypeSystemIntegrationTest.java     always-on: 40 types registered, one Java client round-trip
  TypePlatformClientLoader.java      reflectively loads any of the 40 generated Java clients by name
  TypePlatformBenchmarkRunner.java   tagged benchmark: the 2,000-query differential run
```

**Why one generic REST controller instead of 40 generated controllers.** `GenericTypeController` dispatches on the `typeName` path variable at request time against `TypeRegistry`, rather than 40 hand-generated `@RestController` classes. This is the same tradeoff most real ORM-backed admin APIs make: the server side does not need to be "typed" to be correct, because `TypeRegistry` and the declared DDL already guarantee every table matches its declaration; typed-ness is what the *client* side needs, because that is the code an application author actually writes against and where a typo in a field name should be a compile error.

**Why the differential benchmark loads all 40 Java clients reflectively instead of 40 import statements.** Every generated `<Type>Client` shares the same `(String baseUrl)` constructor and implements `GeneratedTypeClient`, so `TypePlatformClientLoader.load(typeName, baseUrl)` (`Class.forName(...).getConstructor(String.class).newInstance(baseUrl)`) drives all 40 uniformly. The generated TypeScript side does the equivalent with an explicit, generated `index.ts` registry (`buildClients()`) instead of reflection, because that is the idiomatic way to do it in TypeScript; both approaches exist for the same reason, a test harness that must drive 40 generated clients cannot have 40 hand-written call sites without the import list going stale the next time the DSL gains a type.

## The bitemporal book of record

```
src/main/java/com/manas/metaplatform/bookofrecord/
  domain/
    FundAccount.java          a tenant-scoped simulated fund account
    LedgerEntry.java           one bitemporal version of one fact: effectiveDate (business
                               time), asOfFrom/asOfTo (system time), factId stable across
                               every version of "the same fact"
    LedgerEntryType.java       TRADE | CORPORATE_ACTION
  repository/
    LedgerEntryRepository.java, FundAccountRepository.java, PositionAggregateRepository.java
  generator/
    BookOfRecordDataSeeder.java   builds the 500-account / 250,000-row backfill dataset
  projection/
    PositionProjector.java    the fast path: one SQL GROUP BY trusting `as_of_to`
  restatement/
    RestatementWriter.java    the only code allowed to close a version and insert the next
src/main/kotlin/com/manas/metaplatform/bookofrecord/
  oracle/
    IndependentRecomputation.kt   the diff oracle: raw JDBC, decides "current" from
                                   `as_of_from` alone, never reads `as_of_to`
  restatement/
    RestatementDiff.kt         the named field-by-field diff the React console renders
  web/
    RestatementController.kt   REST surface backing the console (positions, restatement diff)
src/test/java/.../bookofrecord/restatement/
  RestatementWriterTest.java   proves a correction never UPDATEs the prior version's economic fields
src/test/kotlin/.../bookofrecord/
  integration/BookOfRecordIntegrationTest.kt   fast wiring check, 5 accounts
  bench/RestatementBenchmarkRunner.kt           the 500-account / 250,000-row measurement run
  web/ConsoleFixtureExporterTest.kt             exports real restatements to the React fixture
```

**Why `effectiveDate` and `asOfFrom`/`asOfTo` are two different kinds of time.** `effectiveDate` is business (valid) time: when a trade or corporate action actually happened in the market. `asOfFrom`/`asOfTo` is system (transaction) time: the window during which a given version was this system's current belief. A correction never changes `effectiveDate`; it only ever closes one version's `asOfTo` and opens a new version's `asOfFrom`. This is the standard bitemporal design (two independent time axes, not one), chosen because the resume claim is specifically about a back-dated *correction*, which is a transaction-time event about a fixed business-time fact, not a change to when the fact happened.

**Why a correction is "close, then insert", never "update".** `RestatementWriter.correct` loads the currently-open version of a fact, calls `closeAsOf` (which touches only the bookkeeping `asOfTo` column), saves it, then constructs and saves a brand new `LedgerEntry` row with the same `factId`, the next `version` number, and the corrected economic fields. There is no code path in this package that runs `UPDATE` on `quantity`, `costBasis`, `realizedPnl` or `effectiveDate` on an existing row; `RestatementWriterTest` asserts the prior version's economic fields are byte-identical (via `isEqualByComparingTo`, not reference equality) before and after a correction is applied to its fact.

**Why the fast path and the oracle disagree about what "current" even means, on purpose.** `PositionProjector` (Java, JPQL `GROUP BY` over `as_of_to is null`) trusts that `RestatementWriter` closed every superseded version correctly; it is the production-shaped read path. `IndependentRecomputation` (Kotlin, raw JDBC) never reads `as_of_to` at all: for every `factId`, it looks at every version's `asOfFrom` and picks the one with the latest timestamp as current, the same way a brute-force "replay everything and take the newest" auditor would. The two implementations would only ever agree if the writer actually maintained `as_of_to` correctly for every one of 250,000 rows; `RestatementBenchmarkRunner` diffs their per-row verdicts and their summed positions, not just a spot check.

## Validation

Default test run (parser, AST invariants, integration wiring, tenant-only and sharing, the type platform's wiring check, and the book of record's fast-wiring, write-path and fixture-export checks), 37 tests, all passing (`docs/test_output.txt`):

```
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- AstInvariantsTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- ParserTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- PlatformIntegrationTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- SharingIntegrationTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- SharingAstInvariantsTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- TypeSystemIntegrationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- BookOfRecordIntegrationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- RestatementWriterTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- ConsoleFixtureExporterTest
Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`ParserTest` covers select-list parsing, all six WHERE shapes (none, single, AND, OR, parenthesized), all six numeric operators, escaped-quote string literals, and four distinct syntax-error cases (missing `FROM`, trailing garbage, unterminated string, unbalanced parenthesis).

`AstInvariantsTest` is a pure structural test with no database: it builds `Expr` trees by hand and asserts `AstInvariants.assertTenantScopedAtRoot` accepts a bare tenant node, a tenant node ANDed with a user clause, and a tenant node three ANDs deep, while rejecting a missing tenant node, a tenant node buried under an OR (the exact shape a string-concatenation bug could produce), a wrong tenant id, and duplicate tenant nodes. `SharingAstInvariantsTest` is the same set of assertions for `SharingScopeNode` against `SharingAstInvariants`.

`PlatformIntegrationTest` seeds the real dataset through the real Spring context against H2, then checks that the compiler and the reference interpreter agree on a live query, that two tenants' identically-named `Contact` objects never share a row, that querying another tenant's exclusive object name is rejected with `UnknownObjectException`, and that the compiled SQL text never contains the tenant id as a standalone numeric token (a word-boundary check, since column aliases like `fv1` can coincidentally contain the same digits). `SharingIntegrationTest` is the sharing-filter equivalent: an owner sees their own record, the CEO sees a sales rep's record through the role hierarchy, an unrelated peer rep sees nothing with no grant, an explicit grantee outside ownership and the hierarchy sees the one record it was granted, and the compiled SQL for a sharing-scoped query names both `r.tenant_id = ?` and the sharing predicate (`r.owner_user_id IN`, `record_shares`).

`TypeSystemIntegrationTest` confirms the DSL parses to exactly 40 registered types (the context fails to start otherwise, since `TypeSystemBootstrap` runs the DDL for each one at startup) and that one generated Java client can create and read back a real record through the generic REST surface. `BenchmarkRunner`, `SharingBenchmarkRunner`, and `TypePlatformBenchmarkRunner` (all tagged `benchmark`, excluded from the default run) are the scale measurements behind those resume claims; see "Measured results" below for their output.

`BookOfRecordIntegrationTest` seeds 5 accounts (2,500 rows) and asserts the fast path and the independent oracle agree on every row's current/superseded verdict and on every summed position, the fast, always-on confirmation that the mechanism is wired correctly; `RestatementBenchmarkRunner` (tagged `benchmark`) is the 500-account, 250,000-row scale measurement, see "Measured results". `RestatementWriterTest` asserts a correction leaves the prior version's `quantity`, `costBasis`, `realizedPnl` and `effectiveDate` byte-identical and only closes its `asOfTo`. `ConsoleFixtureExporterTest` books three real corrected facts and writes their diffs to `web/src/fixtures/restatements.json`, which `web/tests/App.test.tsx` (3 Vitest + React Testing Library tests, run separately with `npm test` inside `web/`, `docs/web_test_output.txt`) asserts names every field for every fact in that fixture.

## Findings

The first version of the tenant-scoped "compiled SQL never contains the tenant id" test used a bare substring check (`doesNotContain(String.valueOf(tenantId))`) and failed on the very first run, not because of a tenant-scoping bug but because the compiler's own generated column alias for the first joined field, `fv1`, coincidentally contains the digit `1`, which was also the seeded tenant id under test. The measurement that discriminated a real leak from a coincidental digit collision was switching to a word-boundary regex (`(?<![A-Za-z0-9_])1(?![0-9])`) that only matches a standalone numeric token, plus a positive check that `r.tenant_id = ?` (the placeholder form) is present in the SQL text. This is exactly the kind of thing a real measurement is supposed to catch: the first assertion was too strong in the wrong way, and weakening it precisely (rather than deleting it) is what makes the surviving assertion still mean something.

Building the sharing filter's reference oracle, `ReferenceInterpreter.executeWithSharing` initially failed to compile with `reference to query is ambiguous`, on two of its four `JdbcTemplate.query(...)` calls. The compiler could not decide between `JdbcTemplate`'s `ResultSetExtractor` overload and its `RowCallbackHandler` overload for a lambda whose body was a single expression statement (`rs -> map.put(rs.getLong("id"), rs.getLong("role_id"))`); the other two calls in the same method, written as an explicit `{ ... }` block with no return value, resolved without ambiguity. The fix was to cast the two ambiguous lambdas to `RowCallbackHandler` explicitly, which is a real, if minor, lesson about `JdbcTemplate`'s overload set rather than a logic bug: it was caught at compile time, before any measurement ran, so it never had a chance to produce a wrong number.

The first run of the type platform's differential benchmark reported 0/2,000 matched, with the printed mismatch sample showing the two sides' result maps looking identical field by field. The comparison itself was the bug, not the two clients: Jackson can hand back an `Integer` from one deserialization path and a `Double` from another for the identical JSON number, and the original diff used plain `Object.equals`, which treats `5` and `5.0` as unequal. The fix was a small recursive comparator that treats any two `Number`s as equal by value; after that fix the run reported 2,000/2,000 on the first honest attempt, which is recorded as-is below rather than re-run to look less convenient, since nothing about the fix changed what was actually being measured, only how two representations of the same number were compared.

The real bug caught during this extension was in Spring's own AOP machinery, not the bitemporal logic: `RestatementWriter` was initially declared `final`, and Spring's `@Transactional` proxying (CGLIB) cannot subclass a final class, which failed loudly at Spring context startup, before any test or measurement ran, the moment `@SpringBootTest` first tried to wire it. The fix was removing `final`. The 250,000-row restatement benchmark itself matched 250,000/250,000 and 5,000/5,000 on the first genuine attempt (`docs/bookofrecord_benchmark_output.txt`), which is recorded as-is rather than invented drama; nothing about the measurement needed a second attempt.

## Measured results

Machine: 8 physical / 16 logical cores, Windows 11 Home, JDK 21 Temurin, Maven 3.9.9, Spring Boot 3.3.4, H2 2.x in PostgreSQL-compatibility mode. Runs: `mvn test -Dtest=BenchmarkRunner -Dsurefire.excludedGroups=` (raw output in `docs/benchmark_output.txt`) and `mvn test -Dtest=SharingBenchmarkRunner -Dsurefire.excludedGroups=` (raw output in `docs/sharing_benchmark_output.txt`).

Tenant-isolation dataset: 6 synthetic tenants, 3 shared object names per tenant (150 records each) plus 1 exclusive object per tenant (20 records), 85% field-presence rate, seed `1001`. Sharing dataset: 1 synthetic company, an 11-role/4-level hierarchy, 200 `Opportunity` records (20 per rep across 10 reps), 40 explicit cross-branch `record_shares` grants, seed `9001`.

| Metric | Measured | Claim | Meets claim |
|---|---|---|---|
| **Adversarial cross-tenant isolation** | **0 foreign rows across 500 probes** (250 cross-tenant literal-collision probes, 250 parameterization-breaking literal probes), re-verified after the sharing filter was added | 500 adversarial cross-tenant queries returned 0 foreign rows | yes |
| **Reference-translator diff (tenant only)** | **2,000 / 2,000 generated queries matched the independent reference interpreter exactly**, row for row, field for field, re-verified after the sharing filter was added | 2,000 generated queries matched an independent reference translator exactly | yes |
| **Sharing-visibility adversarial suite** | **0 leaks, 0 wrongful denials, across 500 probes** (100 each of owner, role-hierarchy ancestor, explicit share, same-tier peer denial, cross-branch denial) | tenant predicate and a record-level sharing filter injected into the syntax tree, verified against an adversarial probe suite | yes |
| **Sharing-filter reference-translator diff** | **1,000 / 1,000 generated sharing-scoped queries matched the independent reference interpreter exactly** | compiler lowers the DSL and the sharing filter to SQL correctly, checked against an independent second implementation | yes |
| **Declared types** | **40** types declared in `declared-types.dsl` and registered by `TypeSystemBootstrap` at startup | 40 declared types | yes |
| **Cross-client differential (type platform)** | **2,000 / 2,000 generated queries matched** between the generated Java client (in-process) and the generated TypeScript client (`node --experimental-strip-types`), both against the same live server and database | the two generated clients returned identical results on 2,000 generated queries | yes |
| **As-of restatement, per-row verdict** | **250,000 / 250,000** ledger rows' current/superseded verdict matched between the fast path and the independent oracle, met on the first genuine attempt | restatements for 500 accounts matched an independent recomputation on all 250,000 records | yes |
| **As-of restatement, position aggregates** | **5,000 / 5,000** account/symbol position-and-P&L aggregates matched (500 accounts x 10 symbols) | (supporting figure for the row above; not a separately stated claim) | yes |
| **Record-level sharing isolation (inherited, re-verified)** | **0 leaks / 0 wrongful denials across 500 probes**, unchanged, re-run after this extension | the inherited tenant and sharing isolation claims still hold after the book of record was added | yes |

Book of record run: `mvn test -Dtest=RestatementBenchmarkRunner -Dsurefire.excludedGroups=` (raw output in `docs/bookofrecord_benchmark_output.txt`). 500 simulated fund accounts, 10 symbols each, 450 base facts per account plus 50 corrected facts per account (500 rows/account, 250,000 rows total), seed `9001`.

Type platform run: `mvn test -Dtest=TypePlatformBenchmarkRunner -Dsurefire.excludedGroups=` (raw output in `docs/typeplatform_benchmark_output.txt`). 400 records seeded (10 per type across the 40 declared types), then 2,000 queries generated uniformly at random (a GET-by-id or a full LIST per query) and run through both clients against the same running server.

All figures above are counted, not sampled: every probe, generated query, and ledger row in the runs that produced `docs/benchmark_output.txt`, `docs/sharing_benchmark_output.txt`, and `docs/bookofrecord_benchmark_output.txt` is included in the totals above. The tenant-isolation "foreign row" check looks up each returned row's true `tenant_id` directly against the `records` table, independent of whatever the query claimed to be scoped to. The sharing-visibility check computes `expectedVisible` independently, from the seeded ownership/role-hierarchy/explicit-share data, for every probe before the query ever runs, so a leak or a wrongful denial shows up as a nonzero count rather than needing to be spotted by eye. Both reference-translator diffs compare full result rows (record id, every selected field's value), not just row counts or SQL text. The restatement per-row verdict compares, for every one of 250,000 rows, whether the fast path's `as_of_to is null` flag agrees with the oracle's independently-derived "latest `as_of_from` for this fact" answer; the position-aggregate check compares summed `quantity`, `costBasis` and `realizedPnl` per account/symbol between the two implementations.

What this does not measure: query performance or planner behavior (out of scope, see "Honest framing"), concurrent multi-tenant load (the measurement runs are single-threaded), correctness against a real PostgreSQL instance rather than H2 in compatibility mode, sharing rules keyed on record criteria rather than ownership, hierarchy or explicit grant, and a restatement that changes `effectiveDate` itself rather than the economic fields on a fixed business date (see "Limitations").

## Building and running

```
export PATH="/c/Users/Manas/tools/apache-maven-3.9.9/bin:$PATH"   # or the equivalent mvn on PATH
mvn test                                                          # 37 tests, H2 in-memory
mvn test -Dtest=BenchmarkRunner -Dsurefire.excludedGroups=        # the 2,000-query / 500-probe tenant-isolation run
mvn test -Dtest=SharingBenchmarkRunner -Dsurefire.excludedGroups= # the 1,000-query / 500-probe sharing-filter run
mvn test -Dtest=TypePlatformBenchmarkRunner -Dsurefire.excludedGroups= # the type platform's 2,000-query Java/TS differential (needs node on PATH)
mvn test -Dtest=RestatementBenchmarkRunner -Dsurefire.excludedGroups= # the 500-account / 250,000-row restatement run
cd web && npm install && npm test && npm run build                # the restatement console: 3 tests, then a production build
```

Against a real PostgreSQL instance (not exercised for any number in this README):

```
createdb metaplatform
export METAPLATFORM_DB_PASSWORD=...
mvn spring-boot:run
```

## Sibling comparison

This is the first repository in the portfolio with a tenancy model or a query compiler. [`securities-settlement-matching`](https://github.com/Manas103/securities-settlement-matching) and `card-authorization-gateway` are the nearest existing Java/Spring Boot services by stack (Spring Boot, Spring Data JPA, H2-in-PostgreSQL-compatibility-mode testing), but both are single-tenant domain services: every row in those services already belongs to the one system running them, so there is no tenant predicate to inject and no adversarial cross-tenant suite to run. Their "diffed against a reference" pattern (a linear-scan reference oracle, in `securities-settlement-matching`'s case) is the same validation idea this project reuses for `ReferenceInterpreter`, applied here to query translation instead of settlement matching.

It is also the first repository in the portfolio with two time axes. [`corporate-action-event-ledger`](https://github.com/Manas103/corporate-action-event-ledger) reconciles disagreeing sources into a versioned, append-only ledger keyed on `(event id, version)`, which is close in spirit (never mutate a prior version) but has only one notion of time: a version is superseded, full stop, with no separate business-time axis a reader could use to ask "what was effective on this date, as the system believed at that time". This repository's `effectiveDate` vs `asOfFrom`/`asOfTo` split is what a real bitemporal restatement needs and a single-version-number ledger does not give you.

## Limitations

No real PostgreSQL instance was exercised for any number in this README; every measured figure comes from the H2-in-PostgreSQL-compatibility-mode test path. The DSL supports a single flat table's worth of fields per object (no relationships or joins between custom objects), six comparison operators, and no aggregate functions (`COUNT`, `SUM`, etc.), all deliberately out of scope for a small version of the idea. Concurrency correctness under simultaneous writers is whatever Spring Data JPA and the underlying database already provide; nothing in this project adds tenant-aware locking or isolation beyond that. The adversarial suite covers cross-tenant literal collisions and parameterization-breaking string literals; it does not include a fuzzer or a formal proof, so it is evidence of correctness at the scale measured (500 probes), not a guarantee for every possible adversarial input.

The record-level sharing filter covers three visibility paths (ownership, role-hierarchy descent, explicit per-record grant) and no others: no criteria-based sharing rules, no public groups, no manual-share reason codes, and no write-time enforcement (`executeWithSharing` is a read path; nothing in this project checks a write against the sharing model). `RoleHierarchyResolver` recomputes the visible owner-id set from the full role/user table on every call rather than caching it, which is a real, disclosed cost at the scale a production sharing engine would need to handle (thousands of roles), not measured here because the seeded hierarchy (11 roles) is far too small for that cost to show up. The sharing adversarial suite, like the tenant one, is evidence at the scale measured (500 probes across 5 categories), not a formal proof.

The type platform's DSL supports the same three field types the SOQL DSL's EAV storage does (STRING/NUMBER/BOOLEAN); no relationships between declared types, no server-side validation beyond what the column type enforces, and tables are dropped and recreated on every application startup (a test-friendly choice, not a migration strategy, see `TypeSystemBootstrap`). The differential benchmark seeds 10 records per type (400 total) and issues GET-by-id and unfiltered LIST queries; it does not exercise equality-filtered LIST queries, so it is evidence about the generated clients agreeing on record shape and content, not about every possible query the REST surface accepts. The generated TypeScript client depends on Node's `--experimental-strip-types` flag, which Node itself documents as experimental and subject to change.

The book of record supports exactly two versions of a fact (original and one correction); nothing in `RestatementWriter` forbids a second correction (`correct` just closes whichever version is currently open and inserts the next), but the seeded dataset and the benchmark only exercise one correction per corrected fact, so two-deep correction chains are untested. A correction can change `quantity`, `costBasis`, `realizedPnl`, `entryType` or implicitly nothing at all; it cannot change `effectiveDate` or `symbol` (restating "which date" or "which instrument" a trade belongs to is a materially different operation this project does not model). `PositionProjector` and `IndependentRecomputation` both answer "what is current as of system time now"; querying a past system time ("what did we believe on 2026-07-20") is representable in the data (`asOfFrom`/`asOfTo` already carry that information) but no query path in this project exposes it yet. The 500-account/250,000-row benchmark is single-threaded, like every other benchmark in this repository; concurrent corrections to the same fact are not exercised. The React console renders a static fixture, not a live call against `RestatementController`; wiring the console to the real running server is future work, not something measured here.
