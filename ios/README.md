# EasyEsuite iOS

| Part | What | Build |
|---|---|---|
| `EasyEsuiteKit/` | Swift package SDK: `APIClient` (URLSession, async/await, JWT refresh), models, repositories, Keychain store. No UIKit. | `swift test` on macOS, or via Xcode |
| `EasyEsuite/` | SwiftUI app (iOS 16+): tabs, scanner, all features. | Xcode 15+ |

```bash
brew install xcodegen          # once
cd ios && xcodegen generate    # creates EasyEsuite.xcodeproj (git-ignored)
open EasyEsuite.xcodeproj      # set your team in Signing & Capabilities, run on a device for the camera
cd EasyEsuiteKit && swift test # SDK tests
```

Shipping to testers: **[docs/TESTFLIGHT.md](../docs/TESTFLIGHT.md)** — a GitHub Actions workflow (`TestFlight`) archives, signs and uploads
to App Store Connect; `scripts/asc-bootstrap.py` does the one-time certificate/profile setup without a Mac.

## Layout

```
EasyEsuiteKit/Sources/EasyEsuiteKit/
  ApiConfig.swift        base URL, tenant prefix, every endpoint path, marketplace ids
  Net/APIClient.swift    request/response, Bearer header, proactive + 401 refresh with rotating refresh tokens, DRF error parsing
  Net/FirestoreClient.swift  read-only Firestore REST client (Copilot history, notifications) with the typed-value decoder
  Auth/                  Session, TokenStore (+Keychain), AuthService (workspaces → login → Firebase custom token, 2FA, me), JWT claims
  Model/                 Items, Orders (+Shipments), Purchasing, Reports (+JSONValue), User, Money, Page
  Repo/                  ItemsRepository + InventoryRepository, OrdersRepository + ShippingRepository + ReportsRepository + AssistantRepository
  Util/Dates.swift       DateRange presets → inclusive UTC bounds in the user's zone

EasyEsuite/
  App/                   AppContainer (one graph per tenant), RootView (Route enum, tabs), EasyEsuiteApp
  Common/UI.swift        PagedListModel/PagedListView, Thumb, StatusChip, KeyValueRow, ChipRow, StatTile, toast
  Features/Auth          LoginView (email + password → 2FA → workspace picker)
  Features/Items         ItemsView, ItemDetailView (stock by warehouse), NewItemView (UPC prefill, photos, opening stock)
  Features/Inventory     InventoryView, TransfersView (+NewTransferView), ReceiveView, AdjustView
  Features/Orders        OrdersView (orders | shipments), OrderDetailView (fulfil, memo), ShipmentDetailView (rates → buy, hold, tracking, verify)
  Features/Dashboard     DashboardView (tiles, overview cards, Swift Charts, top sellers)
  Features/Reports       ReportsView (sales by item, carrier spend)
  Features/Assistant     AssistantView (Copilot chat)
  Features/More          MoreView, SettingsView (sign out)
  Scanner/               ScannerSheet (AVFoundation), ScanResultView (item / shipment / order / create)
  Resources/             Assets (AppIcon, Logo), Info.plist (generated), PrivacyInfo.xcprivacy

ExportOptions.plist      App Store Connect upload options used by the TestFlight workflow
scripts/asc-bootstrap.py One-time App Store Connect setup (bundle ID, distribution certificate, profile)
```

## Sign-in and environment configuration

Sign-in is the backend's three-step flow (`../docs/API_MAP.md` → Auth): `POST api/v1/auth/login/tenants/` lists the
user's workspaces, the chosen workspace is logged into at `clients/{schema_name}/api/v1/auth/login/`, and the
`firebase_custom_token` from that response is exchanged for a Firebase ID token that only the Firestore-backed
screens use (Copilot history, notifications, connector status). `AppConfig` reads the environment from Info.plist,
which `project.yml` fills from build settings defined in `Config/Environment.xcconfig`:

| Build setting | Effect |
|---|---|
| `EASYESUITE_API_ROOT` | API host; empty = production `api-new.easyesuite.com` |
| `EASYESUITE_FIREBASE_API_KEY`, `EASYESUITE_FIREBASE_PROJECT_ID` | Firebase project of the **same** environment; empty = Firestore screens unavailable (REST still works) |
| `EASYESUITE_FIRESTORE_CONNECTOR_DB`, `EASYESUITE_FIRESTORE_NOTIFICATIONS_DB` | the two named Firestore databases |

Real values live in `Config/Environment.local.xcconfig` (git-ignored; copy `Environment.local.xcconfig.example`) or are
passed on the `xcodebuild` command line in CI — never committed, the repository is public. Settings shows the
environment name; non-production builds also print it on the login screen.

## First run checklist

1. Sign in with your **email and password**, then pick the workspace — the same flow as erp.easyesuite.com. With one workspace the picker is skipped; "Switch workspace" (Settings) asks for the password again because the backend's login is per workspace.
2. "Live data: Connected" in Settings means the Firebase custom-token sign-in worked for this workspace.
3. Confirm the remaining **ASSUMED** rows in `../docs/API_MAP.md` (the MFA body and the write bodies); each lives in one `*Request` struct.
