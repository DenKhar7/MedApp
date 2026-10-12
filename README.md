# MedApp

An Android app to track daily medication, built on top of a self-hostable API serving the official French medicines database (ANSM / RUIM). Born as a five-person university project (Scrum, 2025 - 2026), now maintained as an open-source portfolio piece.

> **Disclaimer** – MedApp is an educational project, not a medical device. It does not give medical advice. Always follow your doctor's and pharmacist's instructions.

## What it does

- **Daily checklist & calendar** – see what to take today, and which days were completed or missed.
- **Treatments** – dosage, date range, weekly recurrence, several reminders per day.
- **Reminders** – exact-time notifications with *Taken* / *Snooze 15 min* actions, rescheduled after reboot.
- **Medicine search** – search the official database by name, with results cached on the phone.
- **Prescriptions** – manual entry or document scan with on-device OCR (ML Kit).
- **Family profiles** – one account, several people, separate data.
- **English / French.**

Accounts and treatments are stored **locally on the device** (Room + encrypted session). The server only provides medicine reference data.

## Architecture

```
┌────────────────────┐   HTTP/JSON    ┌──────────────────────┐        ┌──────────────┐
│  android/          │ ─────────────► │  server/  (Ktor)     │ ─────► │  MariaDB     │
│  Kotlin · MVVM     │  /api/v1/      │  hexagonal:          │        │  active/temp │
│  Room · WorkManager│  medicament    │  domain · infra · api│        │  archive     │
│  ML Kit · Ktor     │                │  nightly import jobs │        │  views (RO)  │
└────────────────────┘                └──────────┬───────────┘        └──────────────┘
                                                 │ scrape + download
                                                 ▼
                                      ANS terminology server (RUIM, SMS)
```

| Folder | Contents |
|---|---|
| [`android/`](android) | Android app – Kotlin, MVVM, Room, AlarmManager/WorkManager, CameraX/ML Kit. |
| [`server/`](server) | Ktor 3 REST API (Exposed + HikariCP), CSV importers, scheduled referential updates, Swagger UI. |
| [`infra/`](infra) | MariaDB schemas, SQL migration runner, example Jenkins pipelines, test dataset. |

## Quick start

### 1. Run the backend (Docker)

```bash
cd server
cp .env.example .env      # then fill it in: choose passwords (letters, digits and _ . @ + - only)
docker compose build
docker compose up -d mariadb
docker compose run --rm server import-referentiels   # first import of the ANS data
docker compose up -d server
```

API docs: <http://localhost:4000/swagger> · health check: <http://localhost:4000/health>

### 2. Build the Android app

Requirements: JDK 17, Android Studio (SDK 36).

Create `android/local.properties`:

```properties
sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
# Server root. Android emulator -> your machine's localhost (the /api/v1/medicament prefix is added by the app)
BASE_URL=http://10.0.2.2:4000
```

```bash
cd android
./gradlew assembleDebug        # or open the folder in Android Studio
./gradlew test                 # unit tests
```

Use an emulator image **with Google Play** – the document scanner relies on Google Play services.

## Security notes

- **Database accounts (least privilege).** MariaDB is initialised from `infra/sql/prod`, which creates two accounts:
  `API_DB_USER` (read-only, `SELECT` on `med_db_views` only) used by the API, and `MARIADB_USER` (import jobs, rights
  limited to the `active`/`temp`/`archive` databases, no `ALL PRIVILEGES`). The root password stays in the MariaDB
  container; the API container never receives it. The API container also runs as a non-root user.
- **Atomic data updates.** The nightly referential update swaps tables with a single `RENAME TABLE` statement, so
  the API never sees a half-updated database, and an import that is empty or lost more than half of its rows is
  rejected before it can replace production data. Failures are recorded in `referentiel_import_state`.
- **API protection.** Per-IP rate limiting (default 120 requests/min, `api.rate-limit.per-minute`), `limit` capped at
  50, user-typed `%`/`_` treated literally in searches, CORS without credentials, and a `/health` endpoint that
  really checks the database.
- **Android.** No cloud backup, private storage for prescription scans (deleted with the prescription), generic
  lock-screen notifications, PBKDF2-HMAC-SHA256 (600 000 iterations) password hashes with transparent upgrade of old
  hashes, and no request bodies in release logs.

**Upgrading an existing database:** the account setup only runs on an empty volume. For an existing deployment,
create the two accounts with the grants from `infra/sql/prod/02_create_db_users.sh`, drop the foreign key on
`med_substance.code_sms` in `med_db_active` and `med_db_temp` (and `med_db_archive`), and re-run the import.

## Known limitations

- No hosted instance is provided: search requires you to run the backend yourself.
- Cleartext HTTP is only permitted for local development hosts; use HTTPS for any real deployment.
- Behind a reverse proxy, enable Ktor's `XForwardedHeaders` so the rate limit is applied per real client IP.
- The Room database is not encrypted at rest and the app has no biometric lock (accounts are local to the device).
- Reminders are rescheduled by a daily WorkManager job; aggressive battery savers can delay them.
- Substance molecular details (`/substances/detail`) are available again, but the mobile app does not display them.

## License

[MIT](LICENSE)
