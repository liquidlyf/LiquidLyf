// LiquidLyf escalation backup (phase-05 task 5.8, doc 10 §6.4). Google Apps Script, on the
// project account liquidlyf@gmail.com. Paste into the Apps Script file "Backup".
//
// Every 10 minutes it looks for OPEN requests overdue by more than 10 minutes, which happens only
// if the Worker's 5-minute sweep is down or behind, and runs the very same escalation:
// LiquidLyf.sweepSteps is the Worker's own code (Shared.js). Claims carry the doc's updateTime,
// so the Worker and this backup never alert twice for one tier.
//
// Its own service-account key (liquidlyf-backup), in the script property GCP_SA_JSON.
// Setup and key rotation: backend/apps-script/README.md.

var PROJECT_ID = "liquidlyf-app";
var ESCALATION_MINUTES = { CRITICAL: 15, URGENT: 30, PLANNED: 120 }; // = wrangler.jsonc (a test checks)
var DAILY_ALERT_CAP = 5;                                              // = wrangler.jsonc (a test checks)
var OVERDUE_MINUTES = 10;
var EVERY_MINUTES = 10;   // overdue + every <= 20 min: the phase-05 acceptance limit
var TOKEN_CACHE_KEY = "gcp_token";

/** The time trigger's handler. */
function backupSweep() {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(5000)) {
    console.log(JSON.stringify({ backupSweep: "previous run still going" }));
    return null;
  }
  try {
    var out = runSweep_(config_(), new Date());
    console.log(JSON.stringify({ backupSweep: out }));
    return out;
  } finally {
    lock.releaseLock();
  }
}

/** Run once by hand after pasting the files: checks the key, then installs the 10-minute trigger. */
function setup() {
  var cfg = config_();
  var token = accessToken_(cfg);
  perform_(cfg, token, { op: "query", query: { collection: "requests", limit: 1, select: ["status"] } });
  removeTrigger();
  ScriptApp.newTrigger("backupSweep").timeBased().everyMinutes(EVERY_MINUTES).create();
  console.log("OK: the key works for " + cfg.projectId + ". backupSweep now runs every " + EVERY_MINUTES + " minutes.");
}

/** Stops the backup (for example before deleting its key). */
function removeTrigger() {
  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === "backupSweep") ScriptApp.deleteTrigger(t);
  });
}

// ---------------------------------------------------------------- internals

function runSweep_(cfg, now) {
  var token = accessToken_(cfg);
  var steps = LiquidLyf.sweepSteps({
    now: now,
    dueBefore: new Date(now.getTime() - OVERDUE_MINUTES * 60000),
    minutes: ESCALATION_MINUTES,
    cap: DAILY_ALERT_CAP,
    names: LiquidLyf.docNames(cfg.projectId),
  });
  // The same loop as the Worker's drive(), but synchronous: UrlFetchApp blocks.
  var step = steps.next();
  while (!step.done) {
    var result;
    var failed = false;
    var error;
    try {
      result = perform_(cfg, token, step.value);
    } catch (e) {
      failed = true;
      error = e;
    }
    step = failed ? steps.throw(error) : steps.next(result);
  }
  return step.value;
}

function config_() {
  var props = PropertiesService.getScriptProperties();
  var projectId = props.getProperty("FIREBASE_PROJECT_ID") || PROJECT_ID;
  // Tests only: the Firestore emulator. Refused for real projects, like the Worker's emulator mode.
  var emulator = props.getProperty("FIRESTORE_EMULATOR_HOST") || "";
  if (emulator && projectId.indexOf("demo-") !== 0) {
    throw new Error("FIRESTORE_EMULATOR_HOST is set for a non-demo project");
  }
  var sa = null;
  if (!emulator) {
    var json = props.getProperty("GCP_SA_JSON");
    if (!json) throw new Error("Script property GCP_SA_JSON is missing (see backend/apps-script/README.md)");
    sa = JSON.parse(json);
    if (sa.project_id !== projectId) {
      throw new Error("GCP_SA_JSON is a key for project " + sa.project_id + ", not " + projectId);
    }
  }
  return { projectId: projectId, emulator: emulator, sa: sa };
}

/** An OAuth token for Firestore + FCM, minted from the key and cached for ~50 minutes. */
function accessToken_(cfg) {
  if (cfg.emulator) return "owner"; // the emulator's admin token
  var cache = CacheService.getScriptCache();
  var cached = cache.get(TOKEN_CACHE_KEY);
  if (cached) return cached;

  var nowSec = Math.floor(Date.now() / 1000);
  var input = b64url_(JSON.stringify({ alg: "RS256", typ: "JWT" })) + "." +
    b64url_(JSON.stringify(LiquidLyf.jwtClaims(cfg.sa.client_email, nowSec)));
  var assertion = input + "." + b64url_(Utilities.computeRsaSha256Signature(input, cfg.sa.private_key));
  var res = UrlFetchApp.fetch(LiquidLyf.TOKEN_URL, {
    method: "post",
    contentType: "application/x-www-form-urlencoded",
    payload: "grant_type=" + encodeURIComponent(LiquidLyf.JWT_GRANT) + "&assertion=" + assertion,
    muteHttpExceptions: true,
  });
  // The body may echo details: don't log it.
  if (res.getResponseCode() !== 200) throw new Error("token endpoint " + res.getResponseCode());
  var body = JSON.parse(res.getContentText());
  cache.put(TOKEN_CACHE_KEY, body.access_token, Math.max(60, Math.min(Number(body.expires_in) - 600, 3000)));
  return body.access_token;
}

/** Utilities' web-safe base64 keeps the "=" padding; JWTs drop it. */
function b64url_(data) {
  return Utilities.base64EncodeWebSafe(data).replace(/=+$/, "");
}

/** Performs one step of LiquidLyf.sweepSteps and returns what the step expects. */
function perform_(cfg, token, step) {
  var host = cfg.emulator ? "http://" + cfg.emulator : "https://firestore.googleapis.com";
  var base = host + "/v1/" + LiquidLyf.docNames(cfg.projectId).root;
  switch (step.op) {
    case "query":
      return LiquidLyf.parseQueryRows(post_(base + ":runQuery", LiquidLyf.queryBody(step.query), token));
    case "count":
      return LiquidLyf.parseCount(post_(base + ":runAggregationQuery", LiquidLyf.countBody(step.query, step.upTo), token));
    case "batchWrite":
      return LiquidLyf.parseBatchWrite(post_(base + ":batchWrite", { writes: step.writes }, token), step.writes.length);
    case "send":
      return send_(cfg, token, step);
  }
  throw new Error("unknown step " + step.op);
}

function post_(url, body, token) {
  var res = UrlFetchApp.fetch(url, {
    method: "post",
    contentType: "application/json",
    payload: JSON.stringify(body),
    headers: { Authorization: "Bearer " + token },
    muteHttpExceptions: true,
  });
  var code = res.getResponseCode();
  if (code < 200 || code >= 300) {
    var status = "";
    try { status = JSON.parse(res.getContentText()).error.status || ""; } catch (e) { /* not JSON */ }
    throw new Error("Firestore " + code + " " + status);
  }
  return JSON.parse(res.getContentText());
}

function send_(cfg, token, step) {
  var msg = { condition: step.condition, data: step.data, collapseKey: step.collapseKey };
  if (cfg.emulator) {
    console.log(LiquidLyf.fcmDryRun(msg)); // there is no FCM emulator
    return "ok";
  }
  var res = UrlFetchApp.fetch(LiquidLyf.fcmUrl(cfg.projectId), {
    method: "post",
    contentType: "application/json",
    payload: JSON.stringify(LiquidLyf.fcmRequestBody(msg)),
    headers: { Authorization: "Bearer " + token },
    muteHttpExceptions: true,
  });
  var outcome = LiquidLyf.fcmOutcome(res.getResponseCode());
  if (outcome === "fatal") {
    console.error(JSON.stringify({ fcmError: res.getResponseCode(), body: res.getContentText().slice(0, 300) }));
  }
  return outcome;
}
