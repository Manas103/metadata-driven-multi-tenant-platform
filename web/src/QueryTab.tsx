import { useState, type FormEvent } from "react";
import { api } from "./api";
import { ErrorMessage, Loading, TenantSelect, formatValue, useAction, useLoad } from "./ui";
import type { QueryResult } from "./types";

export const EXAMPLE_DSL = "SELECT name FROM Contact WHERE name != ''";

/** Runs the DSL for one tenant, optionally as one of its users, and shows the SQL it compiled to. */
export function QueryTab() {
  const tenants = useLoad(() => api.tenants(), []);
  const [picked, setPicked] = useState<number | null>(null);
  const tenantId =
    tenants.data?.find((t) => t.id === picked)?.id ?? tenants.data?.[0]?.id ?? null;
  const users = useLoad(tenantId === null ? null : () => api.users(tenantId), [tenantId]);

  const [dsl, setDsl] = useState(EXAMPLE_DSL);
  const [viewer, setViewer] = useState("");
  const [result, setResult] = useState<QueryResult | null>(null);
  const action = useAction();

  const run = async () => {
    if (tenantId === null || !dsl.trim()) return;
    setResult(null);
    const viewerUserId = viewer === "" ? undefined : Number(viewer);
    const r = await action.run(() => api.query(tenantId, dsl, viewerUserId));
    if (r) setResult(r);
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    void run();
  };

  const viewerName = users.data?.find((u) => String(u.id) === viewer)?.name;

  return (
    <div className="stack">
      {tenants.loading && !tenants.data && <Loading what="tenants" />}
      <ErrorMessage message={tenants.error} />

      <form className="card" onSubmit={submit} aria-labelledby="query-heading">
        <h2 id="query-heading">Query</h2>
        <div className="row">
          {tenants.data && tenants.data.length > 0 && (
            <TenantSelect
              id="query-tenant"
              tenants={tenants.data}
              value={tenantId}
              onChange={(id) => {
                setPicked(id);
                setViewer("");
                setResult(null);
              }}
            />
          )}
          <div className="field">
            <label htmlFor="query-viewer">View as user</label>
            <select
              id="query-viewer"
              value={viewer}
              onChange={(e) => setViewer(e.target.value)}
              disabled={!users.data || users.data.length === 0}
            >
              <option value="">(nobody: tenant scoping only)</option>
              {users.data?.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.name}
                  {u.role ? `, ${u.role}` : ""} (#{u.id})
                </option>
              ))}
            </select>
          </div>
        </div>
        <ErrorMessage message={users.error} />
        <div className="field">
          <label htmlFor="query-dsl">DSL query</label>
          <textarea
            id="query-dsl"
            className="mono"
            rows={4}
            spellCheck={false}
            value={dsl}
            onChange={(e) => setDsl(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
                e.preventDefault();
                void run();
              }
            }}
          />
        </div>
        <p className="hint">
          Grammar: <code>SELECT f1, f2 FROM Object WHERE predicate</code>. WHERE is required; combine with AND, OR
          and parentheses; operators <code>= != &lt; &lt;= &gt; &gt;=</code>; strings in single quotes. Ctrl+Enter
          runs.
        </p>
        <div className="actions">
          <button type="submit" disabled={action.busy || tenantId === null || !dsl.trim()}>
            {action.busy ? "Running..." : "Run query"}
          </button>
          <button type="button" className="secondary" onClick={() => setDsl(EXAMPLE_DSL)}>
            Reset example
          </button>
        </div>
        <ErrorMessage message={action.error} />
      </form>

      {result && <QueryResultView result={result} viewerName={viewerName} />}
    </div>
  );
}

function QueryResultView({ result, viewerName }: { result: QueryResult; viewerName?: string }) {
  const columns = columnsOf(result.rows);
  const scopingText =
    result.scoping === "tenant+sharing"
      ? `tenant+sharing: tenant filter plus record sharing${viewerName ? ` for ${viewerName}` : ""}`
      : "tenant: tenant filter only";
  return (
    <section className="card" aria-labelledby="result-heading">
      <h2 id="result-heading">Result</h2>
      <dl className="facts">
        <div>
          <dt>Scoping</dt>
          <dd data-testid="scoping">{scopingText}</dd>
        </div>
        <div>
          <dt>Elapsed</dt>
          <dd data-testid="elapsed">{result.elapsedMs.toFixed(2)} ms</dd>
        </div>
        <div>
          <dt>Rows</dt>
          <dd>{result.rows.length}</dd>
        </div>
      </dl>
      <h3>Compiled SQL</h3>
      <pre className="sql" aria-label="Compiled SQL">
        {result.sql}
      </pre>
      <h3>Params</h3>
      <ol className="params" aria-label="SQL params">
        {result.params.length === 0 && <li className="muted">none</li>}
        {result.params.map((p, i) => (
          <li key={i}>
            <code>{JSON.stringify(p)}</code>
          </li>
        ))}
      </ol>
      <h3>Rows</h3>
      {result.rows.length === 0 ? (
        <p className="muted">No rows matched.</p>
      ) : (
        <div className="table-wrap">
          <table aria-label="Query rows">
            <thead>
              <tr>
                {columns.map((c) => (
                  <th scope="col" key={c}>
                    {c}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {result.rows.map((row, i) => (
                <tr key={i}>
                  {columns.map((c) => (
                    <td key={c} className={row[c] === null || row[c] === undefined ? "null" : undefined}>
                      {formatValue(row[c])}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** Every key that appears in any row, in first-seen order, with record_id first. */
export function columnsOf(rows: Record<string, unknown>[]): string[] {
  const seen: string[] = [];
  for (const row of rows) {
    for (const k of Object.keys(row)) if (!seen.includes(k)) seen.push(k);
  }
  const i = seen.indexOf("record_id");
  if (i > 0) {
    seen.splice(i, 1);
    seen.unshift("record_id");
  }
  return seen;
}
