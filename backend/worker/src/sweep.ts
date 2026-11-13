// Escalation sweep, cron */5 (phase-05 5.5, doc 10 §6.4). The logic lives in escalation.ts,
// shared with the Apps Script backup; this file only performs its steps with fetch.
import { dailyAlertCap } from "./cap";
import type { Env } from "./env";
import { type Step, sweepSteps, type SweepResult } from "./escalation";
import { sendToCondition } from "./fcm";
import { Firestore } from "./firestore";
import { getAccessToken } from "./google-auth";

export {
  CAPPED, planEscalation, RECHECK_MINUTES, SEND_BUDGET, SWEEP_FIELDS, SWEEP_LIMIT, type Plan, type SweepResult,
} from "./escalation";

export async function sweep(env: Env, now = new Date()): Promise<SweepResult> {
  const token = await getAccessToken(env);
  const fs = new Firestore(env, token);
  const perform = (s: Step): Promise<unknown> => {
    switch (s.op) {
      case "query": return fs.runQuery(s.query);
      case "count": return fs.count(s.query, s.upTo);
      case "batchWrite": return fs.batchWrite(s.writes);
      case "send": return sendToCondition(env, token, { condition: s.condition, data: s.data, collapseKey: s.collapseKey });
    }
  };
  const steps = sweepSteps({ now, dueBefore: now, minutes: env.ESCALATION_MINUTES, cap: dailyAlertCap(env), names: fs });
  const out = await drive(steps, perform);
  console.log(JSON.stringify({ sweep: out }));
  return out;
}

/** Runs a step generator, answering each step with `perform`; a failed step is thrown back into it. */
export async function drive<S, R>(gen: Generator<S, R, any>, perform: (s: S) => Promise<unknown>): Promise<R> {
  let step = gen.next();
  while (!step.done) {
    let result: unknown;
    try {
      result = await perform(step.value);
    } catch (e) {
      step = gen.throw(e);
      continue;
    }
    step = gen.next(result);
  }
  return step.value;
}
