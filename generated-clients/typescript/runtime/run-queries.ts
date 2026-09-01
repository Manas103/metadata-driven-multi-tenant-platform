// HAND-WRITTEN, not generated. The TypeScript-side counterpart of the Java differential
// benchmark's query loop: reads a JSON array of generated queries, drives each one through
// the generated client for its declared type (via the generated index.ts registry), and
// writes a JSON array of results in the same order. Invoked as:
//   node --experimental-strip-types runtime/run-queries.ts <baseUrl> <queriesFile> <outputFile>
// Run once per differential benchmark, against the same live server the Java client half
// of the run talks to; it does not start or stop the server itself.

import { readFileSync, writeFileSync } from "node:fs";
import { buildClients } from "../index.ts";
import type { GenericClientOps } from "../index.ts";

interface GeneratedQuery {
  op: "GET" | "LIST";
  type: string;
  id?: number;
  filterField?: string;
  filterValue?: unknown;
}

async function main(): Promise<void> {
  const [baseUrl, queriesFile, outputFile] = process.argv.slice(2);
  if (!baseUrl || !queriesFile || !outputFile) {
    throw new Error("usage: run-queries.ts <baseUrl> <queriesFile> <outputFile>");
  }

  const queries: GeneratedQuery[] = JSON.parse(readFileSync(queriesFile, "utf-8"));
  const clients: Record<string, GenericClientOps> = buildClients(baseUrl);

  const results: unknown[] = [];
  for (const query of queries) {
    const client = clients[query.type];
    if (!client) {
      throw new Error(`no generated client for declared type ${query.type}`);
    }
    if (query.op === "GET") {
      results.push(await client.getById(query.id as number));
    } else {
      const filters: Record<string, unknown> = {};
      if (query.filterField !== undefined) {
        filters[query.filterField] = query.filterValue;
      }
      results.push(await client.list(filters));
    }
  }

  writeFileSync(outputFile, JSON.stringify(results));
  console.log(`ran ${queries.length} queries through the generated TypeScript clients`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
