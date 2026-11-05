# Security policy

OrbitWalker runs a helper with Android's debugging-level ("shell") permissions, so security reports matter a lot to us.

## Reporting a vulnerability
Please report privately through GitHub: **Security → Report a vulnerability** on this repository. Don't open a public issue for security problems.

Include what you found, how to reproduce it, and the app version (Settings, at the bottom). We'll acknowledge your report and keep you updated while we fix it.

## Design notes
- The helper only answers calls from OrbitWalker's own app (checked by Android user id on every call).
- It hands its connection only to OrbitWalker's installed package, and after an app update it only follows an app with the same signing certificate.
- Pairing keys for Wireless debugging stay in the app's private storage and are excluded from backups.
- Typed text and notification contents are never logged.
