// The Apps Script backup (backend/apps-script, phase-05 5.8), run in a bare V8 context with fake
// Apps Script services and a fake Google. Shared.js must be the current build of the Worker's code.
import { KeyObject, verify } from "node:crypto";
import { readFileSync } from "node:fs";
import { describe, expect, test } from "vitest";
// @ts-expect-error: plain .mjs script, no types
import { bundleShared } from "../scripts/build-apps-script.mjs";
import { FAR_FUTURE } from "../src/fanout";
import { fcmRequestBody } from "../src/fcm";
import { docName, keys, PROJECT, requestRest, serviceAccountJson } from "./helpers";
import { loadAppsScript, SHARED_JS, type Route } from "./apps-script-harness";

const saPublic = KeyObject.from(keys.sa.publicKey);
const BASE = `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)/documents`;
const MIN = 60_000;
const stripJsonc = (s: string) => s.replace(/\/\/.*$/gm, "").replace(/,(\s*[}\]])/g, "$1");

type Err = { status: number; grpc: string };
interface GoogleOpts {
  query?: (q: any) => any[]; queryError?: Err;
  count?: (q: any) => number | Err;
  batchCodes?: number[][];
  fcmStatus?: number[];
}

/** A synchronous fake of Google's token, Firestore and FCM endpoints. */
function google(o: GoogleOpts = {}) {
  const calls = {
    token: 0, grants: [] as string[], jwts: [] as { header: any; claims: any }[], auth: [] as string[],
    queries: [] as any[], counts: [] as any[], batches: [] as any[][], fcm: [] as any[],
  };
  const batchCodes = [...(o.batchCodes ?? [])];
  const fcmStatus = [...(o.fcmStatus ?? [])];
  const readTime = new Date().toISOString();
  const err = (e: Err) => ({ status: e.status, body: { error: { status: e.grpc } } });
  const route: Route = (url, p) => {
    if (url === "https://oauth2.googleapis.com/token") {
      calls.token++;
      const form = new URLSearchParams(p.payload);
      calls.grants.push(`${p.contentType} ${form.get("grant_type")}`);
      const assertion = form.get("assertion")!;
      if (!/^[\w-]+\.[\w-]+\.[\w-]+$/.test(assertion)) return { status: 400, body: { error: "invalid_grant", note: "not a compact JWS" } };
      const [h, c, s] = assertion.split(".");
      if (!verify("sha256", Buffer.from(`${h}.${c}`), saPublic, Buffer.from(s, "base64url"))) {
        return { status: 400, body: { error: "invalid_grant" } };
      }
      calls.jwts.push({ header: JSON.parse(Buffer.from(h, "base64url").toString()), claims: JSON.parse(Buffer.from(c, "base64url").toString()) });
      return { status: 200, body: { access_token: `ya29.backup-${calls.token}`, expires_in: 3599, token_type: "Bearer" } };
    }
    calls.auth.push(p.headers?.Authorization ?? "");
    const body = JSON.parse(p.payload ?? "{}");
    if (url === `${BASE}:runQuery`) {
      calls.queries.push(body.structuredQuery);
      if (o.queryError) return err(o.queryError);
      const docs = o.query?.(body.structuredQuery) ?? [];
      return { status: 200, body: docs.length ? docs.map((document) => ({ document, readTime })) : [{ readTime }] };
    }
    if (url === `${BASE}:runAggregationQuery`) {
      calls.counts.push(body.structuredAggregationQuery);
      const n = o.count?.(body.structuredAggregationQuery.structuredQuery) ?? 0;
      if (typeof n === "object") return err(n);
      return { status: 200, body: [{ result: { aggregateFields: { n: { integerValue: String(n) } } }, readTime }] };
    }
    if (url === `${BASE}:batchWrite`) {
      calls.batches.push(body.writes);
      const codes = batchCodes.shift() ?? [];
      return {
        status: 200,
        body: {
          writeResults: body.writes.map((_: unknown, i: number) => ((codes[i] ?? 0) === 0 ? { updateTime: new Date().toISOString() } : {})),
          status: body.writes.map((_: unknown, i: number) => ({ code: codes[i] ?? 0 })),
        },
      };
    }
    if (url === `https://fcm.googleapis.com/v1/projects/${PROJECT}/messages:send`) {
      calls.fcm.push(body);
      const status = fcmStatus.shift() ?? 200;
      return { status, body: status === 200 ? { name: "m1" } : { error: { code: status } } };
    }
    return { status: 500, body: `unexpected ${url}` };
  };
  return { calls, route };
}

const props = async (o: Record<string, string> = {}) =>
  ({ FIREBASE_PROJECT_ID: PROJECT, GCP_SA_JSON: await serviceAccountJson(), ...o });

const due = (rid: string, o: Record<string, unknown> = {}) => ({
  name: docName(`requests/${rid}`),
  ...requestRest({ nextTierAt: new Date(Date.now() - 15 * MIN), expiresAt: new Date(Date.now() + 2 * 86_400_000),
    unitsNeeded: 1, unitsFulfilled: 0, responders: {}, ...o }),
  updateTime: `2026-11-20T05:00:00.00000${rid.length}Z`,
});

describe("Shared.js", () => {
  test("is the current build of the Worker's code (else: npm run build:apps-script)", async () => {
    const committed = readFileSync(SHARED_JS, "utf8").replace(/\r\n/g, "\n");
    expect(committed === await bundleShared()).toBe(true);
  });

  test("runs in a bare V8 context like Apps Script's: no fetch, TextEncoder, btoa or crypto", async () => {
    const as = loadAppsScript({ props: await props(), route: google().route });
    expect(as.run("[typeof fetch, typeof TextEncoder, typeof btoa, typeof crypto, typeof setTimeout]"))
      .toEqual(["undefined", "undefined", "undefined", "undefined", "undefined"]);
    expect(as.run("typeof LiquidLyf.sweepSteps")).toBe("function");
  });
});

describe("backupSweep", () => {
  test("an overdue, never-dispatched request: key -> token, cap count, claim with precondition, the Worker's exact push", async () => {
    const { calls, route } = google({ query: () => [due("r1")] });
    const as = loadAppsScript({ props: await props(), route });
    const before = Date.now();
    const out = as.run("backupSweep()");
    const after = Date.now();
    expect(out).toEqual({ due: 1, sent: 1, waiting: 0, finished: 0, capped: 0, lost: 0, failed: 0, deferred: 0 });
    expect(as.logs).toContain(JSON.stringify({ backupSweep: out }));

    // The token: an RS256 JWT signed with the key, asking only for Firestore + FCM.
    expect(calls.token).toBe(1);
    expect(calls.grants).toEqual(["application/x-www-form-urlencoded urn:ietf:params:oauth:grant-type:jwt-bearer"]);
    expect(calls.jwts[0].header).toEqual({ alg: "RS256", typ: "JWT" });
    expect(calls.jwts[0].claims).toMatchObject({
      iss: `liquidlyf-worker@${PROJECT}.iam.gserviceaccount.com`, aud: "https://oauth2.googleapis.com/token",
      scope: "https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/firebase.messaging",
    });
    expect(new Set(calls.auth)).toEqual(new Set(["Bearer ya29.backup-1"]));

    // Only requests overdue by more than 10 minutes: the Worker's own sweep handles the rest.
    const [status, dueBy] = calls.queries[0].where.compositeFilter.filters.map((f: any) => f.fieldFilter);
    expect(status).toMatchObject({ field: { fieldPath: "status" }, op: "EQUAL", value: { stringValue: "OPEN" } });
    expect(dueBy).toMatchObject({ field: { fieldPath: "nextTierAt" }, op: "LESS_THAN_OR_EQUAL" });
    const t = Date.parse(dueBy.value.timestampValue);
    expect(t).toBeGreaterThanOrEqual(before - 10 * MIN);
    expect(t).toBeLessThanOrEqual(after - 10 * MIN);

    expect(calls.counts[0].aggregations).toEqual([{ alias: "n", count: { upTo: "5" } }]);
    const claim = calls.batches[0][0];
    expect(claim.currentDocument).toEqual({ updateTime: "2026-11-20T05:00:00.000002Z" });
    expect(claim.update.fields.fanoutTier).toEqual({ integerValue: "1" });
    expect(calls.fcm).toEqual([fcmRequestBody({
      condition: "'up_BD30262000_B_POS' in topics", collapseKey: "r1",
      data: { type: "NEW_REQUEST", requestId: "r1", bloodGroup: "B_POS", district: "BD3026", urgency: "URGENT", tier: "1" },
    })]);
  });

  test("the token is cached between runs", async () => {
    const { calls, route } = google();
    const as = loadAppsScript({ props: await props(), route });
    as.run("backupSweep()");
    as.run("backupSweep()");
    expect(calls.token).toBe(1);
    expect(calls.queries).toHaveLength(2);
  });

  test("a request the Worker claimed first is lost cleanly: no push", async () => {
    const { calls, route } = google({ query: () => [due("r1", { fanoutTier: 1 })], batchCodes: [[9]] });
    const as = loadAppsScript({ props: await props(), route });
    expect(as.run("backupSweep()")).toMatchObject({ lost: 1, sent: 0 });
    expect(calls.fcm).toHaveLength(0);
  });

  test("if FCM fails, the claim is put back for the next run", async () => {
    const { calls, route } = google({ query: () => [due("r1", { fanoutTier: 1 })], fcmStatus: [503] });
    const as = loadAppsScript({ props: await props(), route });
    expect(as.run("backupSweep()")).toMatchObject({ failed: 1 });
    expect(calls.batches[1][0].update.fields.fanoutTier).toEqual({ integerValue: "1" });
  });

  test("over the daily cap: parked at tier 0 like the Worker does; a broken count fails open", async () => {
    let g = google({ query: () => [due("r1")], count: () => 5 });
    let as = loadAppsScript({ props: await props(), route: g.route });
    expect(as.run("backupSweep()")).toMatchObject({ capped: 1, sent: 0 });
    expect(g.calls.batches[0][0].update.fields).toEqual({
      fanoutTier: { integerValue: "0" }, nextTierAt: { timestampValue: FAR_FUTURE.toISOString() },
    });
    g = google({ query: () => [due("r1")], count: () => ({ status: 400, grpc: "FAILED_PRECONDITION" }) });
    as = loadAppsScript({ props: await props(), route: g.route });
    expect(as.run("backupSweep()")).toMatchObject({ sent: 1 });
    expect(as.errors.join()).toContain("count failed, allowing");
  });

  test("a Firestore error on the query fails the run, so Apps Script emails the owner", async () => {
    const { calls, route } = google({ queryError: { status: 403, grpc: "PERMISSION_DENIED" } });
    const as = loadAppsScript({ props: await props(), route });
    expect(() => as.run("backupSweep()")).toThrow("Firestore 403 PERMISSION_DENIED");
    expect(calls.batches).toHaveLength(0);
  });

  test("while a previous run still holds the lock, it does nothing", async () => {
    const { route } = google();
    const as = loadAppsScript({ props: await props(), route, lockBusy: true });
    expect(as.run("backupSweep()")).toBeNull();
    expect(as.fetches).toHaveLength(0);
  });
});

describe("setup and config", () => {
  test("setup checks the key with one read, then installs exactly one 10-minute trigger", async () => {
    const { calls, route } = google();
    const as = loadAppsScript({ props: await props(), route });
    as.run("setup()");
    as.run("setup()"); // running it again replaces, never duplicates
    expect(as.triggers).toEqual([{ handler: "backupSweep", minutes: 10 }]);
    expect(calls.queries[0]).toMatchObject({ from: [{ collectionId: "requests" }], limit: 1 });
    expect(as.logs.at(-1)).toContain("backupSweep now runs every 10 minutes");
    as.run("removeTrigger()");
    expect(as.triggers).toEqual([]);
  });

  test("clear errors: no key, a key for another project, the emulator on a real project", async () => {
    const { route } = google();
    expect(() => loadAppsScript({ props: { FIREBASE_PROJECT_ID: PROJECT }, route }).run("setup()"))
      .toThrow("Script property GCP_SA_JSON is missing");
    const other = JSON.stringify({ ...JSON.parse(await serviceAccountJson()), project_id: "liquidlyf-9760b" });
    const wrongKey = await props({ GCP_SA_JSON: other });
    expect(() => loadAppsScript({ props: wrongKey, route }).run("backupSweep()"))
      .toThrow("GCP_SA_JSON is a key for project liquidlyf-9760b, not liquidlyf-test");
    const emulatorOnProd = await props({ FIRESTORE_EMULATOR_HOST: "127.0.0.1:8085" });
    expect(() => loadAppsScript({ props: emulatorOnProd, route }).run("backupSweep()"))
      .toThrow("FIRESTORE_EMULATOR_HOST is set for a non-demo project");
  });

  test("Backup.js matches wrangler.jsonc, and overdue + interval stay within the 20-minute acceptance", async () => {
    const config = JSON.parse(stripJsonc(readFileSync(new URL("../wrangler.jsonc", import.meta.url), "utf8")));
    const as = loadAppsScript({ props: await props(), route: google().route });
    expect(as.run("PROJECT_ID")).toBe(config.vars.FIREBASE_PROJECT_ID);
    expect(as.run("ESCALATION_MINUTES")).toEqual(config.vars.ESCALATION_MINUTES);
    expect(as.run("DAILY_ALERT_CAP")).toBe(config.vars.DAILY_ALERT_CAP);
    expect(as.run("OVERDUE_MINUTES + EVERY_MINUTES")).toBeLessThanOrEqual(20);
  });
});
