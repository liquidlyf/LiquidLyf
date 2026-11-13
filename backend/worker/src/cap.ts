// Per-uid daily alert cap (doc 10 §6.5). A request alerts donors only if its owner created
// fewer than DAILY_ALERT_CAP requests in the 24 hours before it. So in any 24 hours at most
// that many of one person's requests reach donors, however often they cancel and re-post.
//
// No counter to keep: the rules make createdAt the server time and forbid deleting requests,
// so counting the uid's earlier requests is exact, and both dispatch and the sweep get the
// same answer for the same request. Uses the my-requests index (requesterUid ASC,
// createdAt DESC). Cost: 1 read (a COUNT, stopped at the cap).
import type { Env } from "./env";
import type { Firestore } from "./firestore";

export const DEFAULT_DAILY_ALERT_CAP = 5;
const DAY_MS = 86_400_000;

export function dailyAlertCap(env: Env): number {
  const n = Number(env.DAILY_ALERT_CAP);
  return Number.isInteger(n) && n > 0 ? n : DEFAULT_DAILY_ALERT_CAP;
}

/**
 * True when `uid` already had `cap` requests in the 24 h before `createdAt`.
 * Fails open: a counting error never silences a blood request; it's logged instead.
 */
export async function overDailyCap(fs: Firestore, env: Env, uid: string, createdAt: Date): Promise<boolean> {
  const cap = dailyAlertCap(env);
  try {
    const n = await fs.count({
      collection: "requests",
      where: [
        ["requesterUid", "EQUAL", uid],
        ["createdAt", "GREATER_THAN_OR_EQUAL", new Date(createdAt.getTime() - DAY_MS)],
        ["createdAt", "LESS_THAN", createdAt],
      ],
      orderBy: [["createdAt", "desc"]], // matches the index's direction
    }, cap);
    return n >= cap;
  } catch (e) {
    console.error(JSON.stringify({ dailyCap: "count failed, allowing", error: String(e) }));
    return false;
  }
}
