// Makes an account an admin (custom claim `admin: true`, phase-07 7.4), or removes it with --remove.
// Admins can read reports and the admin log, and act on reports in the hidden Moderation screen.
// Run `node setAdmin.mjs` for the options; README.md has the steps.
import { claimScript } from "./lib/claim-script.mjs";

claimScript({
  file: "setAdmin.mjs",
  claim: "admin",
  what: "moderator: reads reports, hides, restores or cancels requests",
  note: "Admin devices subscribe to the `admins` topic for new-report alerts once the app sees the claim.",
});
