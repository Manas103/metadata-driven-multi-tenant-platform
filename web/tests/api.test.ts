import { describe, expect, it, vi } from "vitest";
import { ApiError, api, restatementOrNull } from "../src/api";
import { mockFetch } from "./mockFetch";

describe("api client", () => {
  it("encodes path segments and builds the entries query string", async () => {
    const calls = mockFetch(() => ({ body: [] }));
    await api.addField(3, "My Obj", "f", "NUMBER");
    await api.entries(4, "2026-09-30", "2026-08-01T12:00:00.000Z");
    await api.entries(4, "2026-09-30");
    expect(calls.map((c) => c.url)).toEqual([
      "/api/admin/tenants/3/objects/My%20Obj/fields",
      "/api/bookofrecord/accounts/4/entries?asOf=2026-09-30&believedAt=2026-08-01T12%3A00%3A00.000Z",
      "/api/bookofrecord/accounts/4/entries?asOf=2026-09-30",
    ]);
    expect(calls[0]).toMatchObject({ method: "POST", body: { name: "f", type: "NUMBER" } });
  });

  it("prefers {error} from the DSL handler and {message} from Spring errors", async () => {
    mockFetch(() => ({ status: 400, body: { error: "unknown field foo" } }));
    await expect(api.query(1, "x")).rejects.toMatchObject({ status: 400, message: "unknown field foo" });

    mockFetch(() => ({ status: 404, body: { status: 404, error: "Not Found", message: "no tenant 9" } }));
    await expect(api.objects(9)).rejects.toMatchObject({ status: 404, message: "no tenant 9" });

    mockFetch(() => ({ status: 500, body: { status: 500, error: "Internal Server Error", message: "" } }));
    await expect(api.tenants()).rejects.toMatchObject({ message: "500 Internal Server Error" });

    mockFetch(() => ({ status: 502, body: "Bad gateway from proxy" }));
    await expect(api.tenants()).rejects.toMatchObject({ message: "502: Bad gateway from proxy" });
  });

  it("wraps a network failure in an ApiError", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => Promise.reject(new TypeError("Failed to fetch"))));
    const err = await api.tenants().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).message).toContain("Cannot reach the server");
  });

  it("restatementOrNull maps 404 to null and rethrows anything else", async () => {
    mockFetch(() => ({ status: 404, body: { status: 404, message: "never restated" } }));
    await expect(restatementOrNull("f")).resolves.toBeNull();
    mockFetch(() => ({ status: 500, body: { status: 500, message: "boom" } }));
    await expect(restatementOrNull("f")).rejects.toThrow("boom");
  });
});
