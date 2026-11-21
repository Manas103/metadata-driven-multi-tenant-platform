import type {
  Account,
  Correction,
  FieldType,
  Health,
  NewRecord,
  ObjectDef,
  QueryResult,
  RecordValue,
  RestatementDiff,
  Tenant,
  User,
  VersionView,
} from "./types";

/** Any non-2xx answer (or unreachable server). `message` is the server's own explanation when it sent one. */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

/** Pulls the human message out of an error body: `{error}` from the DSL handler, `{message}` from Spring. */
function serverMessage(body: unknown, status: number, statusText: string): string {
  if (body && typeof body === "object") {
    const b = body as Record<string, unknown>;
    if (typeof b.error === "string" && b.error && typeof b.status !== "number") return b.error;
    if (typeof b.message === "string" && b.message) return b.message;
    if (typeof b.error === "string" && b.error) return `${status} ${b.error}`;
  }
  if (typeof body === "string" && body.trim()) return `${status}: ${body.trim().slice(0, 300)}`;
  return `${status} ${statusText || "request failed"}`.trim();
}

async function request<T>(url: string, method = "GET", payload?: unknown): Promise<T> {
  const init: RequestInit = { method, headers: { Accept: "application/json" } };
  if (payload !== undefined) {
    init.headers = { Accept: "application/json", "Content-Type": "application/json" };
    init.body = JSON.stringify(payload);
  }
  let res: Response;
  try {
    res = await fetch(url, init);
  } catch (e) {
    throw new ApiError(0, `Cannot reach the server (${e instanceof Error ? e.message : String(e)})`);
  }
  const text = await res.text();
  let body: unknown = text;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      // not JSON: keep the raw text
    }
  }
  if (!res.ok) throw new ApiError(res.status, serverMessage(body, res.status, res.statusText));
  return (text ? body : null) as T;
}

const seg = encodeURIComponent;
const admin = (tenantId: number) => `/api/admin/tenants/${tenantId}`;
const objectPath = (tenantId: number, objectName: string) => `${admin(tenantId)}/objects/${seg(objectName)}`;

export const api = {
  // admin
  tenants: () => request<Tenant[]>("/api/admin/tenants"),
  objects: (tenantId: number) => request<ObjectDef[]>(`${admin(tenantId)}/objects`),
  users: (tenantId: number) => request<User[]>(`${admin(tenantId)}/users`),
  createObject: (tenantId: number, name: string) =>
    request<ObjectDef>(`${admin(tenantId)}/objects`, "POST", { name }),
  addField: (tenantId: number, objectName: string, name: string, type: FieldType) =>
    request<ObjectDef>(`${objectPath(tenantId, objectName)}/fields`, "POST", { name, type }),
  createRecord: (tenantId: number, objectName: string, values: Record<string, RecordValue>, ownerUserId?: number) => {
    const body: NewRecord = { values };
    if (ownerUserId !== undefined) body.ownerUserId = ownerUserId;
    return request<Record<string, unknown>>(`${objectPath(tenantId, objectName)}/records`, "POST", body);
  },
  query: (tenantId: number, dsl: string, viewerUserId?: number) =>
    request<QueryResult>(
      `${admin(tenantId)}/query`,
      "POST",
      viewerUserId === undefined ? { dsl } : { dsl, viewerUserId },
    ),

  // book of record
  accounts: (limit = 50) => request<Account[]>(`/api/bookofrecord/accounts?limit=${limit}`),
  restatedFacts: (accountId: number) => request<string[]>(`/api/bookofrecord/accounts/${accountId}/restated-facts`),
  versions: (factId: string) => request<VersionView[]>(`/api/bookofrecord/facts/${seg(factId)}/versions`),
  restatement: (factId: string) => request<RestatementDiff>(`/api/bookofrecord/restatements/${seg(factId)}`),
  correct: (factId: string, correction: Correction) =>
    request<RestatementDiff>(`/api/bookofrecord/restatements/${seg(factId)}`, "POST", correction),
  entries: (accountId: number, asOf: string, believedAt?: string) => {
    const q = new URLSearchParams({ asOf });
    if (believedAt) q.set("believedAt", believedAt);
    return request<VersionView[]>(`/api/bookofrecord/accounts/${accountId}/entries?${q.toString()}`);
  },

  // ops
  health: () => request<Health>("/actuator/health"),
};

/** The latest restatement of a fact, or null when it has only one version (the server answers 404). */
export async function restatementOrNull(factId: string): Promise<RestatementDiff | null> {
  try {
    return await api.restatement(factId);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  }
}

export function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
