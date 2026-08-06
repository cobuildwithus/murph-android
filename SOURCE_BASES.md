# Source bases

The current parity work was prepared against these reviewed source snapshots on August 6, 2026:

- `cobuildwithus/murph-ios` PR #48 — `f8cdbfd6555def5f4e0c5f2575417cb8cef76931`
- `cobuildwithus/murph` PR #1296 — `46e3671b47001d2b7cc9eeef6afcb659532ba2a5`
- `cobuildwithus/murph` PR #1341 — `f342e61b1d109635c631d5b86014bb4f1de4bd39`
- `cobuildwithus/murph-android` base — `02b4c872dfdc8cd6fba72a4baae4930c85fdc337`
- `tryVital/vital-android` — `eda6a537d0518a7ca7c3716a5c8b25f4a8fae5d0` / SDK `5.0.2`
- Privy Android SDK — artifact `io.privy:privy-core:0.12.0`

Account admission and initial onboarding require the listed Murph backend PRs
to deploy before this Android change. The mobile client deliberately consumes
those server-owned contracts rather than duplicating signup, catalog, or
completion state.

<!-- Hosted workflow trigger diagnostic; reverted immediately. -->
