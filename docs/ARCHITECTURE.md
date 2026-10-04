# Architecture

Two native apps, one contract. The layering is identical on both platforms so a change to an endpoint is a
one-file edit on each side and the screens never touch HTTP.

```
┌────────────── UI ──────────────┐   SwiftUI views / Compose screens + one ViewModel per screen
│  Features/* (iOS)  ui/* (Android)│   (PagedListModel / PagedListViewModel for every list)
└───────────────┬────────────────┘
                │ repositories (async, typed)
┌───────────────▼────────────────┐   EasyEsuiteKit (Swift package)  /  :core (Kotlin JVM)
│  Repo:   Items · Inventory · Orders · Shipping · Reports · Assistant
│  Auth:   AuthService · Session · TokenStore (Keychain / EncryptedSharedPreferences)
│  Net:    APIClient — tenant URL prefix, Bearer header, 401 → refresh → retry, DRF error parsing
│  Model:  Codable / kotlinx.serialization structs mirroring the API (Money, Page, …)
└───────────────┬────────────────┘
                │ HTTPS
        https://api-new.easyesuite.com/clients/{tenant}/api/v1/…
```

## Decisions

**Tenant in the URL.** Every call is `clients/{tenant}/api/v1/…`. The login screen asks for the company slug, the
`ApiConfig` is built per tenant, and the whole object graph (`AppContainer.Graph`) is rebuilt on sign-in/out so no
ViewModel can outlive its tenant. The root view re-keys on the tenant (`.id(tenant)` / `key(tenant)`).

**Auth = dj-rest-auth JWT.** Access + refresh tokens in secure storage. The client refreshes once on a 401 (one in-flight
refresh shared by concurrent requests) and retries; a second 401 clears the session and bounces to login. 2FA is modelled
as a second step (`auth/login/2fa/`) — the login screen shows a code field when the first response carries a challenge.

**Lenient models.** The API mixes `"$1,234.00"`, `"180.00"` and `166464` for money, string and numeric ids, and
provider-shaped JSON for dashboards/tracking/copilot. `Money` decodes all shapes; `FlexibleString`/`isLenient`
absorb id types; dashboard cards, series and copilot replies are parsed from loose JSON with documented key fallbacks,
so a backend field rename degrades to "missing card", never to a crash.

**Invoices, not sales orders, for "what sold".** Orders are commitments; invoices are recognised sales. Reports and
dashboard top-sellers use `invoice_items/invoice_analytics` with `split_by_marketplace=false` for rankings, exactly as the
MCP server instructs. Counts use `limit=1` list calls and read `count` — cheap and reliable.

**Dates.** The API wants inclusive ISO‑8601 UTC bounds. `DateRange` presets are calendar days in the user's zone
converted to `date_after=…T00:00:00Z` / `date_before=…T23:59:59Z` (tested against America/Los_Angeles).

**Scanning.** One scanner (AVFoundation / CameraX + ML Kit) feeds a resolver: 8–14 digit codes → catalog lookup (with
EAN‑13 ⇄ UPC‑A variants), anything else → `shipping/shipments/resolve_barcode/`, `SO-…` → order lookup. Exactly one hit
navigates straight there; an unknown UPC offers "create item" with the UPC pre-filled and the product-database lookup
pre-populating the form.

**No DI framework, no Room/CoreData (yet).** Hand-rolled container; everything is fetched live. Offline queues for
warehouse actions are on the roadmap once the write endpoints are confirmed.

## Platform notes

- **iOS 16+**: NavigationStack with a single `Route` enum, Swift Charts for the dashboard, `PhotosPicker` + `UIImagePickerController`
  for photos, Keychain for tokens. SDK is a local Swift package with its own `swift test` suite (macOS-runnable).
- **Android minSdk 26**: Compose Material 3, Navigation Compose string routes, CameraX + ML Kit, EncryptedSharedPreferences,
  Coil for images. `:core` is pure JVM and is excluded from nothing — `:app` is only included when an SDK is present.
- Both: ViewModels are `@MainActor` / `viewModelScope`; list screens share one paged-list base; errors are mapped to
  short user messages in one place (`userMessage`).

## Adding an endpoint

1. Add the path to `Endpoints` (both platforms) and a row to `docs/API_MAP.md` with its status.
2. Add/extend a model (optional fields unless verified).
3. Add a repository method; add a fixture to `shared/fixtures` and a decode test on both sides.
4. Build the screen on top of the repository.
