# backend/admin-scripts

Small Node scripts a maintainer runs on their own PC (phase-07 7.4). They use the official Firebase Admin SDK, which is free on the Spark plan; no Cloud Functions.

| Script | Does |
|---|---|
| `setAdmin.mjs` | Gives an account the `admin` claim (moderator), or takes it away with `--remove` |
| `setCoordinator.mjs` | The `coordinator` claim (club coordinator, v1.1), or `--remove` |
| `revokeAndBan.mjs` | Suspends an account: the `banned` claim + signs it out everywhere. `--unban` removes the claim |
| `deleteUserData.mjs` | Deletes an account for a deletion request sent by email or the website (write contract 16 in `firebase/README.md`) |
| `listAdmins.mjs` | Lists every account with a claim (moderators, coordinators, banned). Read-only. Shows uids, not emails, unless you add `--emails` |

**The Admin SDK bypasses the security rules.** So every script:
- is a **dry run unless you add `--apply`**: it shows what it would change, and changes nothing (`listAdmins.mjs` only reads, so it has no `--apply`);
- needs `--emulator` (the local emulators, no key) or `--key <file>` (production);
- refuses a key file inside the repo, and never prints the key;
- refuses unknown options, so a typo like `--aply` can't change what it does.

Every script keeps the account's other claims. A claim takes effect when the app refreshes its sign-in token (within 1 hour), or at once after signing out and in again.

## Set up (once)
Node 22.12 or newer. In this folder:

```powershell
npm.cmd ci
```

## Try it on the emulators first
Start the emulators in another window (`cd ..\..\firebase`, then `npm.cmd run emulators`), then for example:

```powershell
node setAdmin.mjs --emulator --email donor@example.test            # dry run
node setAdmin.mjs --emulator --email donor@example.test --apply
```

## Production: the key
The scripts sign in with the key of the service account **`liquidlyf-admin`**, with only two roles: **Firebase Authentication Admin** and **Cloud Datastore User**. Make a new key each time you need a script on production, and delete it afterwards:

1. console.cloud.google.com → project `liquidlyf-app` → IAM & Admin → Service accounts. The first time only: **Create service account** `liquidlyf-admin` with the two roles above.
2. Open `liquidlyf-admin` → Keys → Add key → Create new key → JSON. It downloads as **`liquidlyf-app-<key id>.json`** (for example `liquidlyf-app-1a2b3c4d5e6f.json`). Leave it **outside the repo** (Downloads is fine), and never paste it anywhere.
3. Run the script (below).
4. When you're done, delete **both**: the key file in Downloads **and** the key under `liquidlyf-admin` → Keys (trash icon). Deleting only the file leaves the key valid. Keep the service account itself for next time.

```powershell
node revokeAndBan.mjs --key "$HOME\Downloads\liquidlyf-app-<key id>.json" --email someone@gmail.com          # dry run
node revokeAndBan.mjs --key "$HOME\Downloads\liquidlyf-app-<key id>.json" --email someone@gmail.com --apply
```

The first line printed says which project it is connected to, and as which account. Check it says `liquidlyf-app (PRODUCTION), as liquidlyf-admin@…` before you add `--apply`.

## How to handle each case

**Make someone a moderator:**
1. They need a LiquidLyf account first: signed in to the app at least once, with Google or a verified email. (For an account whose email isn't verified, the script warns you: the rules still refuse most of its writes.)
2. Dry run: `setAdmin.mjs --key <key file> --email <their email>`. Check the project line, the account (`User:`), and `Claims after:` with `"admin":true`.
3. The same command with `--apply`.
4. They sign out and in again. Settings then shows "Moderation", and their phone gets new-report alerts.

**Remove a moderator:**
1. Dry run: `setAdmin.mjs --key <key file> --email <their email> --remove`, then the same with `--apply`. Their other claims stay.
2. It takes effect when their app refreshes its sign-in token, **within 1 hour**. Until then, the token their app already has still works, so they can still use Moderation. Nothing ends it sooner: Firestore accepts a token until it expires. Every action they take meanwhile is in the admin log.
3. If the account itself may be compromised, also disable it in the Firebase console → Authentication → the user → Disable account. Then it can't sign in or get a new token at all; the current token still runs out within the hour. Enable it again later if they should keep a normal account.

**See who is a moderator:**
- All of them: `listAdmins.mjs --key <key file>` prints each account with a claim (`admin`, `coordinator`, `banned`) by uid. Add `--emails` to see their email addresses too; leave it off when you share the output.
- One account: a dry run of `setAdmin.mjs` shows its `Claims now:` line and changes nothing.

**Suspend someone** (scams, harassment):
1. `revokeAndBan.mjs --email <email> --apply`. Within an hour, the app must sign in again, and from then on the rules refuse their posts and offers. They can still read, and they can still delete their account.
2. Their open requests stay: hide or cancel them in the Moderation screen.
3. For a full lock-out, also disable the account in the Firebase console → Authentication → the user → Disable account.

**A deletion request** by email or the website form:
1. **Check it's really them.** Act only on a request sent from the account's own email address. Otherwise, reply to that address and wait for the answer, so nobody can get someone else's account deleted.
2. Dry run: `deleteUserData.mjs --email <email>`. It lists their offers, requests, contacts, donations and inbox.
3. `deleteUserData.mjs --email <email> --apply`. If it stops halfway (for example, the network drops), run the same command again: it continues where it stopped. If the sign-in account is already gone, use `--uid <uid>` instead of `--email`.
4. Reply that it's done. Keep the email thread as your record.

What happens to the data:
- **Deleted:** the profile, donor card, counters, donation history, inbox, and the private contacts of their requests (patient, phone, ward), then the sign-in account.
- **Kept, anonymized:** their requests and offers stay for the other people involved, as "Deleted user", without notes, closing reasons, phone numbers or messages. An open or hidden request is cancelled, and an active offer is withdrawn.
- **Kept with the admins:** reports they filed or that name them, and admin-log entries, both deleted after 1 year by the Worker's clean-up.
- **Not reachable from here:** inbox items they sent to other people (`fromUid`, no text). They're deleted after 60 days by the clean-up.

## Tests
`npm test` runs every script against the emulators (phase-07 7.9): the dry runs, `--apply`, a second run of a deletion, the read-only list, and the safety checks. The emulators need Java 21; on this PC, point `JAVA_HOME` at Android Studio's `jbr` and put its `bin` first on `PATH`.
