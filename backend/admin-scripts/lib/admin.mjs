// Shared code for the maintainer scripts: arguments, the connection (emulators or a production
// key), finding the user, and the custom-claim helpers.
//
// The Admin SDK bypasses the security rules, so every script:
// - is a dry run unless you pass --apply (it prints what it would change);
// - needs either --emulator (no key) or --key <file> for production;
// - refuses a key file inside the repo, so it can't be committed by accident;
// - never prints the key.
import { readFileSync, realpathSync } from "node:fs";
import { relative, resolve, isAbsolute } from "node:path";
import { fileURLToPath } from "node:url";
import { initializeApp, cert } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";

export const EMULATOR_PROJECT = "demo-liquidlyf";
const REPO_ROOT = fileURLToPath(new URL("../../../", import.meta.url));
const ID = /^[A-Za-z0-9_-]{1,128}$/;
const EMAIL = /^[^@\s]{1,64}@[^@\s]{1,255}$/;

export class UsageError extends Error {}

/**
 * Parses `--name value` and `--flag` options. Anything not in `values` or `flags` is an error,
 * so a typo such as `--aply` can't silently turn a real run into a dry run or the other way.
 * `user: false` is for scripts that act on no single account (no --uid/--email);
 * `readOnly: true` for scripts that never write (no --apply).
 */
export function parseArgs(argv, { values = [], flags = [], user = true, readOnly = false } = {}) {
  const known = new Set([...values, ...flags, "uid", "email", "key", "emulator", "apply"]);
  const takesValue = new Set([...values, "uid", "email", "key"]);
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith("--")) throw new UsageError(`unexpected "${a}"`);
    const name = a.slice(2);
    if (!known.has(name)) throw new UsageError(`unknown option --${name}`);
    if (takesValue.has(name)) {
      const v = argv[++i];
      if (v === undefined || v.startsWith("--")) throw new UsageError(`--${name} needs a value`);
      out[name] = v;
    } else {
      out[name] = true;
    }
  }
  if (out.emulator && out.key) throw new UsageError("pass --emulator or --key, not both");
  if (!out.emulator && !out.key) throw new UsageError("pass --emulator (local emulators) or --key <file> (production)");
  if (readOnly && out.apply) throw new UsageError("this script only reads: there is nothing to --apply");
  if (!user) {
    if (out.uid || out.email) throw new UsageError("this script takes no --uid or --email");
    return out;
  }
  if (!!out.uid === !!out.email) throw new UsageError("pass exactly one of --uid or --email");
  if (out.uid && !ID.test(out.uid)) throw new UsageError("--uid doesn't look like a uid");
  if (out.email && !EMAIL.test(out.email)) throw new UsageError("--email doesn't look like an email address");
  return out;
}

/** Connects to the emulators or to the key's project. Prints which one, never the key. */
export function connect(args, { readOnly = false } = {}) {
  let app, project;
  if (args.emulator) {
    process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8085";
    process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9099";
    project = EMULATOR_PROJECT;
    app = initializeApp({ projectId: project });
    console.log(`Project: ${project} (local emulators)`);
  } else {
    // With these set, the SDK would quietly talk to the emulators instead of the key's project.
    for (const v of ["FIRESTORE_EMULATOR_HOST", "FIREBASE_AUTH_EMULATOR_HOST"]) {
      if (process.env[v]) throw new UsageError(`${v} is set: unset it for production, or pass --emulator`);
    }
    const key = readKey(args.key);
    project = key.project_id;
    app = initializeApp({ credential: cert(key), projectId: project });
    console.log(`Project: ${project} (PRODUCTION), as ${key.client_email}`);
  }
  console.log(readOnly ? "Mode: read-only (this script never changes anything)"
    : args.apply ? "Mode: APPLY (changes will be written)" : "Mode: dry run (nothing is changed; add --apply to do it)");
  return { app, project, auth: getAuth(app), db: getFirestore(app), apply: args.apply === true, emulator: !!args.emulator };
}

function readKey(path) {
  let real;
  try {
    real = realpathSync(resolve(path));
  } catch {
    throw new UsageError(`key file not found: ${path}`);
  }
  const rel = relative(realpathSync(REPO_ROOT), real);
  if (!rel.startsWith("..") && !isAbsolute(rel)) {
    throw new UsageError("the key file is inside the repo: move it outside (for example to your Downloads) so it can't be committed");
  }
  let key;
  try {
    key = JSON.parse(readFileSync(real, "utf8"));
  } catch {
    throw new UsageError("the key file isn't valid JSON");
  }
  if (key.type !== "service_account" || !key.project_id || !key.client_email || !key.private_key) {
    throw new UsageError("the key file isn't a service-account key");
  }
  return key;
}

/**
 * Finds the Auth user by --uid or --email. Returns null if there is none and `optional` is set
 * (deleteUserData can still clean up the data of an Auth user that is already gone).
 */
export async function findUser(auth, args, { optional = false } = {}) {
  try {
    return args.uid ? await auth.getUser(args.uid) : await auth.getUserByEmail(args.email);
  } catch (e) {
    if (e?.code === "auth/user-not-found") {
      if (optional && args.uid) return null;
      throw new UsageError(`no account with that ${args.uid ? "uid" : "email"}`);
    }
    throw e;
  }
}

export function describeUser(user) {
  const providers = user.providerData.map((p) => p.providerId).join(", ") || "none";
  return `${user.uid} <${user.email ?? "no email"}>, sign-in: ${providers}, verified: ${user.emailVerified}, disabled: ${user.disabled}`;
}

/** The user's claims with `name` set (value) or removed (undefined). Other claims are kept. */
export function withClaim(user, name, value) {
  const claims = { ...(user.customClaims ?? {}) };
  if (value === undefined) delete claims[name];
  else claims[name] = value;
  return claims;
}

export const showClaims = (c) => (c && Object.keys(c).length ? JSON.stringify(c) : "{}");

/** Runs main(); a UsageError exits 2 with its message, anything else exits 1. */
export function run(main, usage) {
  main().then(
    (code) => process.exit(code ?? 0),
    (e) => {
      if (e instanceof UsageError) {
        console.error(`${e.message}\n\n${usage}`);
        process.exit(2);
      }
      console.error(`FAILED: ${e?.message ?? e}`);
      process.exit(1);
    },
  );
}
