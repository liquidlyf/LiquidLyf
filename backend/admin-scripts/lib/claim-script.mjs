// One custom claim on or off (setAdmin.mjs, setCoordinator.mjs). Other claims are kept.
import { connect, describeUser, findUser, parseArgs, run, showClaims, withClaim } from "./admin.mjs";

export function claimScript({ file, claim, what, note }) {
  const usage = [
    `node ${file} (--email <email> | --uid <uid>) [--remove] [--apply] (--emulator | --key <key.json>)`,
    `  gives the account the "${claim}" claim (${what}); --remove takes it away again.`,
    "  Without --apply it only shows what would change.",
  ].join("\n");

  run(async () => {
    const args = parseArgs(process.argv.slice(2), { flags: ["remove"] });
    const { auth, apply } = connect(args);
    const user = await findUser(auth, args);
    console.log(`User: ${describeUser(user)}`);
    if (!args.remove && !user.emailVerified && !user.providerData.some((p) => p.providerId === "google.com")) {
      console.log("Warning: this account's email isn't verified, so the rules still refuse most of its writes.");
    }

    const before = user.customClaims ?? {};
    const after = withClaim(user, claim, args.remove ? undefined : true);
    console.log(`Claims now:   ${showClaims(before)}`);
    console.log(`Claims after: ${showClaims(after)}`);
    if (showClaims(before) === showClaims(after)) {
      console.log("Nothing to change.");
      return 0;
    }
    if (!apply) {
      console.log("Dry run: nothing changed. Add --apply to do it.");
      return 0;
    }
    await auth.setCustomUserClaims(user.uid, after);
    console.log(`OK: "${claim}" ${args.remove ? "removed" : "set"}.`);
    console.log("It takes effect when the app refreshes its sign-in token (within 1 hour), or at once after signing out and in again.");
    if (note) console.log(note);
    return 0;
  }, usage);
}
