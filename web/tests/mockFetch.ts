import { vi } from "vitest";

export interface Call {
  method: string;
  url: string;
  body: unknown;
}

export interface Reply {
  status?: number;
  body?: unknown;
}

/** Returns a reply for a request, or undefined to answer 404 "unmocked". */
export type Handler = (call: Call) => Reply | undefined;

function response(status: number, body: unknown): Response {
  const text = body === undefined ? "" : typeof body === "string" ? body : JSON.stringify(body);
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 404 ? "Not Found" : status === 400 ? "Bad Request" : "",
    text: async () => text,
  } as Response;
}

/** Replaces global fetch with a router over `handler`, recording every call. */
export function mockFetch(handler: Handler): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const call: Call = {
        method: init?.method ?? "GET",
        url: String(input),
        body: init?.body ? JSON.parse(String(init.body)) : undefined,
      };
      calls.push(call);
      const reply = handler(call);
      if (!reply) return response(404, { status: 404, error: "Not Found", message: `unmocked ${call.method} ${call.url}` });
      return response(reply.status ?? 200, reply.body);
    }),
  );
  return calls;
}

export const ok = (body: unknown): Reply => ({ status: 200, body });

export function callsTo(calls: Call[], method: string, url: string): Call[] {
  return calls.filter((c) => c.method === method && c.url === url);
}
