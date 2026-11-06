# Health Core (Android)

One app for [health-core](https://github.com/ravilushqa/health-agent) that replaces two:

| Before | After |
|---|---|
| **HC Webhook** (`com.hcwebhook.app`, FOSS release) reads Health Connect and posts JSON to `POST /ingest/health-connect` | **Health Core** (`com.ravil.healthcore`) does the same, with the same JSON |
| **Nutrition Bridge** (`com.ravil.healthconnect.nutrition`, module `:nutrition`) polls `GET /nutrition/queue`, writes meals, `POST /nutrition/ack` | the same code, in the same app |

It talks to one server only (`https://health-core.ravil.space` by default), has no Google
Play Services, Firebase, analytics, notifications or foreground services, and shows no
health values: the screen reports times, outcomes and record counts.

## What changes for health-core

Nothing on the server. The ingest payload is HC Webhook's JSON, byte for byte (see
*Tests*), with one difference that already exists in this repository's fork: every record
carries its Health Connect identity in `metadata` (`id`, `client_record_id`,
`client_record_version`, `last_modified_time`; buckets carry none). health-core then
deduplicates by id instead of by timestamps (`journal.Options.Reconcile`, on by default).

## Modules

```
healthcore-core/   plain Kotlin/JVM: payload, cursors, HTTP clients, meal sync logic
healthcore/        the Android app: Health Connect reads/writes, WorkManager, Compose UI
```

`healthcore-core` holds everything that can be tested without Android: the record model
and JSON serializer (ported verbatim from `:app`), the per-type cursors and 48-hour read
window, the ingest client, the connection check, and the meal queue logic from
`:nutrition` (plan, executor, validator, bookmarks, API client) with its tests.

`healthcore` holds the Health Connect reader (ported from `:app`'s `HealthConnectManager`,
reading code only), the nutrition writer, settings and token storage, the periodic worker
and the UI. `:app` and `:nutrition` are untouched.

## Install

**From the pre-release (recommended).** Every push that touches the app publishes a signed
APK as the rolling pre-release `healthcore-nightly`:
`https://github.com/ravilushqa/health-connect-webhook/releases/download/healthcore-nightly/health-core.apk`.
Open it on the phone and install. Later builds install over it: they are signed with the
same key and carry a higher version (the workflow run number), so tokens and settings stay.

**From a workflow artifact.** `healthcore-debug` (Actions → run → Artifacts) is a zip with a
debug APK signed with that runner's throwaway key. It cannot be updated in place, so use it
only to try a branch. A debug install and the release cannot be installed over each other:
uninstall one before installing the other.

### Signing key (once)

The release is signed with a PKCS12 keystore (alias `healthcore`) kept in two repository
secrets: `HEALTHCORE_KEYSTORE_B64` (the file, base64) and `HEALTHCORE_KEYSTORE_PASSWORD`.
Without them the workflow skips the release. Keep a backup of the keystore outside GitHub:
if it is lost, the next key's builds cannot be installed over the old ones (uninstall,
then set the app up again). The workflow log prints the certificate's SHA-256 digest
(`apksigner verify --print-certs`), which must match the one recorded when the key was made.

## Set up

1. Open **Health Core → Settings** (gear icon).
2. **health-core URL**: keep `https://health-core.ravil.space` unless the server moved.
   Only `https://` URLs without query or credentials are accepted.
3. **Ingest token**: *Add* → paste the `X-Health-Token` (the same one HC Webhook uses) →
   later *Save*. The field is write-only: after saving it shows *Saved · updated …*, never
   the token. *Replace* swaps it.
4. **Nutrition token**: the same for `X-Nutrition-Token` (the one Nutrition Bridge uses).
5. **Sync**: turn on *Send health data* and *Write meals*; set the interval (≥ 15 min).
6. **Data resolution**: set the same values as in HC Webhook (*Configuration → data
   types*). The defaults are HC Webhook's defaults (steps, distance, active calories:
   daily; heart rate, HRV, SpO₂, respiratory rate, skin temperature: 1 min; sleep: with
   stages; the rest raw). If they differ, health-core stores the same period in two
   shapes (e.g. daily step totals and 15-minute buckets) and day totals double.
7. **Save**, back to the main screen, **Grant permissions**: allow all types, *Access data
   in the background* and *Nutrition (write)*. Background access is what lets the
   periodic sync read without the app open.
8. **Check connection**: both lines should say *OK*. The check stores nothing: it sends a
   request health-core authenticates and then rejects (an empty ingest object, an ack
   without a record id), so *OK* means "this URL is health-core and the token is right".
9. **Sync now**: *Health data* should say *Delivered* (or *Nothing new*), *Meals* *Up to date*.

## Switch over from HC Webhook and Nutrition Bridge

1. Install and set up Health Core as above, next to the old apps. Running both for a while
   is safe: health-core deduplicates the overlap (the same records arrive twice; those
   with ids replace their timestamp-keyed copies).
2. Check for a day: main screen shows regular *Delivered* runs; on the server,
   `health_status` / `/v1/status` shows fresh data; a meal logged through the agent shows
   up in Health Connect with Health Core as the source.
3. Then, in HC Webhook remove the health-core webhook (or uninstall the app), and uninstall
   Nutrition Bridge.

Notes:

- Meals already written by Nutrition Bridge stay in Health Connect under its name. If such
  a meal is amended later, Health Core writes the new version under its own name and the
  old record stays (Health Connect scopes `clientRecordId` per app). Delete the old one in
  Health Connect by hand if it happens.
- Changing the server URL resets the ingest cursors, so the next run re-sends the last 48
  hours to the new server.

## Privacy

- Tokens: Android Keystore-backed `EncryptedSharedPreferences`, one file per token,
  excluded from backups and device transfer (the app disables backup entirely).
- Requests: HTTPS only (cleartext disabled), redirects not followed (a redirect would
  otherwise carry the token), tokens only in their headers, never in URLs.
- Logs: record type names only; no times, values, tokens or server bodies.
- Permissions: Health Connect read for the 32 types HC Webhook reads (steps also covers cadence),
  background reads, nutrition write; `INTERNET`, `ACCESS_NETWORK_STATE`. No history
  permission (reads never go back more than 48 hours).

## Tests

```bash
./gradlew :healthcore-core:test            # in the full build (CI)
./gradlew -p healthcore-core test          # standalone: no Android SDK or Google Maven needed
./gradlew :healthcore:testDebugUnitTest    # meal settings/token durability tests from :nutrition
./gradlew :healthcore:lintDebug :healthcore:assembleDebug
```

What the core tests check:

- **Parity with HC Webhook**: `generateAppReference` cuts `putRecordMetadata`,
  `buildJsonPayload` and the BMI helper out of `app/.../SyncManager.kt` at build time and
  compiles them into the tests; `IngestPayloadTest` compares their output with Health
  Core's on synthetic data for every record type and branch (buckets, optional fields,
  missing metadata). A change to `:app`'s serializer or model fails this test.
- **Accepted by health-core**: `HealthCoreRules` is a port of health-core's
  `internal/hc/validate` (and the RFC 3339 timestamp profile); every payload variant must
  pass it, and every record type it emits must be one health-core stores. The committed
  fixture `healthcore-core/src/test/resources/contract/ingest-all-types.json` was also run
  through health-core's own Go `validate.Payload` (0 errors). Regenerate it with
  `./gradlew -p healthcore-core test -Dhealthcore.updateFixtures=true` and review the diff.
- **Cursors**: future-dated interval records (daily projections) do not advance them.
- **HTTP**: token headers, no redirects, no chunked bodies, size limit (8 MiB, health-core's
  `MaxBody`), status mapping, the connection check.
- **Meals**: the `:nutrition` test suites, unchanged apart from package names.

All test data is synthetic.
