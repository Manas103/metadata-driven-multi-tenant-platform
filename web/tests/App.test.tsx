import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { App } from "../src/App";
import { mockFetch, ok } from "./mockFetch";

function backend(health: "UP" | "down") {
  return mockFetch((c) => {
    if (c.url === "/actuator/health") return health === "UP" ? ok({ status: "UP" }) : undefined;
    if (c.url === "/api/admin/tenants") return ok([{ id: 1, name: "Acme", objectCount: 0, userCount: 0 }]);
    if (c.url === "/api/admin/tenants/1/objects") return ok([]);
    if (c.url === "/api/admin/tenants/1/users") return ok([]);
    if (c.url === "/api/bookofrecord/accounts?limit=50") return ok([]);
    return undefined;
  });
}

describe("App", () => {
  it("opens on Schema and switches tabs without a router", async () => {
    backend("UP");
    render(<App />);

    expect(screen.getByRole("tab", { name: "Schema" })).toHaveAttribute("aria-selected", "true");
    expect(await screen.findByText("This tenant has no objects yet.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "Query" }));
    expect(screen.getByRole("tab", { name: "Query" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tabpanel")).toHaveAttribute("aria-labelledby", "tab-query");
    expect(await screen.findByLabelText("DSL query")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("tab", { name: "Ledger" }));
    expect(await screen.findByText("No fund accounts yet.")).toBeInTheDocument();
  });

  it("moves between tabs with the arrow keys", async () => {
    backend("UP");
    render(<App />);
    fireEvent.keyDown(screen.getByRole("tablist"), { key: "ArrowLeft" });
    expect(screen.getByRole("tab", { name: "Ledger" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "Ledger" })).toHaveFocus();
    await screen.findByText("No fund accounts yet.");
  });

  it("shows whether the backend is up", async () => {
    backend("UP");
    render(<App />);
    expect(await screen.findByText("Backend: UP")).toBeInTheDocument();
  });

  it("says when the backend is unreachable", async () => {
    backend("down");
    render(<App />);
    expect(await screen.findByText("Backend: unreachable")).toBeInTheDocument();
  });
});
