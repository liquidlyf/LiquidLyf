// LiquidLyf Worker (Cloudflare Workers Free). Phase 2: health check + dispatch (first tier).
// Phase 5 adds /notify and the crons (escalation sweep, expiry, retention).
import { authenticate } from "./auth";
import { dispatch } from "./dispatch";
import type { Env } from "./env";
import { HttpError, json } from "./http";

const DISPATCH = /^\/v1\/requests\/([A-Za-z0-9_-]{1,128})\/dispatch$/;

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(req.url);
    try {
      if (pathname === "/health") {
        if (req.method !== "GET") throw new HttpError(405, "method not allowed");
        return json({ ok: true, service: "liquidlyf-worker" });
      }
      const m = DISPATCH.exec(pathname);
      if (!m) throw new HttpError(404, "not found");
      if (req.method !== "POST") throw new HttpError(405, "method not allowed");
      const user = await authenticate(req, env);
      return await dispatch(env, user, m[1]);
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.message }, e.status);
      console.error(JSON.stringify({ error: String(e) }));
      return json({ error: "internal error" }, 500);
    }
  },
};
