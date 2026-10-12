# EasyEsuite Android

Two Gradle modules:

| Module | What | Builds on |
|---|---|---|
| `:core` | Pure Kotlin/JVM SDK: `ApiClient` (OkHttp + kotlinx.serialization, JWT refresh), models, repositories. **No Android dependency** — unit-tested against the shared fixtures. | Any JDK 17 box |
| `:app` | Jetpack Compose app (Material 3), CameraX + ML Kit barcode scanning, EncryptedSharedPreferences token storage. | Android Studio Ladybug+, SDK 35 |

```bash
# unit tests for the SDK (no Android SDK needed)
./gradlew :core:test

# the app — needs ANDROID_HOME or local.properties
./gradlew :app:assembleDebug
```

> `settings.gradle.kts` only includes `:app` when an Android SDK is detected, so CI can run `:core:test` on a plain JVM runner.

## Where things are

```
core/src/main/kotlin/com/easyesuite/core/
  ApiConfig.kt         base URL, tenant prefix, every endpoint path, marketplace ids
  net/ApiClient.kt     request/response, Bearer header, 401 → refresh → retry, DRF error parsing
  auth/                Session, TokenStore, AuthService (login / 2FA / me / tenants)
  model/               Items, Orders (+Shipments), Purchasing (POs, transfers, adjustments), Reports, User, Money, Page
  repo/                ItemsRepository, InventoryRepository, OrdersRepository + ShippingRepository, ReportsRepository, AssistantRepository
  util/Dates.kt        DateRange presets → inclusive UTC bounds in the user's zone

app/src/main/kotlin/com/easyesuite/app/
  di/AppContainer.kt   one object graph per signed-in tenant
  data/SecureTokenStore.kt
  ui/AppRoot.kt        login ↔ main switch, bottom tabs, all routes
  ui/items/            catalog, item detail (stock by warehouse), NEW ITEM (UPC lookup prefill, photos, opening stock)
  ui/inventory/        stock list (company / per warehouse, low-stock), transfers (+create), receive PO, adjustments
  ui/orders/           orders + shipments lists, order detail (fulfill, memo), shipment detail (rates → buy label, hold, tracking, scan-to-verify)
  ui/dashboard/        owner tiles, web-dashboard cards, marketplace chart, top sellers
  ui/reports/          sales by item (revenue / units / profit, per-marketplace) + carrier spend
  ui/assistant/        Copilot chat
  ui/scan/             CameraX + ML Kit scanner, scan-result router (item / shipment / order / create item)
```

## Sign-in and environment configuration

Sign-in is the backend's three-step flow (`../docs/API_MAP.md` → Auth): `POST api/v1/auth/login/tenants/` lists the
user's workspaces, the chosen workspace is logged into at `clients/{schema_name}/api/v1/auth/login/`, and the
`firebase_custom_token` from that response is exchanged for a Firebase ID token that only the Firestore-backed
screens use (Copilot history, notifications, connector status). `app/build.gradle.kts` turns these into `BuildConfig`
fields, read from an untracked `android/easyesuite.properties` (copy `easyesuite.properties.example`), else a Gradle
property, else an environment variable:

| Property / env var | Effect |
|---|---|
| `easyesuite.apiRoot` / `EASYESUITE_API_ROOT` | API host; empty = production `api-new.easyesuite.com` |
| `easyesuite.firebaseApiKey` + `easyesuite.firebaseProjectId` / `EASYESUITE_FIREBASE_API_KEY` + `EASYESUITE_FIREBASE_PROJECT_ID` | Firebase project of the **same** environment; empty = Firestore screens unavailable (REST still works) |
| `easyesuite.firestoreConnectorDb`, `easyesuite.firestoreNotificationsDb` / `EASYESUITE_FIRESTORE_*_DB` | the two named Firestore databases |

Never commit the values — the repository is public. Settings shows the environment name; non-production builds also
print it on the login screen. Tokens live in `EncryptedSharedPreferences`; OkHttp logging is `BASIC` (no headers/bodies).

## First run checklist

1. Sign in with your **email and password**, then pick the workspace — the same flow as erp.easyesuite.com. With one workspace the picker is skipped; "Switch workspace" (Settings) asks for the password again because the backend's login is per workspace.
2. "Live data: Connected" in Settings means the Firebase custom-token sign-in worked for this workspace.
3. Everything still marked **ASSUMED** in `../docs/API_MAP.md` (the MFA body and the write bodies) should be confirmed once; each lives in exactly one request class.
