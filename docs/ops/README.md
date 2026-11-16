# Operations

Checklists for releasing and running LiquidLyf. They're for maintainers: you don't need them to
contribute code or translations.

| Checklist | When |
|---|---|
| [Beta](beta-checklist.md) | Before and during the beta: pre-releases for testers in one or two districts |
| [Launch](launch-checklist.md) | Before and on the day of v1.0, and the first week after it |
| [Running it](operations.md) | After launch: the daily, weekly, monthly and yearly tasks |

How to use them:
- For each release, copy the checklist into a GitHub issue and tick the boxes there. These files stay
  the template.
- 👤 marks a step only a maintainer can do: accounts, keys and consoles.
- Secrets never go into the repo, an issue or a chat: the signing key and its passwords,
  service-account keys, `google-services.json`.

Related: [the website](../website-drafts/README.md) (publishing, the `released` switch),
[the admin scripts](../../backend/admin-scripts/README.md) (moderators, bans, deletion requests) and
[the Worker](../../backend/worker/README.md) (crons, secrets, deployment).
