import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { LedgerTab } from "../src/LedgerTab";
import restatements from "../src/fixtures/restatements.json";
import type { RestatementDiff, VersionView } from "../src/types";
import { callsTo, mockFetch, ok } from "./mockFetch";

/** Real diffs computed by the backend's RestatementDiff.of (see ConsoleFixtureExporterTest). */
const DIFFS = restatements as RestatementDiff[];
const NVDA = DIFFS.find((d) => d.factId === "fact-console-1")!;

function nvdaVersions(): VersionView[] {
  return [
    {
      version: 1,
      symbol: "NVDA",
      effectiveDate: "2026-07-14",
      entryType: "TRADE",
      quantity: 240,
      costBasis: 36000,
      realizedPnl: 0,
      asOfFrom: "2026-07-15T00:00:00Z",
      asOfTo: "2026-08-02T00:00:00Z",
    },
    {
      version: 2,
      symbol: "NVDA",
      effectiveDate: "2026-07-14",
      entryType: "TRADE",
      quantity: 260,
      costBasis: 39000,
      realizedPnl: 1500,
      asOfFrom: "2026-08-02T00:00:00Z",
      asOfTo: null,
    },
  ];
}

const CORRECTED: RestatementDiff = {
  ...NVDA,
  priorAsOfFrom: "2026-08-02T00:00:00Z",
  restatedAsOfFrom: "2026-11-21T10:00:00Z",
  fields: [
    { fieldName: "quantity", priorValue: "260.000000", restatedValue: "275.000000", changed: true },
    { fieldName: "costBasis", priorValue: "39000.000000", restatedValue: "39000.000000", changed: false },
    { fieldName: "realizedPnl", priorValue: "1500.000000", restatedValue: "1500.000000", changed: false },
    { fieldName: "effectiveDate", priorValue: "2026-07-14", restatedValue: "2026-07-14", changed: false },
    { fieldName: "entryType", priorValue: "TRADE", restatedValue: "TRADE", changed: false },
  ],
};

function backend() {
  const versions = nvdaVersions();
  let diff: RestatementDiff = NVDA;
  return mockFetch((c) => {
    const url = new URL(c.url, "http://console");
    if (c.url === "/api/bookofrecord/accounts?limit=50")
      return ok([
        { id: 1, name: "Growth Fund" },
        { id: 2, name: "Income Fund" },
      ]);
    if (c.url === "/api/bookofrecord/accounts/1/restated-facts") return ok(["fact-console-1", "fact-console-2"]);
    if (c.url === "/api/bookofrecord/accounts/2/restated-facts") return ok([]);
    if (c.url === "/api/bookofrecord/facts/fact-console-1/versions") return ok(versions);
    if (c.url === "/api/bookofrecord/facts/fact-solo/versions") return ok([{ ...versions[0], asOfTo: null }]);
    if (c.method === "GET" && c.url === "/api/bookofrecord/restatements/fact-console-1") return ok(diff);
    if (c.method === "GET" && c.url === "/api/bookofrecord/restatements/fact-solo")
      return { status: 404, body: { status: 404, error: "Not Found", message: "fact fact-solo was never restated" } };
    if (c.method === "POST" && c.url === "/api/bookofrecord/restatements/fact-console-1") {
      const open = versions[versions.length - 1];
      const now = "2026-11-21T10:00:00Z";
      open.asOfTo = now;
      versions.push({ ...open, version: open.version + 1, quantity: 275, asOfFrom: now, asOfTo: null });
      diff = CORRECTED;
      return ok(CORRECTED);
    }
    if (url.pathname === "/api/bookofrecord/accounts/1/entries") {
      const past = url.searchParams.has("believedAt");
      return ok([past ? nvdaVersions()[0] : nvdaVersions()[1]]);
    }
    return undefined;
  });
}

async function openNvda() {
  render(<LedgerTab />);
  const facts = await screen.findByRole("list", { name: "Restated facts" });
  fireEvent.click(within(facts).getByRole("button", { name: "fact-console-1" }));
  return screen.findByRole("table", { name: "Version history" });
}

describe("LedgerTab", () => {
  it("lists accounts and the selected account's restated facts", async () => {
    backend();
    render(<LedgerTab />);
    const account = await screen.findByLabelText("Account");
    expect(within(account).getAllByRole("option").map((o) => o.textContent)).toEqual([
      "Growth Fund (#1)",
      "Income Fund (#2)",
    ]);
    const facts = await screen.findByRole("list", { name: "Restated facts" });
    expect(within(facts).getAllByRole("button").map((b) => b.textContent)).toEqual([
      "fact-console-1",
      "fact-console-2",
    ]);

    fireEvent.change(account, { target: { value: "2" } });
    expect(await screen.findByText("No fact in this account was ever corrected.")).toBeInTheDocument();
  });

  it("selecting a fact shows its version history and the latest diff", async () => {
    backend();
    const history = await openNvda();

    const rows = within(history).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("240");
    expect(rows[0]).toHaveTextContent("2026-08-02T00:00:00Z");
    expect(rows[1]).toHaveTextContent("260");
    expect(within(rows[1]).getByText("open")).toBeInTheDocument();

    await screen.findByTestId("restatement-fact-console-1");
    for (const f of NVDA.fields) {
      const row = screen.getByTestId(`field-fact-console-1-${f.fieldName}`);
      expect(row).toHaveTextContent(f.priorValue);
      expect(row).toHaveTextContent(f.restatedValue);
      expect(row).toHaveTextContent(f.changed ? "yes" : "no");
    }
    expect(screen.getByRole("button", { name: "fact-console-1" })).toHaveAttribute("aria-pressed", "true");
  });

  it("booking a correction POSTs only the filled fields and refreshes history and diff", async () => {
    const calls = backend();
    await openNvda();
    await screen.findByTestId("restatement-fact-console-1");

    fireEvent.change(screen.getByLabelText("Corrected quantity"), { target: { value: "275" } });
    fireEvent.click(screen.getByRole("button", { name: "Book correction" }));

    expect(await screen.findByText(/Correction booked at 2026-11-21T10:00:00Z\. Changed: quantity\./)).toBeInTheDocument();
    expect(callsTo(calls, "POST", "/api/bookofrecord/restatements/fact-console-1")[0].body).toEqual({ quantity: 275 });

    await waitFor(() =>
      expect(within(screen.getByRole("table", { name: "Version history" })).getAllByRole("row")).toHaveLength(4),
    );
    await waitFor(() =>
      expect(screen.getByTestId("field-fact-console-1-quantity")).toHaveTextContent("275.000000"),
    );
    expect(callsTo(calls, "GET", "/api/bookofrecord/facts/fact-console-1/versions")).toHaveLength(2);
    expect(screen.getByLabelText("Corrected quantity")).toHaveValue(null);
  });

  it("refuses an empty correction without calling the server", async () => {
    const calls = backend();
    await openNvda();
    fireEvent.click(screen.getByRole("button", { name: "Book correction" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Enter at least one corrected value.");
    expect(callsTo(calls, "POST", "/api/bookofrecord/restatements/fact-console-1")).toHaveLength(0);
  });

  it("a fact opened by id that was never restated says so instead of erroring", async () => {
    backend();
    render(<LedgerTab />);
    await screen.findByRole("list", { name: "Restated facts" });
    fireEvent.change(screen.getByLabelText("Open any fact by id"), { target: { value: "fact-solo" } });
    fireEvent.click(screen.getByRole("button", { name: "Open fact" }));

    expect(await screen.findByText("This fact has a single version: it was never restated.")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows what the ledger believed at a past instant next to what it believes now", async () => {
    const calls = backend();
    render(<LedgerTab />);
    await screen.findByLabelText("Account");

    fireEvent.change(screen.getByLabelText("Effective as of"), { target: { value: "2026-09-30" } });
    fireEvent.change(screen.getByLabelText("Believed at"), { target: { value: "2026-08-01T12:00:00" } });
    fireEvent.click(screen.getByRole("button", { name: "Show entries" }));

    const believedAt = new Date("2026-08-01T12:00:00").toISOString();
    const past = await screen.findByRole("table", { name: `Believed at ${believedAt}, effective by 2026-09-30` });
    const now = screen.getByRole("table", { name: "Believed now, effective by 2026-09-30" });
    expect(within(past).getAllByRole("row")[1]).toHaveTextContent("240");
    expect(within(now).getAllByRole("row")[1]).toHaveTextContent("260");

    const urls = calls.filter((c) => c.url.includes("/entries")).map((c) => new URL(c.url, "http://console"));
    expect(urls.map((u) => Object.fromEntries(u.searchParams))).toEqual(
      expect.arrayContaining([{ asOf: "2026-09-30", believedAt }, { asOf: "2026-09-30" }]),
    );
  });
});
