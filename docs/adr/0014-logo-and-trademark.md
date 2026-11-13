# 0014: Redrawn logo; the name and logo stay outside the AGPL

- **Status:** Accepted
- **Date:** 2026-11-13
- **Deciders:** maintainers

## Context

The project founder drew the heart-and-drop logo for the university version. The original was a PNG
with gradients and shadows. It didn't work as an Android adaptive or themed icon, and "Lyf" was hard to
read on some backgrounds.

The code is under the AGPL ([0002](0002-licence-agpl-3.0-or-later.md)), which lets anyone publish a
modified copy. A copy with the same name and icon could collect donors' phone numbers or ask them for
money, and people couldn't tell it from the real app.

## Options considered

1. **A new logo:** a fresh start, but it throws away the brand people already know.
2. **Keep the heart-and-drop idea and redraw it as flat vector files:** the same brand, sharp at every
   size, and usable as an Android icon.
3. **Put the logo under the AGPL too:** simple, but then forks could legally look identical to the
   official app.

## Decision

- The logo is **redrawn as flat SVG files** in [`branding/`](../../branding/README.md): the mark, a
  single-colour version for themed icons, and the full logo for light and dark backgrounds. The
  wordmark is Montserrat Medium (SIL Open Font License), converted to outlines.
- The **name "LiquidLyf" and the logo are not covered by the AGPL** or by CC BY 4.0.
  [TRADEMARKS.md](../../TRADEMARKS.md) says how they may be used: forks must use their own name, logo
  and app ID.
- The generator script `branding/build_logo.py` is code, under the AGPL. The files it produces are
  covered by the trademark policy.

## Consequences

- **Good:** the brand stays recognisable, the app icon works on every Android version, and users can
  trust that "LiquidLyf" means the official app.
- **Bad, or trade-offs:** "LiquidLyf" is an unregistered trademark, so the policy rests on goodwill and
  on the AGPL credit rule more than on registration.
- **Follow-ups:**
  - registering the trademark in Bangladesh is optional, later;
  - modified versions must keep the "Based on LiquidLyf" credit (AGPL section 7(b), see
    [LICENSING.md](../../LICENSING.md)).
