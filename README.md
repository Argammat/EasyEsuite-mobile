# EasyEsuite Mobile

Native iOS (Swift/SwiftUI) and Android (Kotlin/Jetpack Compose) apps for the EasyEsuite ERP, built against the
same tenant-scoped REST API the web app and the EasyEsuite MCP server use.

```
easyesuite-mobile/
├── docs/            API_MAP.md (the contract, VERIFIED vs ASSUMED), ARCHITECTURE.md, ROADMAP.md
├── shared/fixtures  Real API responses (PII scrubbed) — both test suites decode these
├── android/         :core (pure Kotlin SDK + tests) and :app (Compose)
├── ios/             EasyEsuiteKit (Swift package SDK + tests) and EasyEsuite (SwiftUI app, XcodeGen)
└── scripts/         sync-fixtures.sh
```

## V1 scope (built in this order)

| Area | iOS / Android |
|---|---|
| **Item creation** | Scan or type a UPC → product-database prefill (title, description, brand, photos) → SKU suggestion → cost, reorder point, dimensions, condition → photos from camera/library → optional opening stock in a warehouse |
| **Inventory** | Company-wide and per-warehouse stock with "in stock" / "below reorder point" filters, item detail with stock by warehouse, **transfers** (list + create), **receive POs** (per-line quantities), **stock adjustments** (± with reasons) |
| **Orders** | List by status with search, order detail (lines, ship-to, totals, memo), **fulfill** |
| **Shipping** | To-ship / on-hold / shipped / exceptions queues, shipment detail, **get rates → buy label**, open label, hold/release, tracking timeline, **scan-to-verify packing** |
| **Dashboard** | Order / fulfillment / shipping counts, the web dashboard's overview cards, order value by marketplace chart, top sellers |
| **Reports** | Sales by item (revenue / units / profit, optional per-marketplace split) from invoices, carrier spend |
| **Assistant** | Chat with the ERP Copilot (`ai/ai_copilot_agent_v2`) |
| **Scanning** | Universal scanner: UPC → item, label/tracking → shipment, `SO-…` → order, unknown UPC → create item |

## Run it

**Android** — open `android/` in Android Studio, or:
```bash
cd android && ./gradlew :core:test            # SDK unit tests, no Android SDK needed
cd android && ./gradlew :app:assembleDebug    # needs ANDROID_HOME
```
(The Gradle 8.14.3 wrapper is committed; the first run downloads the distribution.)

**iOS** — needs Xcode 15+ and [XcodeGen](https://github.com/yonaskolb/XcodeGen):
```bash
cd ios && xcodegen generate && open EasyEsuite.xcodeproj
cd ios/EasyEsuiteKit && swift test              # SDK unit tests (macOS)
```

Sign in with **company = tenant slug** (e.g. `nationwide`), email and password — the same credentials as erp.easyesuite.com.

## Before the first real sign-in

Everything in `docs/API_MAP.md` marked **VERIFIED** was observed live. Items marked **ASSUMED** follow the
web app's conventions but need one DevTools capture to confirm (login body, create-item endpoint, fulfil/receive/transfer
bodies, image upload, copilot payload). Each lives in exactly one place per platform:

| What | Android | iOS |
|---|---|---|
| Endpoint paths | `core/.../ApiConfig.kt` → `Endpoints` | `EasyEsuiteKit/.../ApiConfig.swift` → `Endpoints` |
| Login / refresh field names | `core/.../auth/Session.kt` → `TokenResponse` | `EasyEsuiteKit/.../Auth/Session.swift` → `TokenResponse` |
| Request bodies | `core/.../model/*.kt` (`*Request`) | `EasyEsuiteKit/.../Model/*.swift` (`*Request`) |

## Testing

`shared/fixtures/*.json` are real responses captured from the Nationwide tenant on 2026‑10‑03 with customer PII replaced.
Both SDK test suites decode every fixture, exercise the money parser (`"180.00"`, `"$82,765.20"`, `166464`, `"($12.00)"`),
the DRF error summariser, query encoding (multi-value `status`), token refresh on 401, the login/2FA flow and date-range bounds.
