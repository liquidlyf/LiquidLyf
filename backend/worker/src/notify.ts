// Personal pushes to usr_{uid} topics (phase-05 5.6, doc 10 §6.4). The app has already written
// the inbox item through the rules; this sends the push that wakes the other person's phone.
//   POST /v1/requests/{rid}/responses/{donorUid}/notify   one response changed state
//   POST /v1/requests/{rid}/notify-responders             the requester closed the request
//   (asks to donate: src/asks.ts)
// The Worker re-reads the doc and checks the caller's role for its CURRENT state, so a caller
// can only announce something that really happened. Payload: {type, requestId}, nothing else,
// because anyone could subscribe to someone's usr_ topic. Rate-limited per uid (auth.ts).
import type { Env } from "./env";
import { sendToCondition } from "./fcm";
import { Firestore, fieldsOf, updateWrite } from "./firestore";
import { getAccessToken } from "./google-auth";
import { HttpError, json } from "./http";
import { toConditions } from "./tiers";
import type { FirebaseUser } from "./verify";

const UID = /^[A-Za-z0-9_-]{1,128}$/;
/** What the donor's new response status tells the requester. */
const FROM_DONOR: Record<string, string> = { OFFERED: "NEW_OFFER" };
/** What the requester's decision tells the donor. */
const FROM_REQUESTER: Record<string, string> = {
  CONFIRMED: "OFFER_CONFIRMED", DECLINED: "OFFER_DECLINED", DONATED: "DONATION_RECORDED",
};
const CLOSED: Record<string, string> = { FULFILLED: "REQUEST_FULFILLED", CANCELLED: "REQUEST_CANCELLED" };
/** Donors still waiting on a request; DONATED ones already got DONATION_RECORDED. */
const WAITING = new Set(["OFFERED", "CONFIRMED"]);

export const personalTopic = (uid: string) => {
  if (!UID.test(uid)) throw new Error("bad uid");
  return `usr_${uid}`;
};

/** Sends one push to each condition of up to 5 topics; any FCM failure is a 503 (the app retries). */
export async function send(env: Env, token: string, topics: string[], data: Record<string, string>, collapseKey: string) {
  for (const condition of toConditions(topics)) {
    if (await sendToCondition(env, token, { condition, data, collapseKey }) !== "ok") {
      throw new HttpError(503, "push service unavailable, try again");
    }
  }
}

export async function notifyResponse(env: Env, user: FirebaseUser, rid: string, donorUid: string): Promise<Response> {
  const token = await getAccessToken(env);
  const fs = new Firestore(env, token);
  const doc = await fs.get(`requests/${rid}/responses/${donorUid}`, ["donorUid", "requesterUid", "status", "createdAt"]); // 1 read
  if (!doc) throw new HttpError(404, "no such response");
  const r = fieldsOf(doc);

  let type: string | undefined;
  let target: string;
  if (user.uid === donorUid && r.donorUid === donorUid) {
    type = FROM_DONOR[r.status];
    target = r.requesterUid;
  } else if (user.uid === r.requesterUid) {
    type = FROM_REQUESTER[r.status];
    target = r.donorUid;
  } else {
    throw new HttpError(403, "not your request or offer");
  }
  if (!type) throw new HttpError(409, "nothing to announce for this status");
  if (typeof target !== "string" || !UID.test(target)) throw new HttpError(422, "bad response data");

  await send(env, token, [personalTopic(target)], { type, requestId: rid }, `${rid}:${type}`);
  console.log(JSON.stringify({ notify: rid, type }));
  if (type === "NEW_OFFER") await markRapidResponder(fs, rid, donorUid, r.createdAt);
  return json({ accepted: true, type }, 202);
}

/** The profile's "Rapid responder" badge (phase-06): an offer within 15 minutes of the post. */
export const RAPID_RESPONSE_MS = 15 * 60_000;

/**
 * Sets users/{donor}.rapidResponder = true when this offer came within RAPID_RESPONSE_MS of the
 * request being posted. Both times are server times (the rules make them request.time), so
 * neither app can fake them. The rules let only the Worker write the field. Best effort:
 * the push has already gone, so a failure here is only logged. Cost: 1 read, +1 write if fast.
 */
async function markRapidResponder(fs: Firestore, rid: string, donorUid: string, offeredAt: unknown) {
  try {
    const req = await fs.get(`requests/${rid}`, ["createdAt"]);
    const posted = req ? fieldsOf(req).createdAt : undefined;
    if (!(posted instanceof Date) || !(offeredAt instanceof Date)) return;
    if (offeredAt.getTime() - posted.getTime() > RAPID_RESPONSE_MS) return;
    await fs.commit([updateWrite(fs.docName(`users/${donorUid}`), ["rapidResponder"], { rapidResponder: true }, { exists: true })]);
    console.log(JSON.stringify({ rapidResponder: rid }));
  } catch (e) {
    console.error(JSON.stringify({ rapidResponder: "failed", error: String(e) }));
  }
}

export async function notifyResponders(env: Env, user: FirebaseUser, rid: string): Promise<Response> {
  const token = await getAccessToken(env);
  const fs = new Firestore(env, token);
  const doc = await fs.get(`requests/${rid}`, ["requesterUid", "status", "responders"]); // 1 read
  if (!doc) throw new HttpError(404, "no such request");
  const r = fieldsOf(doc);
  if (r.requesterUid !== user.uid) throw new HttpError(403, "not your request");
  const type = CLOSED[r.status];
  if (!type) throw new HttpError(409, "the request isn't fulfilled or cancelled");

  const targets = Object.entries((r.responders ?? {}) as Record<string, { status?: string }>)
    .filter(([uid, v]) => UID.test(uid) && WAITING.has(v?.status ?? ""))
    .map(([uid]) => uid);
  if (targets.length > 0) await send(env, token, targets.map(personalTopic), { type, requestId: rid }, `${rid}:${type}`);
  console.log(JSON.stringify({ notifyResponders: rid, type, recipients: targets.length }));
  return json({ accepted: true, type, recipients: targets.length }, 202);
}
