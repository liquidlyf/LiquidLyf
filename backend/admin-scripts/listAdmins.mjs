// Lists the accounts that have a custom claim: moderators (`admin`), coordinators and banned
// accounts. Read-only: it never changes anything, so it takes no --apply.
// Prints uids, claims and sign-in methods; emails only with --emails, so the output can be
// shared without showing who people are.
// Run `node listAdmins.mjs` for the options; README.md has the steps.
import { connect, parseArgs, run, showClaims } from "./lib/admin.mjs";

const usage = [
  "node listAdmins.mjs [--emails] (--emulator | --key <key.json>)",
  "  lists every account with a claim (admin, coordinator, banned). Read-only.",
  "  --emails also prints each account's email address.",
].join("\n");

run(async () => {
  const args = parseArgs(process.argv.slice(2), { flags: ["emails"], user: false, readOnly: true });
  const { auth } = connect(args, { readOnly: true });

  let total = 0;
  const found = [];
  let pageToken;
  do {
    const page = await auth.listUsers(1000, pageToken);
    total += page.users.length;
    for (const u of page.users) if (u.customClaims && Object.keys(u.customClaims).length) found.push(u);
    pageToken = page.pageToken;
  } while (pageToken);

  for (const u of found) {
    const providers = u.providerData.map((p) => p.providerId).join(", ") || "none";
    const email = args.emails ? ` <${u.email ?? "no email"}>` : "";
    console.log(`${u.uid}${email}  claims: ${showClaims(u.customClaims)}  sign-in: ${providers}${u.disabled ? "  DISABLED" : ""}`);
  }
  const count = (claim) => found.filter((u) => u.customClaims[claim]).length;
  console.log(`${found.length} of ${total} accounts have a claim: ${count("admin")} admin, ${count("coordinator")} coordinator, ${count("banned")} banned.`);
  return 0;
}, usage);
