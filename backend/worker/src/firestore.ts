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
  /** A precondition (updateTime / exists) didn't hold: someone else changed the doc first. */
  get preconditionFailed() {
    return this.grpcStatus === "FAILED_PRECONDITION" || this.grpcStatus === "ABORTED"
      || this.grpcStatus === "ALREADY_EXISTS";
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

  docName(path: string) {
    return `${this.root}/${path}`;
  }
}
