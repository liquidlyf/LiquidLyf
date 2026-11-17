# Demo seed data

Synthetic data for the Firebase emulators (project `demo-liquidlyf`). Nothing here is real:
- "Demo" names;
- `example.test` emails;
- `+88017000000xx` phone numbers.

**Never copy real user data into this folder.**

| Path | What |
|---|---|
| `seed.mjs` | The source of truth. It writes everything with times relative to "now" |
| `emulator-data/` | The committed snapshot that `npm run emulators` imports. Regenerate it with `npm run seed:export` |

## Sample accounts

All passwords are `password123`, and every email is already verified.

| Email | Who |
|---|---|
| `demo-requester@example.test` | Requester, B+, Dhaka. 2 open requests: one has a confirmed donor and an offered donor; the other is a critical O− request |
| `demo-donor-bpos@example.test` | Listed B+ donor, Dhaka. Confirmed on the B+ request |
| `demo-donor-oneg@example.test` | Listed O− donor, Dhaka |
| `demo-donor-apos@example.test` | Listed A+ donor, Mirpur. Donated 40 days ago, so not eligible yet. Owns an AB+ request that accepts compatible blood |
| `demo-donor-abpos@example.test` | Listed AB+ donor, set to unavailable |
| `demo-donor-ctg@example.test` | Listed B+ donor, Chattogram. Offered on the B+ request |
| `demo-new@example.test` | 2-day-old account (limit: 1 open request), with one money-word-flagged request |
| `demo-admin@example.test` | Has the `admin` custom claim |
| `demo-requester-only@example.test` | The short setup's profile: name, phone and area (Mirpur) only. No blood group, gender, birth year or weight, so unlisted and unavailable until "Become a donor" (D1) |

## Notes
- **The snapshot's requests expire after a few days**, because they carry the date they were generated. With the emulators running, run `npm run seed` in a second terminal for fresh ones.
- **Area codes are the HDX pcodes from `app/src/main/assets/bd-geo.json`**, for example Dhaka South City Corporation `BD30262000`. Each city corporation is one area. If a code changes there, update `AREAS` in `seed.mjs` and re-run `npm run seed:export`.
- **`rules-tests/seed.test.js` checks that the seed stays valid** under the security rules. If a schema change breaks it, update `seed.mjs`.

## The real hospital list (`hospitals.csv`)
This one is real data, not demo data. It's the list the app searches when someone posts a request (plan D14).

| Path | What |
|---|---|
| `hospitals.csv` | **The only source.** One row per hospital: English and Bangla name, type, district and upazila codes (from `bd-geo.json`), short address, optional map point |
| `hospitals.mjs` | Checks the CSV and writes `hospitals.json`. `--check` only compares |
| `hospitals.json` | Generated, never edited by hand. The app copies it into `app/src/main/assets/`, and the website serves it as `data/hospitals.json`, which phones fetch once a day when it changed |

To change the list:
1. Edit `hospitals.csv`, and raise `# version:` by 1. Versions never go down: to undo a change, put the old rows back as a new version.
2. Run `node seed/hospitals.mjs` in `firebase/`. It refuses a list that phones would throw away: an empty one, one under 80% of the current list, a bad code, or a partial map point.
3. `npm run test:rules` also runs `rules-tests/hospitals.test.js`.

Rules for the rows:
- **Map points come only from OpenStreetMap.** Copy `lat`/`lng` from the OSM feature named in `osmId`, never from Google Maps (its terms forbid it). A hospital that isn't in OSM gets no point (no "Open in Maps") until someone adds it to OSM.
- **The list is ODbL 1.0** because of those points: "Hospital locations © OpenStreetMap contributors". The app's code stays AGPL.
- **Check each name and address** against the hospital's own website or an official gov.bd page, and put that link in `source`. Only rows with `verified=yes` are published.
- **No phone numbers.** **Hospitals only:** no blood banks or blood centres, not even non-profit ones (the maintainer, 18 Nov 2026). `hasBloodBank` marks a hospital with its own blood bank.
