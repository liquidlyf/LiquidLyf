// LiquidLyf Worker (Cloudflare Workers Free).
//   HTTP: GET /health; POST dispatch (tier 1), notify (one response), notify-responders.
//   Crons (wrangler.jsonc triggers): escalation sweep, expiry, retention clean-up.
import { authenticate } from "./auth";
import { dispatch } from "./dispatch";
import type { Env } from "./env";
import { expireOverdue } from "./expiry";
import { HttpError, json } from "./http";
import { notifyResponders, notifyResponse } from "./notify";
import { retention } from "./retention";
import { sweep } from "./sweep";

const ID = "([A-Za-z0-9_-]{1,128})";
const DISPATCH = new RegExp(`^/v1/requests/${ID}/dispatch$`);
const NOTIFY = new RegExp(`^/v1/requests/${ID}/responses/${ID}/notify$`);
const NOTIFY_RESPONDERS = new RegExp(`^/v1/requests/${ID}/notify-responders$`);

/** Must match wrangler.jsonc "triggers.crons" (test/index.test.ts checks). */
export const CRONS = {
  sweep: "*/5 * * * *",
  expiry: "7 * * * *",
  retention: "0 3 * * *", // 09:00 in Dhaka (UTC+6)
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
      if (!d && !n && !nr) throw new HttpError(404, "not found");
      if (req.method !== "POST") throw new HttpError(405, "method not allowed");
      const user = await authenticate(req, env);
      if (d) return await dispatch(env, user, d[1]);
      if (n) return await notifyResponse(env, user, n[1], n[2]);
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
      else console.error(JSON.stringify({ unknownCron: event.cron }));
    } catch (e) {
      console.error(JSON.stringify({ cron: event.cron, error: String(e) }));
      throw e; // shows the run as failed in the dashboard
    }
  },
};
