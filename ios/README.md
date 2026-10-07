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

## Layout

```
EasyEsuiteKit/Sources/EasyEsuiteKit/
  ApiConfig.swift        base URL, tenant prefix, every endpoint path, marketplace ids
  Net/APIClient.swift    request/response, Bearer header, 401 → refresh → retry, DRF error parsing
  Auth/                  Session, TokenStore (+Keychain), AuthService (login / 2FA / me / tenants)
  Model/                 Items, Orders (+Shipments), Purchasing, Reports (+JSONValue), User, Money, Page
  Repo/                  ItemsRepository + InventoryRepository, OrdersRepository + ShippingRepository + ReportsRepository + AssistantRepository
  Util/Dates.swift       DateRange presets → inclusive UTC bounds in the user's zone

EasyEsuite/
  App/                   AppContainer (one graph per tenant), RootView (Route enum, tabs), EasyEsuiteApp
  Common/UI.swift        PagedListModel/PagedListView, Thumb, StatusChip, KeyValueRow, ChipRow, StatTile, toast
  Features/Auth          LoginView (company + email + password, 2FA step)
  Features/Items         ItemsView, ItemDetailView (stock by warehouse), NewItemView (UPC prefill, photos, opening stock)
  Features/Inventory     InventoryView, TransfersView (+NewTransferView), ReceiveView, AdjustView
  Features/Orders        OrdersView (orders | shipments), OrderDetailView (fulfil, memo), ShipmentDetailView (rates → buy, hold, tracking, verify)
  Features/Dashboard     DashboardView (tiles, overview cards, Swift Charts, top sellers)
  Features/Reports       ReportsView (sales by item, carrier spend)
  Features/Assistant     AssistantView (Copilot chat)
  Features/More          MoreView, SettingsView (sign out)
  Scanner/               ScannerSheet (AVFoundation), ScanResultView (item / shipment / order / create)
```

## Sign-in configuration

The backend expects `Authorization: Bearer <firebase_id_token>` (sign in with Firebase / Identity Platform against the
workspace's Firebase tenant id). Two strategies are built in — see `../docs/API_MAP.md` → Auth. `AppConfig` reads them
from Info.plist, which `project.yml` fills from build settings so real keys stay out of git:

| Build setting (`xcodebuild … SETTING=value` or an untracked xcconfig) | Effect |
|---|---|
| `EASYESUITE_FIREBASE_API_KEY` | Firebase web API key → sign in against Identity Platform directly (`Auth/FirebaseAuth.swift`) |
| `EASYESUITE_FIREBASE_TENANT_ID` | Identity Platform tenant id for a single-tenant build (otherwise resolved per company via `TenantDirectory`, an ASSUMED endpoint) |
| `EASYESUITE_API_ROOT` | Override the API host (defaults to production) |
| *(nothing)* | Backend-proxied `auth/login/` + `auth/token/refresh/` flow |

Access tokens are renewed ~60 s before `access_expiration` (Firebase ID tokens live an hour) and once more on a 401.

## First run checklist

1. Sign in with your **email and password**, then pick the workspace (e.g. `nationwide`) — the same flow as erp.easyesuite.com. With one workspace the picker is skipped; "Switch workspace" lives in Settings.
2. 404 on login → fix `Endpoints.login` in `ApiConfig.swift`. 200 but "no access token" → map the field names in `Auth/Session.swift` → `TokenResponse`.
   If the team confirms Firebase sign-in, set the build settings above instead.
3. Confirm the **ASSUMED** rows in `../docs/API_MAP.md` with one DevTools capture; each lives in one `*Request` struct.
