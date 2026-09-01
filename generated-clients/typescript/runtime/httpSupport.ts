// HAND-WRITTEN, not generated. The one shared HTTP + query-string helper every generated
// <Type>Client calls into, mirroring TypeClientSupport.java on the Java side field for
// field: same three operations (POST create, GET one, GET list with equality filters), same
// division of labor between generated per-type code and one small shared runtime.

export async function httpPost<T>(url: string, body: unknown): Promise<T> {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`POST ${url} returned HTTP ${res.status}: ${text}`);
  }
  return (await res.json()) as T;
}

export async function httpGet<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`GET ${url} returned HTTP ${res.status}: ${text}`);
  }
  return (await res.json()) as T;
}

export async function httpGetArray<T>(url: string): Promise<T[]> {
  return httpGet<T[]>(url);
}

export function buildFilterQueryString(filters: Record<string, unknown> | undefined | null): string {
  const keys = Object.keys(filters ?? {});
  if (keys.length === 0) {
    return "";
  }
  const params = keys.map((k) => {
    const value = (filters as Record<string, unknown>)[k];
    return `${encodeURIComponent(k)}=${encodeURIComponent(String(value))}`;
  });
  return "?" + params.join("&");
}
