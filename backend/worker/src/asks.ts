// Ask to donate (phase-06 6.2, write contract 14): the push for an ask the requester's app has
// already written through the rules.
//   POST /v1/requests/{rid}/asks/{donorUid}/notify
// The rules checked the ask when it was written: the caller's OPEN request, a listed donor with
// a compatible group who hasn't blocked the caller, at most 10 asks per request. So the Worker
// only needs proof that the ask exists, plus the per-uid daily cap. Then it sends
// {type: ASKED_TO_DONATE, requestId} to the donor's usr_ topic.
//
// Daily cap (phase-06 6.2: 30 per user): counted in Firestore, like the alert cap in cap.ts. The
// rules make the inbox item's createdAt the server time, and only the donor can delete it, so
// counting the caller's ASKED_TO_DONATE items of the last 24 hours is exact enough and needs
// no counter (KV writes are scarce on the free plan). Uses the collection-group index
// notifications (fromUid, type, createdAt). Over the cap, the ask stays in the donor's inbox
// and isn't pushed. A counting error fails open, like the alert cap.
//
// Cost per call: 3 reads (the request, the inbox item, one COUNT) + 1 FCM send.
import type { Env } from "./env";
import { Firestore, fieldsOf, type StructuredQuery } from "./firestore";
import { getAccessToken } from "./google-auth";
import { HttpError, json } from "./http";
import { personalTopic, send } from "./notify";
import type { FirebaseUser } from "./verify";

export const ASK_TYPE = "ASKED_TO_DONATE";
export const DAILY_ASK_CAP = 30;
/** An ask older than this isn't pushed any more (a late retry would only be noise). */
export const ASK_FRESH_MS = 10 * 60_000;
const DAY_MS = 86_400_000;

/** The caller's asks in the 24 hours up to `now`, across every donor's inbox. */
export function askCapQuery(uid: string, now: Date): StructuredQuery {
  return {
    collection: "notifications",
    allDescendants: true,
    where: [
      ["fromUid", "EQUAL", uid],
      ["type", "EQUAL", ASK_TYPE],
      ["createdAt", "GREATER_THAN", new Date(now.getTime() - DAY_MS)],
    ],
  };
}

export async function notifyAsk(env: Env, user: FirebaseUser, rid: string, donorUid: string, now = new Date()): Promise<Response> {
  const token = await getAccessToken(env);
  const fs = new Firestore(env, token);

  const req = await fs.get(`requests/${rid}`, ["requesterUid", "status"]); // 1 read
  if (!req) throw new HttpError(404, "no such request");
  const r = fieldsOf(req);
  if (r.requesterUid !== user.uid) throw new HttpError(403, "not your request");
  if (r.status !== "OPEN") throw new HttpError(409, "the request isn't open");

  const item = await fs.get(`users/${donorUid}/notifications/ask_${rid}`, ["type", "fromUid", "createdAt"]); // 1 read
  const a = item ? fieldsOf(item) : null;
  if (!a || a.type !== ASK_TYPE || a.fromUid !== user.uid) throw new HttpError(409, "no ask from you to announce");
  const at = a.createdAt instanceof Date ? a.createdAt : new Date(String(a.createdAt));
  if (!(now.getTime() - at.getTime() <= ASK_FRESH_MS)) throw new HttpError(409, "the ask is too old to announce");

  let asks = 0;
  try {
    asks = await fs.count(askCapQuery(user.uid, now), DAILY_ASK_CAP + 1); // 1 read; includes this ask
  } catch (e) {
    console.error(JSON.stringify({ askCap: "count failed, allowing", error: String(e) }));
  }
  if (asks > DAILY_ASK_CAP) {
    console.log(JSON.stringify({ ask: rid, capped: true }));
    return json({ error: "daily ask limit reached", dailyAskCap: true }, 429);
  }

  await send(env, token, [personalTopic(donorUid)], { type: ASK_TYPE, requestId: rid }, `${rid}:${ASK_TYPE}`);
  console.log(JSON.stringify({ ask: rid, type: ASK_TYPE }));
  return json({ accepted: true, type: ASK_TYPE }, 202);
}
