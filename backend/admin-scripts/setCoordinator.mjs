// Makes an account a club coordinator (custom claim `coordinator: true`, phase-07 7.10, v1.1),
// or removes it with --remove. Coordinators will be able to mark members and requests
// "Verified by <club>". Run `node setCoordinator.mjs` for the options; README.md has the steps.
import { claimScript } from "./lib/claim-script.mjs";

claimScript({
  file: "setCoordinator.mjs",
  claim: "coordinator",
  what: "club coordinator: can vouch for members and requests (v1.1)",
});
