import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryTab, columnsOf } from "../src/QueryTab";
import type { QueryResult, Tenant, User } from "../src/types";
import { callsTo, mockFetch, ok, type Reply } from "./mockFetch";

const TENANTS: Tenant[] = [
  { id: 1, name: "Acme", objectCount: 2, userCount: 2 },
  { id: 2, name: "Globex", objectCount: 1, userCount: 0 },
];
const USERS: User[] = [
  { id: 10, name: "Alice", role: "Manager" },
  { id: 11, name: "Bob", role: "Rep" },
];

const SQL = `SELECT r.id AS record_id, v1.string_value AS name
FROM records r
  LEFT JOIN field_values v1 ON v1.record_id = r.id AND v1.field_definition_id = ?
WHERE r.tenant_id = ?
  AND (v1.string_value <> ?)`;

const RESULT: QueryResult = {
  scoping: "tenant",
  sql: SQL,
  params: [7, 1, ""],
  rows: [
    { name: "Ada", record_id: 1 },
    { name: "Grace", record_id: 2, note: null },
  ],
  elapsedMs: 3.14159,
};

function backend(query: (body: unknown) => Reply) {
  return mockFetch((c) => {
    if (c.url === "/api/admin/tenants") return ok(TENANTS);
    if (c.url === "/api/admin/tenants/1/users") return ok(USERS);
    if (c.url === "/api/admin/tenants/2/users") return ok([]);
    if (c.method === "POST" && c.url === "/api/admin/tenants/1/query") return query(c.body);
    return undefined;
  });
}

async function ready() {
  render(<QueryTab />);
  await screen.findByLabelText("Tenant");
  await waitFor(() => expect(screen.getByLabelText("View as user")).toBeEnabled());
}

describe("QueryTab", () => {
  it("prefills a query that works on the demo data", async () => {
    backend(() => ok(RESULT));
    await ready();
    expect(screen.getByLabelText("DSL query")).toHaveValue("SELECT name FROM Contact WHERE name != ''");
  });

  it("runs the DSL and shows scoping, compiled SQL with whitespace, params, elapsed and rows (record_id first)", async () => {
    const calls = backend(() => ok(RESULT));
    await ready();

    fireEvent.click(screen.getByRole("button", { name: "Run query" }));

    const sql = await screen.findByLabelText("Compiled SQL");
    expect(sql.tagName).toBe("PRE");
    expect(sql.textContent).toBe(SQL);
    expect(screen.getByTestId("scoping")).toHaveTextContent("tenant: tenant filter only");
    expect(screen.getByTestId("elapsed")).toHaveTextContent("3.14 ms");
    expect(within(screen.getByLabelText("SQL params")).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "7",
      "1",
      '""',
    ]);

    const table = screen.getByRole("table", { name: "Query rows" });
    expect(within(table).getAllByRole("columnheader").map((th) => th.textContent)).toEqual([
      "record_id",
      "name",
      "note",
    ]);
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows.map((r) => within(r).getAllByRole("cell").map((td) => td.textContent))).toEqual([
      ["1", "Ada", "null"],
      ["2", "Grace", "null"],
    ]);

    expect(callsTo(calls, "POST", "/api/admin/tenants/1/query")[0].body).toEqual({
      dsl: "SELECT name FROM Contact WHERE name != ''",
    });
  });

  it("shows the DSL error message from a 400 {error} body", async () => {
    backend(() => ({ status: 400, body: { error: "expected WHERE at position 25" } }));
    await ready();

    fireEvent.change(screen.getByLabelText("DSL query"), { target: { value: "SELECT name FROM Contact" } });
    fireEvent.click(screen.getByRole("button", { name: "Run query" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("expected WHERE at position 25");
    expect(screen.queryByLabelText("Compiled SQL")).not.toBeInTheDocument();
  });

  it("shows the message of a Spring error body for other 4xx answers", async () => {
    backend(() => ({
      status: 400,
      body: { timestamp: "t", status: 400, error: "Bad Request", message: "viewer 99 is not a user of this tenant" },
    }));
    await ready();

    fireEvent.click(screen.getByRole("button", { name: "Run query" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("viewer 99 is not a user of this tenant");
  });

  it("sends viewerUserId when viewing as a user and shows the sharing scope", async () => {
    const calls = backend((body) =>
      ok({ ...RESULT, scoping: (body as { viewerUserId?: number }).viewerUserId ? "tenant+sharing" : "tenant" }),
    );
    await ready();

    fireEvent.change(screen.getByLabelText("View as user"), { target: { value: "11" } });
    fireEvent.click(screen.getByRole("button", { name: "Run query" }));

    await waitFor(() => expect(screen.getByTestId("scoping")).toHaveTextContent("tenant+sharing"));
    expect(screen.getByTestId("scoping")).toHaveTextContent("for Bob");
    expect(callsTo(calls, "POST", "/api/admin/tenants/1/query")[0].body).toEqual({
      dsl: "SELECT name FROM Contact WHERE name != ''",
      viewerUserId: 11,
    });

    fireEvent.change(screen.getByLabelText("View as user"), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Run query" }));
    await waitFor(() => expect(screen.getByTestId("scoping")).toHaveTextContent("tenant: tenant filter only"));
    expect(callsTo(calls, "POST", "/api/admin/tenants/1/query")[1].body).not.toHaveProperty("viewerUserId");
  });

  it("disables the viewer picker for a tenant without users", async () => {
    backend(() => ok(RESULT));
    await ready();
    fireEvent.change(screen.getByLabelText("Tenant"), { target: { value: "2" } });
    await waitFor(() => expect(screen.getByLabelText("View as user")).toBeDisabled());
  });
});

describe("columnsOf", () => {
  it("unions keys across rows and puts record_id first", () => {
    expect(columnsOf([{ a: 1, record_id: 5 }, { b: 2 }])).toEqual(["record_id", "a", "b"]);
    expect(columnsOf([])).toEqual([]);
  });
});
