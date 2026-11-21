# Admin Console

A React + TypeScript admin console for the Spring Boot backend in the parent module. Plain
React, one stylesheet, a typed `fetch` client (`src/api.ts`), no router or UI library. Three
tabs:

- **Schema**: pick a tenant, see its metadata-defined objects with their fields and record
  counts, create objects, add fields (a metadata row, not an ALTER TABLE) and create records
  with an optional owner.
- **Query**: run the DSL (`SELECT f1, f2 FROM Object WHERE predicate`) for a tenant, optionally
  as one of its users, and see the scoping, the compiled SQL with its params, the elapsed time
  and the rows.
- **Ledger**: the bitemporal book of record: accounts, restated facts, a fact's version
  history and latest diff, booking a correction now, and "what did we believe at" an instant
  next to what the ledger believes now.

```
npm install
npm run dev      # Vite on :5173, proxies /api and /actuator to the backend on :8080
npm test         # Vitest + Testing Library, fetch is mocked, no backend needed
npm run build    # tsc -b && vite build, output in web/dist
```

Start the backend first (with `platform.demo-data: true` to seed demo tenants and a ledger);
the header shows `Backend: UP` once `/actuator/health` answers.

`src/fixtures/restatements.json` is not hand-typed: it is produced by
`ConsoleFixtureExporterTest` in the parent module, which books a few real corrected facts
through the real `RestatementWriter` and diffs them with the real `RestatementDiff.of`. The
console no longer renders it; the ledger tests use it as realistic diff data.
