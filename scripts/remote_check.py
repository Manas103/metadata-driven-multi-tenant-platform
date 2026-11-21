"""End-to-end check of a running deployment over HTTP (standard library only).

    python scripts/remote_check.py http://HOST:8080 [probes]

1. Cross-tenant isolation through the public API: for each probe, take a value stored by
   one tenant and query another tenant for exactly that value. Every returned record must be
   one of the querying tenant's own records (checked by record id, so values that two
   tenants happen to share do not count as leaks).
2. Latency of the query endpoint: client-side round trip (includes the network) and the
   server's own elapsedMs, p50/p99.
3. The metadata write path: a new object and a field are metadata rows, a record written
   through the API is read back through the DSL.
4. The ledger: a correction is a new version, and the belief before it is still readable.
Prints one summary block; exits 1 if any check fails.
"""

import json
import random
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

BASE = sys.argv[1].rstrip("/")
PROBES = int(sys.argv[2]) if len(sys.argv) > 2 else 500
SHARED_OBJECTS = ("Contact", "Deal", "Task")


def call(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=30) as r:
        out = json.loads(r.read() or b"null")
    return out, (time.perf_counter() - t0) * 1000


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * p / 100))] if xs else 0.0


def main():
    failures = []
    tenants, _ = call("GET", "/api/admin/tenants")
    meta = [t for t in tenants if t["objectCount"] >= 4 and t["userCount"] == 0]
    print(f"target: {BASE}")
    print(f"tenants: {len(tenants)} ({len(meta)} metadata tenants with the shared objects)")

    # every tenant's own record ids and string field values, per shared object
    own_ids, values = {}, {}
    for t in meta:
        objs, _ = call("GET", f"/api/admin/tenants/{t['id']}/objects")
        for o in objs:
            if o["name"] not in SHARED_OBJECTS:
                continue
            fields = [f["name"] for f in o["fields"] if f["type"] == "STRING"]
            if not fields:
                continue
            f = fields[0]
            res, _ = call("POST", f"/api/admin/tenants/{t['id']}/query",
                          {"dsl": f"SELECT {f} FROM {o['name']} WHERE {f} != ''"})
            own_ids[(t["id"], o["name"])] = {r["record_id"] for r in res["rows"]}
            values[(t["id"], o["name"])] = (f, [r[f] for r in res["rows"] if r[f] and "'" not in r[f]])

    rng = random.Random(20261121)
    keys = [k for k in values if values[k][1]]
    leaks, rtt, server = 0, [], []
    for _ in range(PROBES):
        victim = rng.choice(keys)
        attackers = [t["id"] for t in meta if t["id"] != victim[0] and (t["id"], victim[1]) in own_ids]
        attacker = rng.choice(attackers)
        field, vals = values[victim]
        literal = rng.choice(vals)
        res, ms = call("POST", f"/api/admin/tenants/{attacker}/query",
                       {"dsl": f"SELECT {field} FROM {victim[1]} WHERE {field} = '{literal}'"})
        rtt.append(ms)
        server.append(res["elapsedMs"])
        leaks += sum(1 for r in res["rows"] if r["record_id"] not in own_ids[(attacker, victim[1])])
    print(f"cross-tenant probes: {PROBES}, foreign rows returned: {leaks}")
    if leaks:
        failures.append("cross-tenant leak")
    print(f"query latency, client round trip (n={len(rtt)}): p50 {pct(rtt, 50):.1f} ms, p99 {pct(rtt, 99):.1f} ms")
    print(f"query latency, server elapsedMs (n={len(server)}): p50 {pct(server, 50):.1f} ms, p99 {pct(server, 99):.1f} ms")

    # metadata write path
    t = meta[0]["id"]
    obj = f"Probe{rng.randrange(10**6)}"
    call("POST", f"/api/admin/tenants/{t}/objects", {"name": obj})
    call("POST", f"/api/admin/tenants/{t}/objects/{obj}/fields", {"name": "label", "type": "STRING"})
    call("POST", f"/api/admin/tenants/{t}/objects/{obj}/records", {"values": {"label": "written-over-http"}})
    call("POST", f"/api/admin/tenants/{t}/objects/{obj}/fields", {"name": "score", "type": "NUMBER"})
    call("POST", f"/api/admin/tenants/{t}/objects/{obj}/records", {"values": {"label": "second", "score": 5}})
    res, _ = call("POST", f"/api/admin/tenants/{t}/query", {"dsl": f"SELECT label, score FROM {obj} WHERE label != ''"})
    ok = [r["label"] for r in res["rows"]] == ["written-over-http", "second"] and res["rows"][0]["score"] is None
    print(f"metadata write path (new object, field added after a record, read back): {'ok' if ok else 'FAILED'}")
    if not ok:
        failures.append("write path")

    # ledger: correction and past belief
    accounts, _ = call("GET", "/api/bookofrecord/accounts?limit=1")
    acct = accounts[0]["id"]
    facts, _ = call("GET", f"/api/bookofrecord/accounts/{acct}/restated-facts")
    versions, _ = call("GET", f"/api/bookofrecord/facts/{facts[0]}/versions")
    target = facts[0]
    before = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    time.sleep(1.0)
    call("POST", f"/api/bookofrecord/restatements/{target}", {"quantity": 424242})
    after, _ = call("GET", f"/api/bookofrecord/facts/{target}/versions")
    then, _ = call("GET", f"/api/bookofrecord/accounts/{acct}/entries?asOf=2030-01-01&believedAt={before}")
    now, _ = call("GET", f"/api/bookofrecord/accounts/{acct}/entries?asOf=2030-01-01")
    ok = (len(after) == len(versions) + 1 and sum(1 for v in after if v["asOfTo"] is None) == 1
          and not any(float(v["quantity"]) == 424242 for v in then)
          and sum(1 for v in now if float(v["quantity"]) == 424242) == 1 and len(then) == len(now))
    print(f"ledger correction (versions {len(versions)} -> {len(after)}, past belief unchanged, current shows it): "
          f"{'ok' if ok else 'FAILED'}")
    if not ok:
        failures.append("ledger")

    print("RESULT: " + ("all checks passed" if not failures else "FAILED: " + ", ".join(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code} on {e.url}: {e.read().decode(errors='replace')}")
        sys.exit(1)
