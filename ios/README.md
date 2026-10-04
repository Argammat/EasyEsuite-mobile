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

## First run checklist

1. Sign in with **company = your tenant slug** (e.g. `nationwide`), email, password.
2. 404 on login → fix `Endpoints.login` in `ApiConfig.swift`. 200 but "no access token" → map the field names in `Auth/Session.swift` → `TokenResponse`.
3. Confirm the **ASSUMED** rows in `../docs/API_MAP.md` with one DevTools capture; each lives in one `*Request` struct.
