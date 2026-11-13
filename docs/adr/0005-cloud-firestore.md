# 0005: Cloud Firestore instead of the Realtime Database

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

The university version stored everything in the Firebase Realtime Database (RTDB). Matching donors to a
request needs queries such as "blood group in [A+, A−] **and** district is X **and** status is open,
sorted by when the blood is needed". RTDB can sort and filter by only one field at a time, so the app
had to download too much and filter on the phone. Its security rules also made it hard to keep phone
numbers private. There was no production data, so changing the database cost nothing.

## Options considered

1. **Keep RTDB:** no migration, but the matching queries don't fit, and private fields would need
   awkward copies of the data.
2. **Cloud Firestore:** queries on several fields with indexes, documents and subcollections that map
   well to "public request + private contact", richer security rules, and a built-in offline cache.

## Decision

The rebuilt app uses **Cloud Firestore**. The data model and rules are in `firebase/`: public
`requests` and `donors` documents, with phone numbers only in protected sub-documents. Every rule has a
test that runs in CI.

## Consequences

- **Good:** the matching queries work directly; private and public data are separate documents; the
  offline cache replaces the old hand-made cache.
- **Bad, or trade-offs:** Firestore bills per document read, and the free plan allows 50,000 reads a
  day. Careless listeners or unpaged lists would use that up quickly.
- **Follow-ups:**
  - the read-budget rules in [docs/architecture.md](../architecture.md) are mandatory;
  - the database location is a separate, permanent decision ([0008](0008-firestore-region.md)).
