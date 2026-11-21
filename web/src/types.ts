/** Response and request shapes of the Spring Boot admin and book-of-record APIs. */

// ---- Admin: tenants, metadata, records, query ----

export interface Tenant {
  id: number;
  name: string;
  objectCount: number;
  userCount: number;
}

export type FieldType = "STRING" | "NUMBER" | "BOOLEAN";

export const FIELD_TYPES: readonly FieldType[] = ["STRING", "NUMBER", "BOOLEAN"];

export interface FieldDef {
  id: number;
  name: string;
  type: FieldType;
}

export interface ObjectDef {
  id: number;
  name: string;
  fields: FieldDef[];
  recordCount: number;
}

export interface User {
  id: number;
  name: string;
  role: string | null;
}

export type RecordValue = string | number | boolean;

export interface NewRecord {
  values: Record<string, RecordValue>;
  ownerUserId?: number;
}

export type Scoping = "tenant" | "tenant+sharing";

export interface QueryResult {
  scoping: Scoping;
  sql: string;
  params: unknown[];
  rows: Record<string, unknown>[];
  elapsedMs: number;
}

// ---- Book of record: accounts, fact versions, restatements ----

export interface Account {
  id: number;
  name: string;
}

/** BigDecimal fields arrive as JSON numbers; strings are tolerated in case the server changes. */
export type Decimal = number | string;

export interface VersionView {
  version: number;
  symbol: string;
  effectiveDate: string;
  entryType: string;
  quantity: Decimal;
  costBasis: Decimal;
  realizedPnl: Decimal;
  asOfFrom: string;
  asOfTo: string | null;
}

export interface Correction {
  quantity?: number;
  costBasis?: number;
  realizedPnl?: number;
}

export interface FieldDiff {
  fieldName: string;
  priorValue: string;
  restatedValue: string;
  changed: boolean;
}

export interface RestatementDiff {
  factId: string;
  accountId: number;
  symbol: string;
  effectiveDate: string;
  priorAsOfFrom: string;
  restatedAsOfFrom: string;
  fields: FieldDiff[];
}

export interface Health {
  status: string;
}
