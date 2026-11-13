# 0009: No paid SMS: trust without phone OTP

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

Blood-donor apps in Bangladesh commonly verify phone numbers with an SMS code (OTP). It's the usual
trust baseline, and also the most common complaint: codes that never arrive lock people out. Firebase
phone sign-in needs the paid Blaze plan and costs about US$0.18 per SMS to Bangladesh, which a free app
with no budget can't pay. Fake requests and scammers asking for money are the real threats to stop.

## Options considered

1. **SMS OTP for sign-in or posting:** familiar, but it needs a card on file and a running cost, and it
   locks people out when SMS fails.
2. **WhatsApp authentication messages:** much cheaper (about US$0.004 per message), but still paid,
   and it needs a business account.
3. **Layered free trust:** several free checks that together make abuse hard, with phone verification
   added later through a free channel.

## Decision

**No paid SMS in v1.** Trust comes from free layers:

1. Google sign-in or a verified email, required for everything.
2. App Check: only the genuine app on a real phone can call the backend.
3. Phone numbers are never public; they're shared only after a donor offers.
4. Structured requests (hospital, ward, units, "needed by"), a "genuine, no money" pledge, and
   payment words flagged automatically.
5. Limits by account age: 1 open request for accounts under 7 days old, then up to 3.
6. Report, automatic hiding after 3 different reporters, block, and moderator review.

v1.1 adds **free phone verification through Telegram** and **"verified by club"** vouching by partner
blood-donor clubs. Paid OTP stays behind a Remote Config switch, only if a sponsor pays for it.

## Consequences

- **Good:** no running cost, nobody is locked out by a missing SMS, and emergencies are never blocked.
- **Bad, or trade-offs:** in v1 a phone number isn't proven to belong to its owner. Requests from new or
  unverified accounts post, but are labelled and limited.
- **Follow-ups:** the money-word flag and the report reasons (Phase 7); the Telegram bot in the Worker
  (v1.1).
