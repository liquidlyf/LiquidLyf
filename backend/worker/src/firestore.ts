// Minimal Firestore REST client. The service account BYPASSES the security rules,
// so every check the rules would make must be made by the caller.
import { emulatorMode, type Env } from "./env";

export type FsValue =
  | { stringValue: string } | { booleanValue: boolean } | { integerValue: string }
  | { doubleValue: number } | { timestampValue: string } | { nullValue: null }
  | { arrayValue: { values?: FsValue[] } } | { mapValue: { fields?: Record<string, FsValue> } };

export interface FsDoc {
  name: string;
  fields?: Record<string, FsValue>;
  createTime?: string;
  updateTime: string;
}

export class FirestoreError extends Error {
  constructor(public httpStatus: number, public grpcStatus: string, message: string) {
    super(message);
  }
  /**
   * A precondition didn't hold: someone else changed the doc first (FAILED_PRECONDITION, ABORTED),
   * exists:false met a doc (ALREADY_EXISTS), or exists:true met none (NOT_FOUND, seen on commit).
   */
  get preconditionFailed() {
    return this.grpcStatus === "FAILED_PRECONDITION" || this.grpcStatus === "ABORTED"
      || this.grpcStatus === "ALREADY_EXISTS" || this.grpcStatus === "NOT_FOUND";
  }
}

export function decode(v: FsValue | undefined): any {
  if (!v) return undefined;
  if ("stringValue" in v) return v.stringValue;
  if ("booleanValue" in v) return v.booleanValue;
  if ("integerValue" in v) return Number(v.integerValue);
  if ("doubleValue" in v) return v.doubleValue;
  if ("timestampValue" in v) return new Date(v.timestampValue);
  if ("nullValue" in v) return null;
  if ("arrayValue" in v) return (v.arrayValue.values ?? []).map(decode);
  if ("mapValue" in v) {
    return Object.fromEntries(Object.entries(v.mapValue.fields ?? {}).map(([k, x]) => [k, decode(x)]));
  }
  return undefined;
}

export function encode(v: unknown): FsValue {
  if (v === null || v === undefined) return { nullValue: null };
  if (typeof v === "string") return { stringValue: v };
  if (typeof v === "boolean") return { booleanValue: v };
  if (typeof v === "number") return Number.isInteger(v) ? { integerValue: String(v) } : { doubleValue: v };
  if (v instanceof Date) return { timestampValue: v.toISOString() };
  if (Array.isArray(v)) return { arrayValue: { values: v.map(encode) } };
  if (typeof v === "object") {
    return { mapValue: { fields: Object.fromEntries(Object.entries(v).map(([k, x]) => [k, encode(x)])) } };
  }
  throw new Error(`can't encode ${typeof v}`);
}

export const fieldsOf = (d: FsDoc): Record<string, any> =>
  Object.fromEntries(Object.entries(d.fields ?? {}).map(([k, v]) => [k, decode(v)]));

export class Firestore {
  readonly base: string;
  private readonly root: string;

  constructor(env: Env, private token: string) {
    const host = emulatorMode(env) ? `http://${env.FIRESTORE_EMULATOR_HOST}` : "https://firestore.googleapis.com";
    this.root = `projects/${env.FIREBASE_PROJECT_ID}/databases/(default)/documents`;
    this.base = `${host}/v1/${this.root}`;
  }

  private async call<T>(url: string, init?: RequestInit): Promise<T> {
    const res = await fetch(url, {
      ...init,
      headers: { authorization: `Bearer ${this.token}`, "content-type": "application/json" },
    });
    if (!res.ok) {
      const text = await res.text();
      let grpc = "";
      try { grpc = JSON.parse(text)?.error?.status ?? ""; } catch { /* not JSON */ }
      throw new FirestoreError(res.status, grpc, `Firestore ${res.status} ${grpc}`.trim());
    }
    return res.json() as Promise<T>;
  }

  /** GET one document (1 read); null if missing. `mask` limits the fields returned. */
  async get(path: string, mask: string[] = []): Promise<FsDoc | null> {
    const q = mask.map((f) => `mask.fieldPaths=${encodeURIComponent(f)}`).join("&");
    try {
      return await this.call<FsDoc>(`${this.base}/${path}${q ? `?${q}` : ""}`);
    } catch (e) {
      if (e instanceof FirestoreError && e.httpStatus === 404) return null;
      throw e;
    }
  }

  /** Atomic commit. Throws FirestoreError (preconditionFailed) when a precondition fails. */
  async commit(writes: unknown[]): Promise<{ writeResults: { updateTime?: string }[]; commitTime: string }> {
    return this.call(`${this.base}:commit`, { method: "POST", body: JSON.stringify({ writes }) });
  }

  /**
   * Non-atomic batch (max 500 writes, one subrequest): each write succeeds or fails on its
   * own, so one stale precondition doesn't sink the others. Returns per-write results.
   */
  async batchWrite(writes: unknown[]): Promise<{ ok: boolean; updateTime?: string; code: number }[]> {
    if (writes.length === 0) return [];
    const res = await this.call<{ writeResults?: { updateTime?: string }[]; status?: { code?: number }[] }>(
      `${this.base}:batchWrite`, { method: "POST", body: JSON.stringify({ writes }) });
    return writes.map((_, i) => {
      const code = res.status?.[i]?.code ?? 0;
      return { ok: code === 0, code, updateTime: res.writeResults?.[i]?.updateTime };
    });
  }

  /**
   * A structured query on one collection (or every collection with that ID when allDescendants).
   * Each returned document costs 1 read; an empty result costs 1 read.
   */
  async runQuery(q: StructuredQuery): Promise<FsDoc[]> {
    // Collection-group queries run from the database root.
    const rows = await this.call<{ document?: FsDoc }[]>(`${this.base}:runQuery`, {
      method: "POST", body: JSON.stringify({ structuredQuery: structuredQuery(q) }),
    });
    return rows.filter((r) => r.document).map((r) => r.document!);
  }

  /**
   * COUNT of a query's matches, stopping at `upTo`. Costs 1 read per 1000 index entries
   * scanned (minimum 1); needs the same index as the query itself.
   */
  async count(q: StructuredQuery, upTo?: number): Promise<number> {
    const rows = await this.call<{ result?: { aggregateFields?: Record<string, FsValue> } }[]>(
      `${this.base}:runAggregationQuery`, {
        method: "POST",
        body: JSON.stringify({ structuredAggregationQuery: {
          structuredQuery: structuredQuery(q),
          aggregations: [{ alias: "n", count: upTo ? { upTo: String(upTo) } : {} }],
        } }),
      });
    return Number(decode(rows.find((r) => r.result)?.result?.aggregateFields?.n) ?? 0);
  }

  docName(path: string) {
    return `${this.root}/${path}`;
  }

  /** "requests/abc" from a full document name. */
  pathOf(name: string) {
    return name.slice(this.root.length + 1);
  }
}

export type FilterOp = "EQUAL" | "LESS_THAN" | "LESS_THAN_OR_EQUAL" | "GREATER_THAN" | "GREATER_THAN_OR_EQUAL" | "IN";
export interface StructuredQuery {
  collection: string;
  allDescendants?: boolean;
  where?: [string, FilterOp, unknown][];
  orderBy?: [string, "asc" | "desc"][];
  limit?: number;
  select?: string[];
  /** Cursor values matching orderBy; use { ref: fullDocName } for __name__. */
  startAfter?: unknown[];
}

function structuredQuery(q: StructuredQuery): Record<string, unknown> {
  const { collection, allDescendants = false, where = [], orderBy = [], limit, select, startAfter } = q;
  const filters = where.map(([field, op, value]) => ({ fieldFilter: { field: { fieldPath: field }, op, value: encode(value) } }));
  return {
    from: [{ collectionId: collection, allDescendants }],
    ...(filters.length === 1 ? { where: filters[0] }
      : filters.length > 1 ? { where: { compositeFilter: { op: "AND", filters } } } : {}),
    orderBy: orderBy.map(([field, dir]) => ({ field: { fieldPath: field }, direction: dir === "desc" ? "DESCENDING" : "ASCENDING" })),
    ...(select ? { select: { fields: select.map((f) => ({ fieldPath: f })) } } : {}),
    ...(startAfter ? { startAt: { values: startAfter.map(encodeCursor), before: false } } : {}),
    ...(limit ? { limit } : {}),
  };
}

function encodeCursor(v: unknown): FsValue {
  if (v && typeof v === "object" && "ref" in (v as object)) return { referenceValue: (v as { ref: string }).ref } as unknown as FsValue;
  return encode(v);
}

// ---- write builders (Firestore REST "Write" objects) ----

/** Update only `fields` (a key missing from `values` but listed in `mask` is deleted). */
export function updateWrite(name: string, mask: string[], values: Record<string, unknown>,
  pre?: { updateTime?: string; exists?: boolean }, serverTimeFields: string[] = []) {
  return {
    update: { name, fields: Object.fromEntries(Object.entries(values).map(([k, v]) => [k, encode(v)])) },
    updateMask: { fieldPaths: mask },
    ...(serverTimeFields.length
      ? { updateTransforms: serverTimeFields.map((f) => ({ fieldPath: f, setToServerValue: "REQUEST_TIME" })) } : {}),
    ...(pre ? { currentDocument: pre } : {}),
  };
}

export function incrementWrite(name: string, field: string, by: number, pre?: { exists?: boolean }) {
  return {
    transform: { document: name, fieldTransforms: [{ fieldPath: field, increment: { integerValue: String(by) } }] },
    ...(pre ? { currentDocument: pre } : {}),
  };
}

export const deleteWrite = (name: string) => ({ delete: name });
