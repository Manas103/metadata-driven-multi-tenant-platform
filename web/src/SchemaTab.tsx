import { useState, type FormEvent } from "react";
import { api } from "./api";
import { ErrorMessage, Loading, Notice, TenantSelect, useAction, useLoad } from "./ui";
import { FIELD_TYPES, type FieldType, type ObjectDef, type RecordValue, type User } from "./types";

/** Tenants, their metadata-defined objects and fields, and forms that add to them. */
export function SchemaTab() {
  const tenants = useLoad(() => api.tenants(), []);
  const [picked, setPicked] = useState<number | null>(null);
  const tenantId =
    tenants.data?.find((t) => t.id === picked)?.id ?? tenants.data?.[0]?.id ?? null;

  const objects = useLoad(tenantId === null ? null : () => api.objects(tenantId), [tenantId]);
  const users = useLoad(tenantId === null ? null : () => api.users(tenantId), [tenantId]);

  const refresh = () => {
    objects.reload();
    tenants.reload();
  };

  return (
    <div className="stack">
      {tenants.loading && !tenants.data && <Loading what="tenants" />}
      <ErrorMessage message={tenants.error} />
      {tenants.data && tenants.data.length === 0 && <p className="muted">No tenants yet.</p>}
      {tenants.data && tenants.data.length > 0 && (
        <TenantSelect id="schema-tenant" tenants={tenants.data} value={tenantId} onChange={setPicked} />
      )}

      {tenantId !== null && (
        <>
          <section className="card" aria-labelledby="objects-heading">
            <h2 id="objects-heading">Objects</h2>
            {objects.loading && <Loading what="objects" />}
            <ErrorMessage message={objects.error} />
            {objects.data && objects.data.length === 0 && <p className="muted">This tenant has no objects yet.</p>}
            <div className="object-grid">
              {objects.data?.map((o) => <ObjectCard key={o.id} object={o} />)}
            </div>
          </section>

          <div className="form-grid">
            <CreateObjectForm key={`o${tenantId}`} tenantId={tenantId} onDone={refresh} />
            <AddFieldForm key={`f${tenantId}`} tenantId={tenantId} objects={objects.data ?? []} onDone={refresh} />
            <CreateRecordForm
              key={`r${tenantId}`}
              tenantId={tenantId}
              objects={objects.data ?? []}
              users={users.data ?? []}
              onDone={refresh}
            />
          </div>
        </>
      )}
    </div>
  );
}

function ObjectCard({ object }: { object: ObjectDef }) {
  return (
    <article className="object-card" data-testid={`object-${object.name}`}>
      <header>
        <h3>{object.name}</h3>
        <span className="badge">
          {object.recordCount} {object.recordCount === 1 ? "record" : "records"}
        </span>
      </header>
      {object.fields.length === 0 ? (
        <p className="muted">No fields.</p>
      ) : (
        <ul className="field-list">
          {object.fields.map((f) => (
            <li key={f.id}>
              <code>{f.name}</code> <span className="type">{f.type}</span>
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}

/** Picks a valid object name: the chosen one if it still exists, else the first. */
function pickObject(objects: ObjectDef[], chosen: string): ObjectDef | undefined {
  return objects.find((o) => o.name === chosen) ?? objects[0];
}

function CreateObjectForm({ tenantId, onDone }: { tenantId: number; onDone: () => void }) {
  const [name, setName] = useState("");
  const [done, setDone] = useState<string | null>(null);
  const action = useAction();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setDone(null);
    const created = await action.run(() => api.createObject(tenantId, name.trim()));
    if (created) {
      setDone(`Created object ${created.name}.`);
      setName("");
      onDone();
    }
  };

  return (
    <form className="card" onSubmit={submit} aria-labelledby="create-object-heading">
      <h2 id="create-object-heading">Create object</h2>
      <p className="hint">A new object is one metadata row, not a CREATE TABLE.</p>
      <div className="field">
        <label htmlFor="new-object-name">Object name</label>
        <input id="new-object-name" value={name} required onChange={(e) => setName(e.target.value)} />
      </div>
      <button type="submit" disabled={action.busy || !name.trim()}>
        {action.busy ? "Creating..." : "Create object"}
      </button>
      <ErrorMessage message={action.error} />
      {done && <Notice>{done}</Notice>}
    </form>
  );
}

function AddFieldForm({ tenantId, objects, onDone }: { tenantId: number; objects: ObjectDef[]; onDone: () => void }) {
  const [chosen, setChosen] = useState("");
  const [name, setName] = useState("");
  const [type, setType] = useState<FieldType>("STRING");
  const [done, setDone] = useState<string | null>(null);
  const action = useAction();
  const target = pickObject(objects, chosen);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!target) return;
    setDone(null);
    const updated = await action.run(() => api.addField(tenantId, target.name, name.trim(), type));
    if (updated) {
      setDone(`Added ${type} field ${name.trim()} to ${updated.name}.`);
      setName("");
      onDone();
    }
  };

  return (
    <form className="card" onSubmit={submit} aria-labelledby="add-field-heading">
      <h2 id="add-field-heading">Add field</h2>
      <p className="hint">
        Adding a field inserts one metadata row, not an ALTER TABLE: existing records simply read it as null.
      </p>
      {objects.length === 0 ? (
        <p className="muted">Create an object first.</p>
      ) : (
        <>
          <div className="field">
            <label htmlFor="field-object">Object for new field</label>
            <select id="field-object" value={target?.name ?? ""} onChange={(e) => setChosen(e.target.value)}>
              {objects.map((o) => (
                <option key={o.id} value={o.name}>
                  {o.name}
                </option>
              ))}
            </select>
          </div>
          <div className="row">
            <div className="field">
              <label htmlFor="new-field-name">Field name</label>
              <input id="new-field-name" value={name} required onChange={(e) => setName(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="new-field-type">Field type</label>
              <select id="new-field-type" value={type} onChange={(e) => setType(e.target.value as FieldType)}>
                {FIELD_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <button type="submit" disabled={action.busy || !name.trim()}>
            {action.busy ? "Adding..." : "Add field"}
          </button>
        </>
      )}
      <ErrorMessage message={action.error} />
      {done && <Notice>{done}</Notice>}
    </form>
  );
}

function CreateRecordForm({
  tenantId,
  objects,
  users,
  onDone,
}: {
  tenantId: number;
  objects: ObjectDef[];
  users: User[];
  onDone: () => void;
}) {
  const [chosen, setChosen] = useState("");
  const [raw, setRaw] = useState<Record<string, string>>({});
  const [owner, setOwner] = useState("");
  const [done, setDone] = useState<string | null>(null);
  const action = useAction();
  const target = pickObject(objects, chosen);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!target) return;
    setDone(null);
    const values: Record<string, RecordValue> = {};
    for (const f of target.fields) {
      const v = raw[f.name]?.trim() ?? "";
      if (v === "") continue;
      if (f.type === "NUMBER") {
        const n = Number(v);
        if (Number.isNaN(n)) {
          action.setError(`${f.name} must be a number`);
          return;
        }
        values[f.name] = n;
      } else if (f.type === "BOOLEAN") {
        values[f.name] = v === "true";
      } else {
        values[f.name] = raw[f.name];
      }
    }
    const ownerUserId = owner === "" ? undefined : Number(owner);
    const created = await action.run(() => api.createRecord(tenantId, target.name, values, ownerUserId));
    if (created) {
      setDone(`Created ${target.name} record ${formatId(created.record_id)}.`);
      setRaw({});
      onDone();
    }
  };

  return (
    <form className="card" onSubmit={submit} aria-labelledby="create-record-heading">
      <h2 id="create-record-heading">Create record</h2>
      {objects.length === 0 ? (
        <p className="muted">Create an object first.</p>
      ) : (
        <>
          <div className="field">
            <label htmlFor="record-object">Object for new record</label>
            <select
              id="record-object"
              value={target?.name ?? ""}
              onChange={(e) => {
                setChosen(e.target.value);
                setRaw({});
              }}
            >
              {objects.map((o) => (
                <option key={o.id} value={o.name}>
                  {o.name}
                </option>
              ))}
            </select>
          </div>
          {target && target.fields.length === 0 && (
            <p className="muted">{target.name} has no fields: the record will carry only its id and owner.</p>
          )}
          {target?.fields.map((f) => {
            const id = `record-value-${f.id}`;
            const label = `${f.name} (${f.type.toLowerCase()})`;
            const value = raw[f.name] ?? "";
            const set = (v: string) => setRaw((r) => ({ ...r, [f.name]: v }));
            return (
              <div className="field" key={f.id}>
                <label htmlFor={id}>{label}</label>
                {f.type === "BOOLEAN" ? (
                  <select id={id} value={value} onChange={(e) => set(e.target.value)}>
                    <option value="">(empty)</option>
                    <option value="true">true</option>
                    <option value="false">false</option>
                  </select>
                ) : (
                  <input
                    id={id}
                    type={f.type === "NUMBER" ? "number" : "text"}
                    step={f.type === "NUMBER" ? "any" : undefined}
                    value={value}
                    onChange={(e) => set(e.target.value)}
                  />
                )}
              </div>
            );
          })}
          {users.length > 0 && (
            <div className="field">
              <label htmlFor="record-owner">Owner</label>
              <select id="record-owner" value={owner} onChange={(e) => setOwner(e.target.value)}>
                <option value="">(no owner)</option>
                {users.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.name}
                    {u.role ? `, ${u.role}` : ""} (#{u.id})
                  </option>
                ))}
              </select>
            </div>
          )}
          <button type="submit" disabled={action.busy}>
            {action.busy ? "Creating..." : "Create record"}
          </button>
        </>
      )}
      <ErrorMessage message={action.error} />
      {done && <Notice>{done}</Notice>}
    </form>
  );
}

function formatId(id: unknown): string {
  return id === undefined || id === null ? "" : `#${String(id)}`;
}
