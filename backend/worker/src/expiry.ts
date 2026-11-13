// Expiry, cron 7 * * * * (phase-05 5.7): OPEN requests past expiresAt (neededBy + 24 h)
// become EXPIRED, and the requester's userStats.openRequests goes down by 1 in the same
// commit. The Worker is the only writer of EXPIRED (firebase/README.md, design notes).
import type { Env } from "./env";
import { FAR_FUTURE } from "./fanout";
import { Firestore, FirestoreError, fieldsOf, incrementWrite, updateWrite } from "./firestore";
import { getAccessToken } from "./google-auth";

export const EXPIRY_LIMIT = 40; // one commit (subrequest) each; the rest go next hour
const UID = /^[A-Za-z0-9_-]{1,128}$/;

export interface ExpiryResult { due: number; expired: number; lost: number; withoutStats: number }

export async function expireOverdue(env: Env, now = new Date()): Promise<ExpiryResult> {
  const token = await getAccessToken(env);
  const fs = new Firestore(env, token);
  const docs = await fs.runQuery({
    collection: "requests",
    where: [["status", "EQUAL", "OPEN"], ["expiresAt", "LESS_THAN_OR_EQUAL", now]],
    orderBy: [["expiresAt", "asc"]],
    limit: EXPIRY_LIMIT,
    select: ["requesterUid"],
  });
  const out: ExpiryResult = { due: docs.length, expired: 0, lost: 0, withoutStats: 0 };

  for (const d of docs) {
    const uid = fieldsOf(d).requesterUid;
    // updatedAt = server time, so the delta-sync feed learns the request is gone.
    const expire = updateWrite(d.name, ["status", "nextTierAt"], { status: "EXPIRED", nextTierAt: FAR_FUTURE },
      { updateTime: d.updateTime }, ["updatedAt"]);
    const stats = typeof uid === "string" && UID.test(uid)
      ? [incrementWrite(fs.docName(`userStats/${uid}`), "openRequests", -1, { exists: true })] : [];
    try {
      await fs.commit([expire, ...stats]);
      out.expired++;
      continue;
    } catch (e) {
      if (!(e instanceof FirestoreError && e.preconditionFailed)) throw e;
    }
    // Either the owner changed the request meanwhile (leave it), or userStats is missing.
    try {
      await fs.commit([expire]);
      out.expired++;
      out.withoutStats++;
      console.warn(JSON.stringify({ expiry: fs.pathOf(d.name), userStats: "missing" }));
    } catch (e) {
      if (!(e instanceof FirestoreError && e.preconditionFailed)) throw e;
      out.lost++;
    }
  }
  console.log(JSON.stringify({ expiry: out }));
  return out;
}
