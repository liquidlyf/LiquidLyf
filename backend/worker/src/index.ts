// LiquidLyf Worker (Cloudflare Workers Free).
//   HTTP: GET /health; POST dispatch (tier 1), notify (one response), notify-responders, ask notify.
//   Crons (wrangler.jsonc triggers): escalation sweep, expiry, retention clean-up, report alerts.
import { notifyAsk } from "./asks";
import { authenticate } from "./auth";
import { dispatch } from "./dispatch";
import type { Env } from "./env";
import { expireOverdue } from "./expiry";
import { HttpError, json } from "./http";
import { notifyResponders, notifyResponse } from "./notify";
import { alertNewReports } from "./reports";
import { retention } from "./retention";
import { sweep } from "./sweep";

const ID = "([A-Za-z0-9_-]{1,128})";
const DISPATCH = new RegExp(`^/v1/requests/${ID}/dispatch$`);
const NOTIFY = new RegExp(`^/v1/requests/${ID}/responses/${ID}/notify$`);
const NOTIFY_RESPONDERS = new RegExp(`^/v1/requests/${ID}/notify-responders$`);
const NOTIFY_ASK = new RegExp(`^/v1/requests/${ID}/asks/${ID}/notify$`);

/** Must match wrangler.jsonc "triggers.crons" (test/wiring.test.ts checks). */
export const CRONS = {
  sweep: "*/5 * * * *",
  expiry: "7 * * * *",
  retention: "0 3 * * *", // 09:00 in Dhaka (UTC+6)
  reports: "2,7,12,17,22,27,32,37,42,47,52,57 * * * *", // 2 min after each sweep, in its own run (src/reports.ts)
} as const;

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(req.url);
    try {
      if (pathname === "/health") {
        if (req.method !== "GET") throw new HttpError(405, "method not allowed");
        return json({ ok: true, service: "liquidlyf-worker" });
      }
      const d = DISPATCH.exec(pathname);
      const n = NOTIFY.exec(pathname);
      const nr = NOTIFY_RESPONDERS.exec(pathname);
      const na = NOTIFY_ASK.exec(pathname);
      if (!d && !n && !nr && !na) throw new HttpError(404, "not found");
      if (req.method !== "POST") throw new HttpError(405, "method not allowed");
      const user = await authenticate(req, env);
      if (d) return await dispatch(env, user, d[1]);
      if (n) return await notifyResponse(env, user, n[1], n[2]);
      if (na) return await notifyAsk(env, user, na[1], na[2]);
      return await notifyResponders(env, user, nr![1]);
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.message }, e.status);
      console.error(JSON.stringify({ error: String(e) }));
      return json({ error: "internal error" }, 500);
    }
  },

  async scheduled(event: { cron: string; scheduledTime: number }, env: Env): Promise<void> {
    const now = new Date(event.scheduledTime || Date.now());
    try {
      if (event.cron === CRONS.sweep) await sweep(env, now);
      else if (event.cron === CRONS.expiry) await expireOverdue(env, now);
      else if (event.cron === CRONS.retention) await retention(env, now);
      else if (event.cron === CRONS.reports) await alertNewReports(env, now);
      else console.error(JSON.stringify({ unknownCron: event.cron }));
    } catch (e) {
      console.error(JSON.stringify({ cron: event.cron, error: String(e) }));
      throw e; // shows the run as failed in the dashboard
    }
  },
};
