import { useRef, useState, type KeyboardEvent } from "react";
import { api } from "./api";
import { LedgerTab } from "./LedgerTab";
import { QueryTab } from "./QueryTab";
import { SchemaTab } from "./SchemaTab";
import { useLoad } from "./ui";

const TABS = [
  { id: "schema", label: "Schema", render: () => <SchemaTab /> },
  { id: "query", label: "Query", render: () => <QueryTab /> },
  { id: "ledger", label: "Ledger", render: () => <LedgerTab /> },
] as const;

type TabId = (typeof TABS)[number]["id"];

/** The admin console: three tabs over the Spring Boot backend, plain state instead of a router. */
export function App() {
  const [active, setActive] = useState<TabId>("schema");
  const buttons = useRef<Record<string, HTMLButtonElement | null>>({});

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const i = TABS.findIndex((t) => t.id === active);
    let next = -1;
    if (e.key === "ArrowRight") next = (i + 1) % TABS.length;
    else if (e.key === "ArrowLeft") next = (i - 1 + TABS.length) % TABS.length;
    else if (e.key === "Home") next = 0;
    else if (e.key === "End") next = TABS.length - 1;
    if (next < 0) return;
    e.preventDefault();
    setActive(TABS[next].id);
    buttons.current[TABS[next].id]?.focus();
  };

  const tab = TABS.find((t) => t.id === active)!;

  return (
    <div className="app">
      <header className="topbar">
        <div>
          <h1>Metaplatform admin console</h1>
          <p className="muted">Metadata-driven multi-tenant platform and bitemporal book of record.</p>
        </div>
        <BackendStatus />
      </header>
      <div className="tabs" role="tablist" aria-label="Console sections" onKeyDown={onKeyDown}>
        {TABS.map((t) => (
          <button
            key={t.id}
            ref={(el) => {
              buttons.current[t.id] = el;
            }}
            id={`tab-${t.id}`}
            type="button"
            role="tab"
            aria-selected={t.id === active}
            aria-controls={`panel-${t.id}`}
            tabIndex={t.id === active ? 0 : -1}
            onClick={() => setActive(t.id)}
          >
            {t.label}
          </button>
        ))}
      </div>
      <main id={`panel-${tab.id}`} role="tabpanel" aria-labelledby={`tab-${tab.id}`}>
        {tab.render()}
      </main>
    </div>
  );
}

function BackendStatus() {
  const health = useLoad(() => api.health(), []);
  const status = health.loading ? "checking" : health.data?.status ?? "unreachable";
  const tone = status === "UP" ? "ok" : status === "checking" ? "wait" : "bad";
  return (
    <p className={`health ${tone}`} role="status" title={health.error ?? undefined}>
      Backend: {status}
    </p>
  );
}
