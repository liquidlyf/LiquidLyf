// The Apps Script backup against the real Firestore emulator, side by side with the Worker's sweep.
// UrlFetchApp is synchronous, so each call runs fetch in a child process (slow, but faithful).
import { execFileSync } from "node:child_process";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { fieldsOf } from "../../src/firestore";
import { sweep } from "../../src/sweep";
import { loadAppsScript, type FetchParams, type Route } from "../apps-script-harness";
import { clearFirestore, emulatorEnv, FIRESTORE, getDoc, newRequest, PROJECT, putDoc } from "./emulators";

const MIN = 60_000;
const DOCS = `http://${FIRESTORE}/v1/projects/${PROJECT}/databases/(default)/documents`;
const props = { FIREBASE_PROJECT_ID: PROJECT, FIRESTORE_EMULATOR_HOST: FIRESTORE };
const ago = (m: number) => new Date(Date.now() - m * MIN);

const CHILD = `let s="";process.stdin.setEncoding("utf8");process.stdin.on("data",(d)=>s+=d).on("end",async()=>{
const r=JSON.parse(s);try{const res=await fetch(r.url,{method:r.method,headers:r.headers,body:r.body});
process.stdout.write(JSON.stringify({status:res.status,text:await res.text()}));}
catch(e){process.stdout.write(JSON.stringify({status:599,text:String(e)}));}});`;

/** UrlFetchApp.fetch, synchronously, for real. */
const syncFetch: Route = (url: string, p: FetchParams) => {
  const headers = { ...(p.headers ?? {}), ...(p.contentType ? { "content-type": p.contentType } : {}) };
  const out = execFileSync(process.execPath, ["-e", CHILD], {
    input: JSON.stringify({ url, method: (p.method ?? "get").toUpperCase(), headers, body: p.payload }),
    encoding: "utf8",
  });
  const { status, text } = JSON.parse(out);
  return { status, body: text };
};

const pushesIn = (logs: string[]) => logs.map((l) => { try { return JSON.parse(l); } catch { return null; } })
  .filter((x) => x?.fcmDryRun);
const fields = async (path: string) => fieldsOf(await getDoc(path));

beforeEach(clearFirestore);
afterEach(() => vi.restoreAllMocks());

describe("Apps Script backup (emulator)", () => {
  test("overdue by > 10 min: the backup sends it once; < 10 min is left to the Worker, which then resends nothing", async () => {
    await putDoc("requests/late", newRequest("alice", { createdAt: ago(15), nextTierAt: ago(15) }));
    await putDoc("requests/fresh", newRequest("bob", { createdAt: ago(5), nextTierAt: ago(5) }));

    const as = loadAppsScript({ props, route: syncFetch });
    expect(as.run("backupSweep()")).toMatchObject({ due: 1, sent: 1 });
    const sent = pushesIn(as.logs);
    expect(sent).toHaveLength(1);
    expect(sent[0]).toMatchObject({ condition: "'up_BD30262000_B_POS' in topics", data: { requestId: "late", tier: "1" } });
    const late = await fields("requests/late");
    expect(late.fanoutTier).toBe(1);
    expect(late.nextTierAt.getTime()).toBeGreaterThan(Date.now() + 29 * MIN); // URGENT: 30 min
    expect((await fields("requests/fresh")).fanoutTier).toBe(0);

    const log = vi.spyOn(console, "log").mockImplementation(() => {});
    expect(await sweep(emulatorEnv(), new Date())).toMatchObject({ due: 1, sent: 1 }); // only "fresh"
    const workerSent = pushesIn(log.mock.calls.map((c) => String(c[0])));
    expect(workerSent.map((p) => p.data.requestId)).toEqual(["fresh"]);
  });

  test("a change between its read and its claim makes the backup's claim lose: no alert", async () => {
    await putDoc("requests/r1", newRequest("alice", { createdAt: ago(15), nextTierAt: ago(15) }));
    let raced = false;
    const racing: Route = (url, p) => {
      if (url.endsWith(":batchWrite") && !raced) {
        raced = true; // e.g. the owner cancels, or the Worker claims it, right now
        syncFetch(`${DOCS}/requests/r1?updateMask.fieldPaths=notes`, {
          method: "patch", contentType: "application/json", headers: { Authorization: "Bearer owner" },
          payload: JSON.stringify({ fields: { notes: { stringValue: "edited meanwhile" } } }),
        });
      }
      return syncFetch(url, p);
    };
    const as = loadAppsScript({ props, route: racing });
    expect(as.run("backupSweep()")).toMatchObject({ due: 1, lost: 1, sent: 0 });
    expect(pushesIn(as.logs)).toHaveLength(0);
    expect((await fields("requests/r1")).fanoutTier).toBe(0);
  });

  test("the daily cap holds in the backup too (COUNT on the emulator)", async () => {
    for (let i = 1; i <= 5; i++) {
      await putDoc(`requests/old${i}`, newRequest("alice", { status: "CANCELLED", fanoutTier: 1, createdAt: ago(60 + 60 * i), nextTierAt: new Date("2100-01-01T00:00:00Z") }));
    }
    await putDoc("requests/r6", newRequest("alice", { createdAt: ago(15), nextTierAt: ago(15) }));
    const as = loadAppsScript({ props, route: syncFetch });
    expect(as.run("backupSweep()")).toMatchObject({ due: 1, capped: 1, sent: 0 });
    const r6 = await fields("requests/r6");
    expect(r6.fanoutTier).toBe(0);
    expect(r6.nextTierAt.toISOString()).toBe("2100-01-01T00:00:00.000Z");
  });
});
