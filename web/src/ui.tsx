import { useCallback, useEffect, useState, type ReactNode } from "react";
import { errorMessage } from "./api";
import type { Decimal, Tenant, VersionView } from "./types";

interface LoadState<T> {
  key: string;
  data: T | null;
  loading: boolean;
  error: string | null;
}

/**
 * Loads data whenever `deps` change (pass `null` as the loader to load nothing). `reload()`
 * refetches but keeps showing the current data meanwhile; a deps change clears it, so one
 * tenant's objects never flash under another tenant's name. Late answers for old deps are dropped.
 */
export function useLoad<T>(load: (() => Promise<T>) | null, deps: readonly unknown[]) {
  const key = JSON.stringify(deps);
  const [state, setState] = useState<LoadState<T>>({ key, data: null, loading: load !== null, error: null });
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    if (!load) {
      setState({ key, data: null, loading: false, error: null });
      return;
    }
    let live = true;
    setState((s) => ({ key, data: s.key === key ? s.data : null, loading: true, error: null }));
    load().then(
      (data) => live && setState({ key, data, loading: false, error: null }),
      (e: unknown) => live && setState({ key, data: null, loading: false, error: errorMessage(e) }),
    );
    return () => {
      live = false;
    };
    // `load` is a fresh closure every render; `key` and `nonce` are what decide a refetch.
  }, [key, nonce]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  const current = state.key === key;
  return {
    data: current ? state.data : null,
    loading: current ? state.loading : load !== null,
    error: current ? state.error : null,
    reload,
  };
}

/** Runs a mutation with a busy flag and the server's error message. Resolves to undefined on failure. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = useCallback(async <T,>(fn: () => Promise<T>): Promise<T | undefined> => {
    setBusy(true);
    setError(null);
    try {
      return await fn();
    } catch (e) {
      setError(errorMessage(e));
      return undefined;
    } finally {
      setBusy(false);
    }
  }, []);
  return { busy, error, run, setError };
}

export function Loading({ what }: { what: string }) {
  return (
    <p className="loading" role="status">
      Loading {what}...
    </p>
  );
}

export function ErrorMessage({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <p className="error" role="alert">
      {message}
    </p>
  );
}

export function Notice({ children }: { children: ReactNode }) {
  return (
    <p className="notice" role="status">
      {children}
    </p>
  );
}

export function TenantSelect({
  id,
  tenants,
  value,
  onChange,
}: {
  id: string;
  tenants: Tenant[];
  value: number | null;
  onChange: (id: number) => void;
}) {
  return (
    <div className="field">
      <label htmlFor={id}>Tenant</label>
      <select id={id} value={value ?? ""} onChange={(e) => onChange(Number(e.target.value))}>
        {tenants.map((t) => (
          <option key={t.id} value={t.id}>
            {t.name} (#{t.id}, {t.objectCount} objects, {t.userCount} users)
          </option>
        ))}
      </select>
    </div>
  );
}

/** Renders any cell value from a query row or ledger view. */
export function formatValue(v: unknown): string {
  if (v === null || v === undefined) return "null";
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

function decimal(v: Decimal): string {
  return String(v);
}

/** A table of fact versions. Rows the server has since superseded (asOfTo set) are marked. */
export function VersionTable({ versions, caption }: { versions: VersionView[]; caption: string }) {
  if (versions.length === 0) {
    return <p className="muted">{caption}: no entries.</p>;
  }
  return (
    <div className="table-wrap">
      <table>
        <caption>{caption}</caption>
        <thead>
          <tr>
            <th scope="col">Version</th>
            <th scope="col">Symbol</th>
            <th scope="col">Effective</th>
            <th scope="col">Type</th>
            <th scope="col" className="num">Quantity</th>
            <th scope="col" className="num">Cost basis</th>
            <th scope="col" className="num">Realized PnL</th>
            <th scope="col">As of from</th>
            <th scope="col">As of to</th>
          </tr>
        </thead>
        <tbody>
          {versions.map((v, i) => (
            <tr key={`${v.symbol}-${v.effectiveDate}-${v.version}-${i}`} className={v.asOfTo ? "superseded" : "current"}>
              <td>{v.version}</td>
              <td>{v.symbol}</td>
              <td>{v.effectiveDate}</td>
              <td>{v.entryType}</td>
              <td className="num">{decimal(v.quantity)}</td>
              <td className="num">{decimal(v.costBasis)}</td>
              <td className="num">{decimal(v.realizedPnl)}</td>
              <td>{v.asOfFrom}</td>
              <td>{v.asOfTo ?? <span className="badge">open</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
