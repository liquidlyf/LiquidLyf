# Licensing

SPDX-License-Identifier: `AGPL-3.0-or-later`

Copyright (C) 2025-2026 The LiquidLyf contributors

## The code

LiquidLyf is free software. You can redistribute it and/or modify it under the terms of the
**GNU Affero General Public License** as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version. The full text is in [LICENSE](LICENSE).

LiquidLyf is distributed in the hope that it will be useful, but **without any warranty**; without
even the implied warranty of merchantability or fitness for a particular purpose. See the GNU Affero
General Public License for more details.

This covers everything in this repository unless a file or folder says otherwise: the Android app,
the Firestore security rules and their tests, the Cloudflare Worker, scripts, build files and CI
workflows. Translations (for example `values-bn/strings.xml`) are part of the program and use the
same licence.

## Additional permission for Google libraries (AGPL section 7)

The Android app uses Google Play services and Firebase. Some of those libraries aren't open source,
so we add this permission, as section 7 of the AGPL allows:

> Additional permission under GNU AGPL version 3 section 7:
>
> If you modify this Program, or any covered work, by linking or combining it with Google Play
> services, Firebase SDKs, Google Play Integrity, or other libraries that Google distributes under
> the Android Software Development Kit License or the Google APIs Terms of Service (or modified
> versions of those libraries), containing parts covered by the terms of those licenses, the
> licensors of this Program grant you additional permission to convey the resulting work.

You may remove this permission from your own modified copies, as section 7 allows.

## Credit in modified versions (AGPL section 7(b))

> Additional terms under GNU AGPL version 3 section 7:
>
> (b) If you convey a modified version of this Program, or make one available to users over a network,
> it must keep the attribution "Based on LiquidLyf — https://github.com/liquidlyf/LiquidLyf" in the
> Appropriate Legal Notices it displays, in legible form. In the LiquidLyf Android app, the Appropriate
> Legal Notices are on the About screen.
>
> (e) This attribution requirement gives no right to use the LiquidLyf name, logo or other trademarks,
> except to show the attribution itself. See TRADEMARKS.md.

Unlike the permission for Google libraries, these terms stay with every modified version: you can't
remove them.

## What the AGPL means here, in short

- You may use, study, change and share LiquidLyf, including for an NGO or a blood-donor club.
- If you share a changed app (for example, an APK), you must share its source code under the same licence.
- If you run a changed version of the backend (the Cloudflare Worker) for other people, you must offer
  them its source code too (AGPL section 13). The app's **About → Source code** link does this for
  the official app. A fork must point that link to its own source.
- A changed version must keep the line "Based on LiquidLyf — https://github.com/liquidlyf/LiquidLyf"
  on its About screen. It still needs its own name and logo.
- Nobody, including the maintainers, can take the project closed-source later.

This summary isn't legal advice. The licence text decides.

## Documentation

Documentation in [`docs/`](docs/) is licensed under the
[Creative Commons Attribution 4.0 International licence (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/),
unless a file says otherwise. Code samples inside the docs may also be used under the AGPL.

## The name and the logo are not covered

"LiquidLyf" and the LiquidLyf logo (the logo files in [`branding/`](branding/) and the app icon) are
**not** licensed under the AGPL or CC BY 4.0. They identify the official app. If you distribute or
host a fork, you must use your own name and logo. See [TRADEMARKS.md](TRADEMARKS.md).

## Third-party components

Libraries and data from other projects keep their own licences. The app lists them under
**Settings → About → Open-source licences**. Examples: AndroidX and the open-source Firebase SDKs
(Apache-2.0); map or place data from OpenStreetMap, if used (ODbL, credited in the app).

## Contributions

Contributions are accepted under the same terms: AGPL-3.0-or-later with the additional permission
and the additional terms above (documentation under CC BY 4.0). You keep the copyright in your work. Every commit needs a
[Developer Certificate of Origin](https://developercertificate.org) sign-off (`git commit -s`).
See [CONTRIBUTING.md](CONTRIBUTING.md).

## Earlier versions

The original university project, kept at the git tag `v0-university`, was released under the
**MIT licence** and stays under MIT. Everything after that tag is under AGPL-3.0-or-later.

## Adding a licence header to new files (optional)

[LICENSE](LICENSE) and this file cover the whole repository, so a header isn't required. If you add one,
keep the third line: it says that the additional permission and terms above apply.

```kotlin
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 The LiquidLyf contributors
// Additional permission and terms under AGPL section 7 apply: see LICENSING.md.
```
