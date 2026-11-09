# Restatement Console

A small React/TypeScript single-page app that names every field a bitemporal correction
changed. See the main repository README's "Bitemporal book of record" section for the
backend this console reads from.

```
npm install
npm run dev      # local dev server
npm test         # 3 Vitest + React Testing Library tests
npm run build    # tsc -b && vite build
```

`src/fixtures/restatements.json` is not hand-typed: it is produced by
`ConsoleFixtureExporterTest` in the parent Java/Kotlin module (`mvn test
-Dtest=ConsoleFixtureExporterTest`, part of the default `mvn test` run), which books a
few real corrected facts through the real `RestatementWriter` and diffs them with the
real `RestatementDiff.of`. Regenerate it by running that test again from the parent
module; this app only renders whatever that file contains.
