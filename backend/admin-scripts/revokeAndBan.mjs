// Suspends an account (phase-07 7.4): sets the custom claim `banned: true` and revokes its
// refresh tokens, so the app is signed out within the hour (at its next token refresh) and,
// signed in again, can't post requests or offer. --unban removes the claim.
//
// A banned user can still read, and can still delete their account (write contract 16).
// For a full lock-out, also disable the account: Firebase console → Authentication → the user →
// Disable account. Their open requests stay until an admin acts in the Moderation screen.
// Run `node revokeAndBan.mjs` for the options; README.md has the steps.
import { connect, describeUser, findUser, parseArgs, run, showClaims, withClaim } from "./lib/admin.mjs";

const usage = [
  "node revokeAndBan.mjs (--email <email> | --uid <uid>) [--unban] [--apply] (--emulator | --key <key.json>)",
  '  sets the "banned" claim and signs the account out everywhere; --unban removes the claim.',
  "  Without --apply it only shows what would change.",
].join("\n");

run(async () => {
  const args = parseArgs(process.argv.slice(2), { flags: ["unban"] });
  const { auth, db, apply } = connect(args);
  const user = await findUser(auth, args);
  console.log(`User: ${describeUser(user)}`);

  // Read-only context for the decision: what this account has in the app right now.
  // One equality filter: served by Firestore's automatic index, no composite index needed.
  const mine = await db.collection("requests").where("requesterUid", "==", user.uid).select("status").get();
  const openCount = mine.docs.filter((d) => ["OPEN", "REMOVED"].includes(d.get("status"))).length;
  const card = await db.doc(`donors/${user.uid}`).get();
  console.log(`Requests: ${mine.size}, of which open or hidden: ${openCount}; listed as a donor: ${card.exists ? "yes" : "no"}`);

  const before = user.customClaims ?? {};
  const after = withClaim(user, "banned", args.unban ? undefined : true);
  console.log(`Claims now:   ${showClaims(before)}`);
  console.log(`Claims after: ${showClaims(after)}`);
  const claimChanges = showClaims(before) !== showClaims(after);
  if (!claimChanges && args.unban) {
    console.log("Nothing to change: the account isn't banned.");
    return 0;
  }
  if (!apply) {
    console.log(args.unban
      ? "Dry run: nothing changed. Add --apply to remove the ban."
      : "Dry run: nothing changed. Add --apply to set the ban and sign the account out everywhere.");
    return 0;
  }

  if (claimChanges) await auth.setCustomUserClaims(user.uid, after);
  if (args.unban) {
    console.log("OK: ban removed. It takes effect at the app's next token refresh (within 1 hour).");
    if (user.disabled) console.log("The account is still disabled in the console: enable it there if you disabled it.");
    return 0;
  }
  // Revoke after the claim, so any token issued from now on carries banned: true.
  await auth.revokeRefreshTokens(user.uid);
  const now = await auth.getUser(user.uid);
  console.log(`OK: banned, and signed out everywhere (tokens valid after ${now.tokensValidAfterTime}).`);
  console.log("The app keeps its current token for up to 1 hour; after that it must sign in again, and the rules refuse its posts and offers.");
  if (openCount > 0) console.log("Their open or hidden requests are unchanged: hide or cancel them in the Moderation screen.");
  return 0;
}, usage);
