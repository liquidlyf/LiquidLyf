// The REST client against the real Firestore emulator: masks, 404s and preconditions.
import { beforeEach, describe, expect, test } from "vitest";
import { Firestore, FirestoreError, fieldsOf } from "../../src/firestore";
import { clearFirestore, emulatorEnv, newRequest, putDoc } from "./emulators";

beforeEach(clearFirestore);

describe("Firestore REST client (emulator)", () => {
  const fs = () => new Firestore(emulatorEnv(), "owner");

  test("get returns only the masked fields, and null for a missing doc", async () => {
    await putDoc("requests/r1", newRequest("alice"));
    const doc = await fs().get("requests/r1", ["status", "fanoutTier"]);
    expect(Object.keys(fieldsOf(doc!)).sort()).toEqual(["fanoutTier", "status"]);
    expect(doc!.updateTime).toBeTruthy();
    expect(await fs().get("requests/nope")).toBeNull();
  });

  test("a stale updateTime precondition fails with preconditionFailed", async () => {
    const first = await putDoc("requests/r1", newRequest("alice"));
    await putDoc("requests/r1", newRequest("alice", { notes: "changed" }));
    const write = {
      update: { name: fs().docName("requests/r1"), fields: { fanoutTier: { integerValue: "1" } } },
      updateMask: { fieldPaths: ["fanoutTier"] },
      currentDocument: { updateTime: first.updateTime },
    };
    const err = await fs().commit([write]).catch((e) => e);
    expect(err).toBeInstanceOf(FirestoreError);
    expect((err as FirestoreError).preconditionFailed).toBe(true);
  });

  test("a matching precondition commits and returns the new updateTime", async () => {
    const { updateTime } = await putDoc("requests/r1", newRequest("alice"));
    const res = await fs().commit([{
      update: { name: fs().docName("requests/r1"), fields: { fanoutTier: { integerValue: "1" } } },
      updateMask: { fieldPaths: ["fanoutTier"] },
      currentDocument: { updateTime },
    }]);
    expect(res.writeResults[0].updateTime).toBeTruthy();
    const after = fieldsOf((await fs().get("requests/r1"))!);
    expect(after.fanoutTier).toBe(1);
    expect(after.notes).toBe(""); // the mask kept every other field
  });
});
