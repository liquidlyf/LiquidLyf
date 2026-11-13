// FCM HTTP v1 sends to a topic condition: one call per condition, whatever the number
// of subscribers, and no Firestore reads for device tokens (doc 10 §6).
import { emulatorMode, type Env } from "./env";

export type SendResult = "ok" | "retry" | "fatal";

export interface TopicMessage {
  condition: string;
  /** Data-message values must be strings. Public data only: anyone can subscribe to a topic. */
  data: Record<string, string>;
  /** Pending messages with the same key replace each other on the device. */
  collapseKey?: string;
  ttlSeconds?: number;
}

export const fcmUrl = (projectId: string) => `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`;

/** The messages:send body. Pure: shared with the Apps Script backup. */
export function fcmRequestBody(msg: TopicMessage) {
  return {
    message: {
      condition: msg.condition,
      data: msg.data, // the app renders the text in the user's language
      android: {
        priority: "HIGH",
        ttl: `${msg.ttlSeconds ?? 21600}s`,
        ...(msg.collapseKey ? { collapse_key: msg.collapseKey } : {}),
      },
    },
  };
}

/** 2xx ok; 429/5xx worth retrying; anything else (bad condition or payload, token, missing role) is fatal. */
export function fcmOutcome(status: number): SendResult {
  if (status >= 200 && status < 300) return "ok";
  if (status === 429 || status >= 500) return "retry";
  return "fatal";
}

/** What emulator mode logs instead of sending (there is no FCM emulator). */
export const fcmDryRun = (msg: TopicMessage) =>
  JSON.stringify({ fcmDryRun: true, condition: msg.condition, data: msg.data });

export async function sendToCondition(env: Env, accessToken: string, msg: TopicMessage): Promise<SendResult> {
  if (emulatorMode(env)) {
    console.log(fcmDryRun(msg));
    return "ok";
  }
  const res = await fetch(fcmUrl(env.FIREBASE_PROJECT_ID), {
    method: "POST",
    headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
    body: JSON.stringify(fcmRequestBody(msg)),
  });
  const outcome = fcmOutcome(res.status);
  if (outcome === "fatal") console.error(JSON.stringify({ fcmError: res.status, body: (await res.text()).slice(0, 300) }));
  return outcome;
}
