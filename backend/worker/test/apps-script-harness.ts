// Runs backend/apps-script (Shared.js + Backup.js) the way Apps Script does: plain V8 with no
// fetch, TextEncoder, btoa, crypto or timers, plus fakes of the Apps Script services it uses.
// UrlFetchApp is synchronous, so `route` answers each call synchronously.
import { sign } from "node:crypto";
import { readFileSync } from "node:fs";
import vm from "node:vm";

export const SHARED_JS = new URL("../../apps-script/Shared.js", import.meta.url);
export const BACKUP_JS = new URL("../../apps-script/Backup.js", import.meta.url);

export interface FetchParams {
  method?: string; contentType?: string; payload?: string; headers?: Record<string, string>; muteHttpExceptions?: boolean;
}
export type Route = (url: string, params: FetchParams) => { status: number; body: unknown };

export interface AppsScript {
  /** Evaluates code in the script's global scope; the result comes back as plain JSON. */
  run(code: string): any;
  logs: string[];
  errors: string[];
  fetches: { url: string; params: FetchParams }[];
  triggers: { handler: string; minutes: number }[];
  cache: Map<string, string>;
  global: Record<string, any>;
}

export function loadAppsScript(o: { props: Record<string, string>; route: Route; lockBusy?: boolean }): AppsScript {
  const logs: string[] = [];
  const errors: string[] = [];
  const fetches: AppsScript["fetches"] = [];
  const triggers: AppsScript["triggers"] = [];
  const cache = new Map<string, string>();
  const handles = new WeakMap<object, AppsScript["triggers"][number]>();

  const services = {
    console: {
      log: (m: unknown) => logs.push(String(m)), info: (m: unknown) => logs.push(String(m)),
      warn: (m: unknown) => errors.push(String(m)), error: (m: unknown) => errors.push(String(m)),
    },
    PropertiesService: { getScriptProperties: () => ({ getProperty: (k: string) => o.props[k] ?? null }) },
    CacheService: {
      getScriptCache: () => ({
        get: (k: string) => cache.get(k) ?? null,
        put: (k: string, v: string, ttl: number) => {
          if (!(ttl > 0 && ttl <= 21600)) throw new Error(`bad cache ttl ${ttl}`); // Apps Script's limit
          cache.set(k, v);
        },
      }),
    },
    LockService: { getScriptLock: () => ({ tryLock: () => !o.lockBusy, releaseLock: () => {} }) },
    ScriptApp: {
      getProjectTriggers: () => triggers.map((t) => {
        const h = { getHandlerFunction: () => t.handler };
        handles.set(h, t);
        return h;
      }),
      deleteTrigger: (h: object) => { triggers.splice(triggers.indexOf(handles.get(h)!), 1); },
      newTrigger: (handler: string) => ({
        timeBased: () => ({
          everyMinutes: (minutes: number) => {
            if (![1, 5, 10, 15, 30].includes(minutes)) throw new Error(`everyMinutes(${minutes}) isn't allowed`);
            return { create: () => { triggers.push({ handler, minutes }); } };
          },
        }),
      }),
    },
    Utilities: {
      // Apps Script returns Java-style signed bytes.
      computeRsaSha256Signature: (input: string, pem: string) =>
        Array.from(sign("sha256", Buffer.from(input, "utf8"), pem)).map((b) => (b > 127 ? b - 256 : b)),
      // Web-safe base64 WITH "=" padding, like Apps Script's.
      base64EncodeWebSafe: (d: string | number[]) =>
        (typeof d === "string" ? Buffer.from(d, "utf8") : Buffer.from(d.map((b) => b & 255)))
          .toString("base64").replace(/\+/g, "-").replace(/\//g, "_"),
    },
    UrlFetchApp: {
      fetch: (url: string, params: FetchParams = {}) => {
        if (!params.muteHttpExceptions) throw new Error("always pass muteHttpExceptions, or errors throw");
        fetches.push({ url, params });
        const r = o.route(url, params);
        const text = typeof r.body === "string" ? r.body : JSON.stringify(r.body);
        return { getResponseCode: () => r.status, getContentText: () => text };
      },
    },
  };

  const global = vm.createContext(services);
  vm.runInContext(readFileSync(SHARED_JS, "utf8"), global, { filename: "Shared.js" });
  vm.runInContext(readFileSync(BACKUP_JS, "utf8"), global, { filename: "Backup.js" });
  return {
    run: (code) => {
      const v = vm.runInContext(code, global);
      return v === undefined ? undefined : JSON.parse(JSON.stringify(v));
    },
    logs, errors, fetches, triggers, cache, global,
  };
}
