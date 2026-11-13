// POST /v1/requests/{rid}/dispatch: send the first tier of a new request (doc 10 §6.4).
// Cost: 2 Firestore reads (the request + the daily-cap count) + 1 write.
// Later tiers are the 5-minute sweep's job (sweep.ts).
import { overDailyCap } from "./cap";
import type { Env } from "./env";
import { sendToCondition } from "./fcm";
import { addMinutes, escalationMinutes, FAR_FUTURE, fanoutWrite, requestPushData } from "./fanout";
import { Firestore, FirestoreError, fieldsOf } from "./firestore";
import { getAccessToken } from "./google-auth";
import { HttpError, json } from "./http";
import { firstTier, tierConditions, type TierRequest } from "./tiers";
import type { FirebaseUser } from "./verify";

/** The app calls dispatch right after creating the request; later ones are the sweep's job. */
export const DISPATCH_WINDOW_MS = 10 * 60 * 1000;
const MASK = ["requesterUid", "status", "fanoutTier", "nextTierAt", "createdAt", "bloodGroup", "component",
  "acceptCompatible", "acceptedGroups", "division", "district", "upazila", "urgency"];

const alreadyDispatched = () => json({ accepted: true, alreadyDispatched: true }, 202);
const dailyCapped = () => json({ error: "daily alert limit reached", dailyCap: true }, 429);

export async function dispatch(env: Env, user: FirebaseUser, rid: string): Promise<Response> {
  const token = await getAccessToken(env);
  const fs = new Firestore(env, token);

  const doc = await fs.get(`requests/${rid}`, MASK); // the 1 read
  if (!doc) throw new HttpError(404, "no such request");
  const r = fieldsOf(doc);
  if (r.requesterUid !== user.uid) throw new HttpError(403, "not your request");
  if (r.status !== "OPEN") throw new HttpError(409, "request is not open");
  if ((r.fanoutTier ?? 0) !== 0) return alreadyDispatched();

  const now = new Date();
  if (!(r.createdAt instanceof Date) || now.getTime() - r.createdAt.getTime() > DISPATCH_WINDOW_MS) {
    throw new HttpError(409, "too late to dispatch; the sweep will send it");
  }

  let tier: number | null;
  let conditions: string[];
  try {
    tier = firstTier(r as TierRequest);
    conditions = tier === null ? [] : tierConditions(r as TierRequest, tier);
  } catch {
    throw new HttpError(422, "request has invalid area or group codes");
  }
  if (tier === null || conditions.length === 0) throw new HttpError(422, "nothing to send");

  // Parked at tier 0 = an earlier call (or the sweep) found it over the cap: answer the same, cheaply.
  if (r.nextTierAt instanceof Date && r.nextTierAt >= FAR_FUTURE) return dailyCapped();
  if (await overDailyCap(fs, env, user.uid, r.createdAt)) {
    // Park it (tier 0, never due), so the sweep doesn't send it either. It stays in the feed.
    await fs.commit([fanoutWrite(fs, rid, 0, FAR_FUTURE, doc.updateTime)]).catch((e) => {
      if (!(e instanceof FirestoreError && e.preconditionFailed)) throw e;
    });
    console.log(JSON.stringify({ dispatch: rid, dailyCap: true }));
    return dailyCapped();
  }

  const nextTierAt = addMinutes(now, escalationMinutes(env.ESCALATION_MINUTES, r.urgency));

  // Claim the tier first: the updateTime precondition makes a concurrent or repeated
  // dispatch lose cleanly, so donors are never alerted twice for the same tier.
  let claimedAt: string | undefined;
  try {
    const res = await fs.commit([fanoutWrite(fs, rid, tier, nextTierAt, doc.updateTime)]); // the 1 write
    claimedAt = res.writeResults[0]?.updateTime;
  } catch (e) {
    if (e instanceof FirestoreError && e.preconditionFailed) return alreadyDispatched();
    throw e;
  }

  const data = requestPushData(rid, r, tier);
  for (const condition of conditions) {
    const result = await sendToCondition(env, token, { condition, data, collapseKey: rid });
    if (result !== "ok") {
      // Undo the claim so the app's retry (or the sweep) sends it again.
      if (claimedAt) {
        await fs.commit([fanoutWrite(fs, rid, 0, now, claimedAt)])
          .catch((e) => console.error(JSON.stringify({ dispatch: rid, releaseFailed: String(e) })));
      }
      throw new HttpError(503, "push service unavailable, try again");
    }
  }
  console.log(JSON.stringify({ dispatch: rid, tier, messages: conditions.length }));
  return json({ accepted: true, tier, messages: conditions.length }, 202);
}
