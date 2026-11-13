# 0001: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

LiquidLyf is being rebuilt as a community project. New contributors, a future co-maintainer and partner
organizations need to know why things are the way they are, without asking the original author. Some
decisions, like the licence and the app ID, are almost impossible to change later, so the reasons must
be written down when they are made.

## Options considered

1. **Nothing written down:** fast, but the reasons are lost when people leave.
2. **A wiki:** easy to edit, but it isn't reviewed and drifts away from the code.
3. **Decision records in the repository:** reviewed in pull requests and kept next to the code they explain.

## Decision

We keep short decision records in `docs/adr/`, numbered `NNNN-short-title.md`, using a lightweight
[MADR](https://adr.github.io/madr/)-style template ([0000-template.md](0000-template.md)).

A record is needed for decisions about architecture, the licence, privacy, user data and safety
(see [GOVERNANCE.md](../../GOVERNANCE.md)). A record is never deleted. When a decision changes, a new
record supersedes it.

## Consequences

- **Good:** the reasons survive, and newcomers can catch up by reading a few short files.
- **Bad, or trade-offs:** writing a record takes a little time for every big decision.
- **Follow-ups:** write the records listed as "to be written" in the [index](README.md).
