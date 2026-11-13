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

export async function sendToCondition(env: Env, accessToken: string, msg: TopicMessage): Promise<SendResult> {
  if (emulatorMode(env)) {
    // There is no FCM emulator: log what would be sent.
    console.log(JSON.stringify({ fcmDryRun: true, condition: msg.condition, data: msg.data }));
    return "ok";
  }
  const res = await fetch(fcmUrl(env.FIREBASE_PROJECT_ID), {
    method: "POST",
    headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
    body: JSON.stringify({
      message: {
        condition: msg.condition,
        data: msg.data, // the app renders the text in the user's language
        android: {
          priority: "HIGH",
          ttl: `${msg.ttlSeconds ?? 21600}s`,
          ...(msg.collapseKey ? { collapse_key: msg.collapseKey } : {}),
        },
      },
    }),
  });
  if (res.ok) return "ok";
  if (res.status === 429 || res.status >= 500) return "retry";
  console.error(JSON.stringify({ fcmError: res.status, body: (await res.text()).slice(0, 300) }));
  return "fatal"; // 400 bad condition/payload, 401/403 token or missing FCM role
}
