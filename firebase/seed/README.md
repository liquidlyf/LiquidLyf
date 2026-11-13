# Demo seed data

Synthetic data for the Firebase emulators (project `demo-liquidlyf`). Nothing here is real:
- "Demo" names;
- `example.test` emails;
- `+88017000000xx` phone numbers;
- made-up hospitals.

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

## Notes
- **The snapshot's requests expire after a few days**, because they carry the date they were generated. With the emulators running, run `npm run seed` in a second terminal for fresh ones.
- **Area codes are the HDX pcodes from `app/src/main/assets/bd-geo.json`**, for example Dhaka South City Corporation `BD30262000`. Each city corporation is one area. If a code changes there, update `AREAS` in `seed.mjs` and re-run `npm run seed:export`.
- **`rules-tests/seed.test.js` checks that the seed stays valid** under the security rules. If a schema change breaks it, update `seed.mjs`.
