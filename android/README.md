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

## Sign-in configuration

The backend expects `Authorization: Bearer <firebase_id_token>` (sign in with Firebase / Identity Platform against the
workspace's Firebase tenant id). Two strategies are built in — see `../docs/API_MAP.md` → Auth:

| Build setting | Effect |
|---|---|
| `-Peasyesuite.firebaseApiKey=…` or `EASYESUITE_FIREBASE_API_KEY` | Firebase web API key → sign in against Identity Platform directly (`auth/FirebaseAuth.kt`) |
| `-Peasyesuite.firebaseTenantId=…` or `EASYESUITE_FIREBASE_TENANT_ID` | Identity Platform tenant id for a single-tenant build (otherwise resolved per company via `TenantDirectory`, an ASSUMED endpoint) |
| *(nothing)* | Backend-proxied `auth/login/` + `auth/token/refresh/` flow |

Access tokens are renewed ~60 s before `access_expiration` (Firebase ID tokens live an hour) and once more on a 401.

## First run checklist

1. Sign in with your **email and password**, then pick the workspace (e.g. `nationwide`) — the same flow as erp.easyesuite.com. With one workspace the picker is skipped; "Switch workspace" lives in Settings.
2. If login fails with a 404 → the login path differs; fix `Endpoints.LOGIN` in `ApiConfig.kt`.
   If it returns 200 but the app says "no access token" → map the field names in `auth/Session.kt` → `TokenResponse`.
   If the team confirms Firebase sign-in, set the two build settings above instead.
3. Everything marked **ASSUMED** in `../docs/API_MAP.md` should be confirmed against the web app's DevTools once; each lives in exactly one request class.
