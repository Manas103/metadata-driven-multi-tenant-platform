import { useState, type FormEvent } from "react";
import { api, restatementOrNull } from "./api";
import { ErrorMessage, Loading, Notice, VersionTable, useAction, useLoad } from "./ui";
import type { Correction, RestatementDiff, VersionView } from "./types";

/** The bitemporal book of record: accounts, restated facts, version history, corrections, past beliefs. */
export function LedgerTab() {
  const accounts = useLoad(() => api.accounts(50), []);
  const [picked, setPicked] = useState<number | null>(null);
  const accountId =
    accounts.data?.find((a) => a.id === picked)?.id ?? accounts.data?.[0]?.id ?? null;
  const facts = useLoad(accountId === null ? null : () => api.restatedFacts(accountId), [accountId]);
  const [factId, setFactId] = useState<string | null>(null);

  return (
    <div className="stack">
      {accounts.loading && !accounts.data && <Loading what="accounts" />}
      <ErrorMessage message={accounts.error} />
      {accounts.data && accounts.data.length === 0 && <p className="muted">No fund accounts yet.</p>}

      {accounts.data && accounts.data.length > 0 && (
        <section className="card" aria-labelledby="accounts-heading">
          <h2 id="accounts-heading">Accounts</h2>
          <div className="field">
            <label htmlFor="ledger-account">Account</label>
            <select
              id="ledger-account"
              value={accountId ?? ""}
              onChange={(e) => {
                setPicked(Number(e.target.value));
                setFactId(null);
              }}
            >
              {accounts.data.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name} (#{a.id})
                </option>
              ))}
            </select>
          </div>

          <h3>Restated facts</h3>
          {facts.loading && !facts.data && <Loading what="restated facts" />}
          <ErrorMessage message={facts.error} />
          {facts.data && facts.data.length === 0 && <p className="muted">No fact in this account was ever corrected.</p>}
          {facts.data && facts.data.length > 0 && (
            <ul className="chips" aria-label="Restated facts">
              {facts.data.map((f) => (
                <li key={f}>
                  <button
                    type="button"
                    className="chip"
                    aria-pressed={f === factId}
                    onClick={() => setFactId(f)}
                  >
                    {f}
                  </button>
                </li>
              ))}
            </ul>
          )}
          <OpenFactForm onOpen={setFactId} />
        </section>
      )}

      {factId && <FactPanel key={factId} factId={factId} onCorrected={facts.reload} />}

      {accountId !== null && <BeliefPanel key={accountId} accountId={accountId} />}
    </div>
  );
}

function OpenFactForm({ onOpen }: { onOpen: (factId: string) => void }) {
  const [value, setValue] = useState("");
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (value.trim()) onOpen(value.trim());
  };
  return (
    <form className="inline-form" onSubmit={submit}>
      <div className="field">
        <label htmlFor="open-fact-id">Open any fact by id</label>
        <input id="open-fact-id" value={value} onChange={(e) => setValue(e.target.value)} />
      </div>
      <button type="submit" className="secondary" disabled={!value.trim()}>
        Open fact
      </button>
    </form>
  );
}

function FactPanel({ factId, onCorrected }: { factId: string; onCorrected: () => void }) {
  const versions = useLoad(() => api.versions(factId), [factId]);
  const diff = useLoad(() => restatementOrNull(factId), [factId]);
  const [booked, setBooked] = useState<RestatementDiff | null>(null);

  const afterCorrection = (d: RestatementDiff) => {
    setBooked(d);
    versions.reload();
    diff.reload();
    onCorrected();
  };

  const open = versions.data?.find((v) => v.asOfTo === null);

  return (
    <section className="card" aria-labelledby="fact-heading">
      <h2 id="fact-heading">
        Fact <code>{factId}</code>
      </h2>
      {versions.loading && !versions.data && <Loading what="version history" />}
      <ErrorMessage message={versions.error} />
      {versions.data && <VersionTable versions={versions.data} caption="Version history" />}

      <h3>Latest restatement</h3>
      {diff.loading && !diff.data && <Loading what="restatement" />}
      <ErrorMessage message={diff.error} />
      {!diff.loading && !diff.error && diff.data === null && (
        <p className="muted">This fact has a single version: it was never restated.</p>
      )}
      {diff.data && <DiffTable diff={diff.data} />}

      {versions.data && (
        <CorrectionForm factId={factId} current={open} onBooked={afterCorrection} booked={booked} />
      )}
    </section>
  );
}

/** Names every field the backend diffed between the superseded version and its replacement. */
export function DiffTable({ diff }: { diff: RestatementDiff }) {
  return (
    <div data-testid={`restatement-${diff.factId}`}>
      <p className="muted">
        {diff.symbol}, account {diff.accountId}, effective {diff.effectiveDate}. Restated at {diff.restatedAsOfFrom}{" "}
        (prior version current from {diff.priorAsOfFrom}).
      </p>
      <div className="table-wrap">
        <table>
          <caption>Restatement diff</caption>
          <thead>
            <tr>
              <th scope="col">Field</th>
              <th scope="col">Prior value</th>
              <th scope="col">Restated value</th>
              <th scope="col">Changed</th>
            </tr>
          </thead>
          <tbody>
            {diff.fields.map((f) => (
              <tr
                key={f.fieldName}
                className={f.changed ? "changed" : undefined}
                data-testid={`field-${diff.factId}-${f.fieldName}`}
              >
                <td>{f.fieldName}</td>
                <td>{f.priorValue}</td>
                <td>{f.restatedValue}</td>
                <td>{f.changed ? "yes" : "no"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

const CORRECTABLE = [
  { key: "quantity", label: "Corrected quantity" },
  { key: "costBasis", label: "Corrected cost basis" },
  { key: "realizedPnl", label: "Corrected realized PnL" },
] as const;

function CorrectionForm({
  factId,
  current,
  booked,
  onBooked,
}: {
  factId: string;
  current: VersionView | undefined;
  booked: RestatementDiff | null;
  onBooked: (d: RestatementDiff) => void;
}) {
  const [raw, setRaw] = useState<Record<string, string>>({});
  const action = useAction();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const body: Correction = {};
    for (const { key } of CORRECTABLE) {
      const v = raw[key]?.trim();
      if (!v) continue;
      const n = Number(v);
      if (Number.isNaN(n)) {
        action.setError(`${key} must be a number`);
        return;
      }
      body[key] = n;
    }
    if (Object.keys(body).length === 0) {
      action.setError("Enter at least one corrected value.");
      return;
    }
    const d = await action.run(() => api.correct(factId, body));
    if (d) {
      setRaw({});
      onBooked(d);
    }
  };

  const changed = booked?.fields.filter((f) => f.changed).map((f) => f.fieldName) ?? [];

  return (
    <form className="subcard" onSubmit={submit} aria-labelledby="correct-heading">
      <h3 id="correct-heading">Book a correction now</h3>
      {current ? (
        <p className="hint">
          Closes version {current.version} at the current instant and opens the next one. Blank fields keep their value.
        </p>
      ) : (
        <p className="hint">This fact has no open version, so the server will refuse a correction.</p>
      )}
      <div className="row">
        {CORRECTABLE.map(({ key, label }) => (
          <div className="field" key={key}>
            <label htmlFor={`correct-${key}`}>{label}</label>
            <input
              id={`correct-${key}`}
              type="number"
              step="any"
              placeholder={current ? String(current[key]) : undefined}
              value={raw[key] ?? ""}
              onChange={(e) => setRaw((r) => ({ ...r, [key]: e.target.value }))}
            />
          </div>
        ))}
      </div>
      <button type="submit" disabled={action.busy}>
        {action.busy ? "Booking..." : "Book correction"}
      </button>
      <ErrorMessage message={action.error} />
      {booked && (
        <Notice>
          Correction booked at {booked.restatedAsOfFrom}. Changed: {changed.length ? changed.join(", ") : "nothing"}.
        </Notice>
      )}
    </form>
  );
}

interface Belief {
  asOf: string;
  believedAt: string | null;
  past: VersionView[] | null;
  now: VersionView[];
}

/** "What did we believe at": the ledger as known at a past instant, next to what we know now. */
function BeliefPanel({ accountId }: { accountId: number }) {
  const [asOf, setAsOf] = useState(() => new Date().toISOString().slice(0, 10));
  const [believedLocal, setBelievedLocal] = useState("");
  const [belief, setBelief] = useState<Belief | null>(null);
  const action = useAction();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    let believedAt: string | null = null;
    if (believedLocal) {
      const d = new Date(believedLocal);
      if (Number.isNaN(d.getTime())) {
        action.setError("Believed at is not a valid date and time.");
        return;
      }
      believedAt = d.toISOString();
    }
    const r = await action.run(async () => {
      const [past, now] = await Promise.all([
        believedAt ? api.entries(accountId, asOf, believedAt) : Promise.resolve(null),
        api.entries(accountId, asOf),
      ]);
      return { asOf, believedAt, past, now };
    });
    if (r) setBelief(r);
  };

  return (
    <section className="card" aria-labelledby="belief-heading">
      <h2 id="belief-heading">What did we believe at</h2>
      <p className="hint">
        Entries effective on or before a date, as the ledger knew them at a past instant (your local time), next to
        what it knows now. A row with an "As of to" time was corrected later.
      </p>
      <form className="inline-form" onSubmit={submit}>
        <div className="field">
          <label htmlFor="belief-asof">Effective as of</label>
          <input id="belief-asof" type="date" required value={asOf} onChange={(e) => setAsOf(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="belief-at">Believed at</label>
          <input
            id="belief-at"
            type="datetime-local"
            step="1"
            value={believedLocal}
            onChange={(e) => setBelievedLocal(e.target.value)}
          />
        </div>
        <button type="submit" disabled={action.busy || !asOf}>
          {action.busy ? "Loading..." : "Show entries"}
        </button>
      </form>
      <ErrorMessage message={action.error} />
      {belief && (
        <div className="compare">
          {belief.past && (
            <VersionTable versions={belief.past} caption={`Believed at ${belief.believedAt}, effective by ${belief.asOf}`} />
          )}
          <VersionTable versions={belief.now} caption={`Believed now, effective by ${belief.asOf}`} />
        </div>
      )}
    </section>
  );
}
