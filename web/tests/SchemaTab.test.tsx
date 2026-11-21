import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { SchemaTab } from "../src/SchemaTab";
import type { ObjectDef, Tenant, User } from "../src/types";
import { callsTo, mockFetch, ok, type Call } from "./mockFetch";

const TENANTS: Tenant[] = [
  { id: 1, name: "Acme", objectCount: 2, userCount: 2 },
  { id: 2, name: "Globex", objectCount: 1, userCount: 0 },
];

function acmeObjects(): ObjectDef[] {
  return [
    {
      id: 100,
      name: "Contact",
      fields: [
        { id: 1, name: "name", type: "STRING" },
        { id: 2, name: "age", type: "NUMBER" },
      ],
      recordCount: 3,
    },
    {
      id: 101,
      name: "Deal",
      fields: [
        { id: 3, name: "amount", type: "NUMBER" },
        { id: 4, name: "closed", type: "BOOLEAN" },
      ],
      recordCount: 1,
    },
  ];
}

const GLOBEX_OBJECTS: ObjectDef[] = [{ id: 200, name: "Ticket", fields: [], recordCount: 0 }];

const ACME_USERS: User[] = [
  { id: 10, name: "Alice", role: "Manager" },
  { id: 11, name: "Bob", role: "Rep" },
];

/** A tiny in-memory backend: field and record POSTs change what the next GET returns. */
function backend(extra?: (c: Call) => ReturnType<Parameters<typeof mockFetch>[0]>) {
  const acme = acmeObjects();
  return mockFetch((c) => {
    const custom = extra?.(c);
    if (custom) return custom;
    if (c.method === "GET" && c.url === "/api/admin/tenants") return ok(TENANTS);
    if (c.method === "GET" && c.url === "/api/admin/tenants/1/objects") return ok(acme);
    if (c.method === "GET" && c.url === "/api/admin/tenants/2/objects") return ok(GLOBEX_OBJECTS);
    if (c.method === "GET" && c.url === "/api/admin/tenants/1/users") return ok(ACME_USERS);
    if (c.method === "GET" && c.url === "/api/admin/tenants/2/users") return ok([]);
    const field = c.url.match(/^\/api\/admin\/tenants\/1\/objects\/(\w+)\/fields$/);
    if (c.method === "POST" && field) {
      const o = acme.find((x) => x.name === field[1])!;
      const b = c.body as { name: string; type: "STRING" };
      o.fields.push({ id: 50 + o.fields.length, name: b.name, type: b.type });
      return ok(o);
    }
    const rec = c.url.match(/^\/api\/admin\/tenants\/1\/objects\/(\w+)\/records$/);
    if (c.method === "POST" && rec) {
      const o = acme.find((x) => x.name === rec[1])!;
      o.recordCount += 1;
      return ok({ record_id: 999, ...(c.body as { values: object }).values });
    }
    if (c.method === "POST" && c.url === "/api/admin/tenants/1/objects") {
      const name = (c.body as { name: string }).name;
      acme.push({ id: 102, name, fields: [], recordCount: 0 });
      return ok(acme[acme.length - 1]);
    }
    return undefined;
  });
}

describe("SchemaTab", () => {
  it("loads tenants, selects the first and renders its objects with fields and record counts", async () => {
    backend();
    render(<SchemaTab />);

    const tenant = await screen.findByLabelText("Tenant");
    expect(within(tenant).getAllByRole("option").map((o) => o.textContent)).toEqual([
      "Acme (#1, 2 objects, 2 users)",
      "Globex (#2, 1 objects, 0 users)",
    ]);

    const contact = await screen.findByTestId("object-Contact");
    expect(contact).toHaveTextContent("3 records");
    expect(within(contact).getByText("name")).toBeInTheDocument();
    expect(within(contact).getByText("age")).toBeInTheDocument();
    expect(contact).toHaveTextContent("NUMBER");
    expect(screen.getByTestId("object-Deal")).toHaveTextContent("1 record");
    expect(screen.getByText(/not an ALTER TABLE/)).toBeInTheDocument();
  });

  it("switching tenant loads that tenant's objects and hides the owner picker when it has no users", async () => {
    const calls = backend();
    render(<SchemaTab />);
    await screen.findByTestId("object-Contact");
    expect(screen.getByLabelText("Owner")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Tenant"), { target: { value: "2" } });

    expect(await screen.findByTestId("object-Ticket")).toHaveTextContent("0 records");
    expect(screen.queryByTestId("object-Contact")).not.toBeInTheDocument();
    expect(callsTo(calls, "GET", "/api/admin/tenants/2/objects")).toHaveLength(1);
    await waitFor(() => expect(screen.queryByLabelText("Owner")).not.toBeInTheDocument());
  });

  it("adding a field POSTs {name, type} to the chosen object and refreshes the object list", async () => {
    const calls = backend();
    render(<SchemaTab />);
    await screen.findByTestId("object-Contact");
    const objectGets = callsTo(calls, "GET", "/api/admin/tenants/1/objects").length;

    fireEvent.change(screen.getByLabelText("Object for new field"), { target: { value: "Deal" } });
    fireEvent.change(screen.getByLabelText("Field name"), { target: { value: "stage" } });
    fireEvent.change(screen.getByLabelText("Field type"), { target: { value: "BOOLEAN" } });
    fireEvent.click(screen.getByRole("button", { name: "Add field" }));

    expect(await screen.findByText("Added BOOLEAN field stage to Deal.")).toBeInTheDocument();
    const posts = callsTo(calls, "POST", "/api/admin/tenants/1/objects/Deal/fields");
    expect(posts).toHaveLength(1);
    expect(posts[0].body).toEqual({ name: "stage", type: "BOOLEAN" });

    await waitFor(() =>
      expect(callsTo(calls, "GET", "/api/admin/tenants/1/objects").length).toBeGreaterThan(objectGets),
    );
    expect(await within(screen.getByTestId("object-Deal")).findByText("stage")).toBeInTheDocument();
    expect(screen.getByLabelText("Field name")).toHaveValue("");
  });

  it("creating a record sends typed values and the chosen owner, then refreshes the count", async () => {
    const calls = backend();
    render(<SchemaTab />);
    await screen.findByTestId("object-Contact");

    fireEvent.change(screen.getByLabelText("Object for new record"), { target: { value: "Deal" } });
    fireEvent.change(screen.getByLabelText("amount (number)"), { target: { value: "1250.5" } });
    fireEvent.change(screen.getByLabelText("closed (boolean)"), { target: { value: "true" } });
    fireEvent.change(screen.getByLabelText("Owner"), { target: { value: "11" } });
    fireEvent.click(screen.getByRole("button", { name: "Create record" }));

    expect(await screen.findByText("Created Deal record #999.")).toBeInTheDocument();
    const posts = callsTo(calls, "POST", "/api/admin/tenants/1/objects/Deal/records");
    expect(posts[0].body).toEqual({ values: { amount: 1250.5, closed: true }, ownerUserId: 11 });
    await waitFor(() => expect(screen.getByTestId("object-Deal")).toHaveTextContent("2 records"));
  });

  it("a record without an owner omits ownerUserId and skips blank values", async () => {
    const calls = backend();
    render(<SchemaTab />);
    await screen.findByTestId("object-Contact");

    fireEvent.change(screen.getByLabelText("name (string)"), { target: { value: "Ada" } });
    fireEvent.click(screen.getByRole("button", { name: "Create record" }));

    await screen.findByText("Created Contact record #999.");
    expect(callsTo(calls, "POST", "/api/admin/tenants/1/objects/Contact/records")[0].body).toEqual({
      values: { name: "Ada" },
    });
  });

  it("creating an object POSTs its name and shows it after the refresh", async () => {
    const calls = backend();
    render(<SchemaTab />);
    await screen.findByTestId("object-Contact");

    fireEvent.change(screen.getByLabelText("Object name"), { target: { value: "Invoice" } });
    fireEvent.click(screen.getByRole("button", { name: "Create object" }));

    expect(await screen.findByTestId("object-Invoice")).toHaveTextContent("0 records");
    expect(callsTo(calls, "POST", "/api/admin/tenants/1/objects")[0].body).toEqual({ name: "Invoice" });
  });

  it("shows the server's message when a mutation is refused", async () => {
    backend((c) =>
      c.method === "POST" && c.url.endsWith("/fields")
        ? {
            status: 409,
            body: { timestamp: "x", status: 409, error: "Conflict", message: "field name already exists", path: "/" },
          }
        : undefined,
    );
    render(<SchemaTab />);
    await screen.findByTestId("object-Contact");

    fireEvent.change(screen.getByLabelText("Field name"), { target: { value: "name" } });
    fireEvent.click(screen.getByRole("button", { name: "Add field" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("field name already exists");
  });

  it("shows a visible error when tenants cannot be loaded", async () => {
    mockFetch(() => ({ status: 500, body: { status: 500, error: "Internal Server Error", message: "db down" } }));
    render(<SchemaTab />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading tenants");
    expect(await screen.findByRole("alert")).toHaveTextContent("db down");
  });
});
