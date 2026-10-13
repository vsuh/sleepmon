# Third-party code and licensing notes

Audit date: 2026-10-13  
Repository reviewed: https://github.com/vsuh/sleepmon  
Reviewed branch: `master`, commit `0d81470f90695bb4b3c3d56274f5c4713317fa00`

## Summary

The repository is public, and the root `LICENSE` grants MIT No Attribution (MIT-0) terms to files not covered by a more specific notice. **The repository cannot currently be described as uniformly MIT-0-licensed or as having completed a clean-room license audit.** At least one protocol schema is explicitly copied from Gadgetbridge under AGPL terms, and the separate Xiaomi Cloud probe pins a dependency whose current code is AGPL-3.0-only. Treat the project as mixed-license until the boundaries and distribution obligations are reviewed.

This is a source-level preliminary review, not legal advice or a complete automated Software Bill of Materials (SBOM). It does not replace a review of all historical commits, all transitive dependencies, generated artifacts, images/fonts, and the exact contents of a release APK.

## Identified copied or external code

### Gadgetbridge Xiaomi protocol schema — AGPL

Files:
- `android_sync_app/app/src/main/proto/xiaomi.proto`
- `android_sync_app/app/src/main/proto/xiaomi_auth_only.proto`
- `android_sync_app/app/src/main/java/com/example/sleepmonitorsync/band/proto/XiaomiProto.java` (generated from the trimmed schema)

The schema comments explicitly state that the full schema was ported verbatim from Gadgetbridge and identify José Rebelo and Gadgetbridge contributors, copyright 2023–2024, under GNU AGPL v3 or later. The trimmed authentication schema also identifies Gadgetbridge as its source. The checked-in Java file is generated from that trimmed schema, so it must not be presumed to be MIT-0 merely because it is generated.

Upstream:
- Gadgetbridge protocol schema: https://codeberg.org/Freeyourgadget/Gadgetbridge
- Upstream license: https://github.com/Freeyourgadget/Gadgetbridge/blob/master/LICENSE
- License family indicated by the local source headers: AGPL-3.0-or-later.

**Action required before redistribution:** preserve source attribution and provide the applicable AGPL license text; determine and document the licensing of the generated Java artifact and how this component is distributed with the Android app. Do not remove or overwrite the existing source notices.

### Xiaomi Cloud probe dependency — AGPL-3.0-only at the pinned revision

File:
- `tools/xiaomi_cloud_probe/requirements.txt`

The probe installs `shkyyy18/mi_fitness_data_bridge` from pinned commit `ae70f5e6e167474e93d4c2beeb8ae3d3297d5ade` (commit date 2026-09-30). The dependency's license file at that revision says that new code is AGPL-3.0-only and preserves an MIT notice for older upstream-derived portions. This dependency is installed from Git; its source is not vendored in this repository.

Pinned revision:
- https://github.com/shkyyy18/mi_fitness_data_bridge/commit/ae70f5e6e167474e93d4c2beeb8ae3d3297d5ade
- License at that revision: https://github.com/shkyyy18/mi_fitness_data_bridge/blob/ae70f5e6e167474e93d4c2beeb8ae3d3297d5ade/LICENSE

**Action required before redistributing the probe or offering it as a service:** review AGPL obligations for the way this dependency is used and distributed. Do not copy its implementation into this repository without a separate provenance and license review.

### Git submodule: sleepmon-dbtool

The root `.gitmodules` points to https://github.com/vsuh/sleepmon-dbtool.git. The parent repository stores a submodule pointer rather than the submodule's source files. The public tree checked during this audit did not contain a `LICENSE` file in that separate repository.

Because this is a repository under the same maintainer account, the maintainer can choose and add a license there if they hold the relevant rights. Until then, do not assume the submodule source is MIT-0-licensed simply because the parent repository has a root LICENSE.

## Root project license: what it currently means

The root `LICENSE` says that software and documentation are MIT No Attribution unless a more specific license is stated in a file or directory. MIT-0 permits broad use, modification, and redistribution without requiring attribution for material covered by that license. It does not override third-party terms for copied code.

GitHub's repository metadata currently reports `NOASSERTION`, rather than a recognized single SPDX license, which is consistent with the mixed/exception-based state.

Consequently:
- Original project code not covered by a specific third-party notice is offered under the root MIT-0 terms.
- The Gadgetbridge-derived schema and generated artifact need separate AGPL treatment.
- The cloud probe has an AGPL-3.0-only dependency pinned in its requirements.
- The current repository does **not** have one clean, uniform license status that can safely be summarized as “the whole project is MIT-0.”

## Dependencies that still need a complete license inventory

The Android Gradle files declare AndroidX/Compose, Kotlin coroutines, OkHttp, `org.json`, WorkManager, Protocol Buffers Java Lite, and Bouncy Castle. Python requirements include FastAPI, Uvicorn, Jinja2, python-multipart, httpx, Pydantic, python-dotenv, PyYAML, and Google API/auth packages. Their exact resolved versions, transitive dependencies, packaged notices, and release artifacts were not exhaustively scanned in this review.

Before a public binary release, generate a dependency/license inventory from the actual resolved dependency graph and inspect the APK's bundled notices. Also verify that no credentials, real health data, signing materials, local configuration, or private diary exports are included.

## Recommended next steps

1. Decide whether to retain a mixed-license project with clear per-component boundaries, or to restructure/replace the AGPL-derived material.
2. Keep MIT-0 only for code whose rights holders can grant it; do not assume the root license overrides third-party code.
3. Add the full applicable AGPL license text and a precise notice for the Gadgetbridge-derived schema/generated artifact.
4. Decide whether the Cloud probe should remain a separate optional tool with an AGPL dependency.
5. Add a license to the `sleepmon-dbtool` repository if the maintainer holds the rights to do so.
6. Run a full dependency and release-artifact license scan before distributing APKs or offering the server publicly.

This document records findings and open questions; it does not itself change the license granted by the root `LICENSE`.
