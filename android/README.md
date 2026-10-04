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

## First run checklist

1. Sign in with **company = your tenant slug** (e.g. `nationwide`), email, password.
2. If login fails with a 404 → the login path differs; fix `Endpoints.LOGIN` in `ApiConfig.kt`.
   If it returns 200 but the app says "no access token" → map the field names in `auth/Session.kt` → `TokenResponse`.
3. Everything marked **ASSUMED** in `../docs/API_MAP.md` should be confirmed against the web app's DevTools once; each lives in exactly one request class.
