# EasyEsuite API map (mobile contract)

Both apps code against this file. Every row is marked:

- **VERIFIED** — observed live (MCP call or the web app's own network traffic) on 2026‑10‑03, or confirmed by the
  backend team's *"EasyeSuite API: Items & Inventory (for the mobile app)"* notes (2026‑10‑07, taken from the web
  frontend source on `dev`; full spec in their `docs/docs/api/openapi-backend.yaml`).
- **ASSUMED** — conventional / inferred from the web bundle; confirm in DevTools before shipping.

## Base URLs

| Name | URL | Status |
|---|---|---|
| API root | `https://api-new.easyesuite.com/` | VERIFIED (web bundle constant) |
| Tenant root | `{API root}clients/{tenant}/api/v1/` | VERIFIED (`clients/nationwide/api/v1/...`) |
| Global root | `{API root}api/v1/` | VERIFIED (exists; used for pre‑login / super‑admin calls) |

`tenant` is the company slug the user types at login (web stores it as `logInCompanyName` / `tenantId`).
All paths below are relative to the **tenant root** unless noted.

## Conventions (VERIFIED)

- List endpoints: DRF `limit` / `offset` paging. Response: `{ "count", "next", "previous", "results": [...] }`.
- Dates: ISO‑8601 UTC, `date_after` / `date_before` are inclusive bounds — send same‑day `T00:00:00Z` … `T23:59:59Z`.
  Use the order's own `date` filter, **not** `created_date_*` (that is ERP ingestion time and over‑counts).
- Money comes in three shapes, sometimes on the same record: `"180.00"`, `"$82,765.20"`, `166464`. Decode leniently (see `Money` in both SDKs). Fields suffixed `_new` are plain decimals with a `_new_currency` sibling — prefer those.
- `status` filters on PO lists are **multi‑valued** (`?status=Open&status=Partial%20Received`).
- Standard errors: DRF `{ "detail": "..." }` or `{ "field": ["msg"] }` with 400/401/403/404.

## Auth

**Flow (as on erp.easyesuite.com): email + password first, then pick the workspace.** The web login has no company
field; after signing in the user chooses from their workspaces (`nationwide`, `AMA invesment Group LLC`, …) and can switch
later from the account menu. Both SDKs do the same: `AuthService.login(email, password)` authenticates against the
**global root** (no tenant yet), lists the workspaces, and returns `WorkspaceRequired` (or signs straight in when there is
exactly one); `selectWorkspace(pending, slug)` stores the tenant-scoped session. A session with a blank tenant is
"pending" and never counts as signed in.

The backend team's notes: **`Authorization: Bearer <firebase_id_token>` — sign in with Firebase / Identity Platform against
the workspace's own Firebase tenant ID.** The web app also stores `accessToken` / `refreshToken` / `access_expiration` /
`refresh_expiration` / `firebaseTenantId` and calls `auth/token/refresh/`, so both SDKs implement two strategies behind one
`AuthService`; a config switch (`ApiConfig.firebase`) picks one. Nothing in the UI knows which is active.

| Call | Method / path | Body → Response | Status |
|---|---|---|---|
| Header | `Authorization: Bearer {token}` | the Firebase ID token (Firebase flow) or the backend access token | VERIFIED (backend team) |
| **Firebase sign‑in** | `POST https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key={webApiKey}` | `{email, password, returnSecureToken:true, tenantId}` → `{idToken, refreshToken, expiresIn, localId}` | VERIFIED (Identity Platform REST); **needs the Firebase web API key** (public, ships in the web bundle) |
| Firebase refresh | `POST https://securetoken.googleapis.com/v1/token?key={webApiKey}` | form `grant_type=refresh_token&refresh_token=…` → `{id_token, refresh_token, expires_in}` | VERIFIED (Identity Platform REST) |
| Company → Identity tenant id | `GET {global root}clients/clients/?name={slug}` | → `{slug, display_name, firebase_tenant_id}` (parsed leniently) | **ASSUMED** — the web app stores `firebaseTenantId` per company; the endpoint that returns it must be confirmed. Fallback: build‑time `FIREBASE_TENANT_ID`, then project‑level users |
| Login (backend‑proxied) | `POST {global root}auth/login/` (tenant‑scoped `auth/login/` once inside a workspace) | `{email, password}` → `{access, refresh, user, access_expiration, refresh_expiration}` | **VERIFIED path** (2026‑10‑07: `GET /api/v1/auth/login/` → 405 `Allow: POST, OPTIONS`, DRF view "Pre2Fa Token Obtain" — same view as the tenant‑scoped path). Response body ASSUMED (dj‑rest‑auth keys the web app stores); errors arrive as `{"errors": {...}, "status_code": n}` |
| Workspaces after login | `GET {global root}users/me/tenants/` (fallback: tenant‑scoped `users/me/tenants/`) | → `[{slug, display_name, …}]` parsed leniently; also read from `user.tenants` in the login response when present | **VERIFIED path** (2026‑10‑07: unauthenticated `GET /api/v1/users/me/tenants/` → 403 `{"errors": {"Not authenticated": …}}`, DRF view "My Tenants List"; tenant‑scoped path identical). Row shape unconfirmed → parsed leniently; empty list → the apps ask for the slug |
| Login 2FA step | `POST auth/login/2fa/` | `{...challenge from step 1, otp}` → same as login | VERIFIED path, ASSUMED body |
| Refresh (backend‑proxied) | `POST auth/token/refresh/` | `{refresh}` → `{access, access_expiration}` | VERIFIED path (seen in web traffic), ASSUMED body |
| MFA options | `GET auth/2fa/mfa_options/`, `GET auth/mfa_settings/` | | VERIFIED paths |
| Me | `GET users/users/me/` | profile (`first_name`, `last_name`, `email`, `image`, `groups[]`, `is_superuser`, `finished_onboarding`) | VERIFIED |
| My tenants | `GET users/me/tenants/` | list of workspaces the user can switch to | VERIFIED path, shape unconfirmed |
| Permissions | — | USER role or higher to create/edit items; VIEWER is read‑only | VERIFIED (backend team) |

Token lifecycle (both SDKs): the stored `access_expiration` is checked before every call and the token is renewed
~60 s early (Firebase ID tokens live one hour); a 401 still triggers one refresh + retry; a failed refresh clears the
session. Refresh strategy follows `Session.provider` (`firebase` / `backend`).

> **Two values to obtain from the backend team before the Firebase flow can be switched on:** the Firebase **web API key**
> (`FIREBASE_API_KEY` / `EASYESUITE_FIREBASE_API_KEY`) and either the company→Identity‑tenant lookup endpoint or a per‑build
> `FIREBASE_TENANT_ID`. Until then the apps use the backend‑proxied flow, which is what the web app's stored keys suggest.

## Items & catalog (VERIFIED — backend team notes, 2026‑10‑07)

| Purpose | Method / path | Params / body | Status |
|---|---|---|---|
| Catalog list (all types) | `GET items/items/` | `search, item_type, item_condition, is_available, ordering, limit, offset` — inventory items, kits and variants in one list; each row's `item_type` picks the detail endpoint | VERIFIED |
| Condition filter / dropdown | `GET items/item_conditions/?limit=100` | → `{id, name}` | VERIFIED |
| Tax schedule dropdown | `GET items/tax_schedules/` | → `{id, name, is_default}` | VERIFIED |
| Variation options (variants) | `GET items/variation_options/` | | VERIFIED (not used yet) |
| Item detail — inventory item | `GET items/inventory_items/{id}/` | → full record incl. `marketplace_pricing[]`, per‑marketplace config, `total_quantity_on_hand`, `average_cost`, images | VERIFIED |
| Item detail — kit / bundle | `GET items/kit_package_items/{id}/` | | VERIFIED |
| Item detail — variant | `GET items/variant_items/{id}/` | | VERIFIED |
| Item detail — generic fallback | `GET items/items/{id}/` | used when the type is unknown or the typed endpoint 404s | VERIFIED (live) |
| Stock per warehouse (item page) | `GET items/warehouse_inventory_items/?item={id}&limit=25&offset=0` | rows: `warehouse`, `quantity_on_hand`, `quantity_available`, `quantity_committed`, `quantity_on_order`, `quantity_in_transit`, `reorder_point`, `average_cost` | VERIFIED |
| Kit inventory tab | `GET items/kit_package_items/{id}/kit_package_item_quantity_in_warehouses/` | | VERIFIED (not used yet) |
| History tab | `GET items/inventory_items/{id}/history/` (or `kit_package_items` / `variant_items`) | row shape not captured → decoded leniently (`date`/`created_date`, `transaction_type`/`type`, `quantity`/`quantity_change`, `warehouse`, `memo`…) | VERIFIED path, shape ASSUMED |
| Available qty in every warehouse | `GET items/items/{id}/available_quantity_in_warehouses/` | | VERIFIED (not used yet) |
| **Create inventory item** | `POST items/inventory_items/` | see `CreateItemRequest` and the rules below | VERIFIED |
| Create kit / variant | `POST items/kit_package_items/`, `POST items/variant_items/` | kit rows accept only `{inventory_item, quantity, tax_schedule}`; the web "Add Item ▾" menu offers Add Item / Add Kit Package / Add Variant Item | VERIFIED (designed in the mobile mockups; not implemented in the apps yet) |
| Full save / single‑field edit | `PUT` / `PATCH items/inventory_items/{id}/` | | VERIFIED |
| Item image upload | `POST files/images/` (multipart field `image`) → response field `image` is the URL → put it in `item_images[].image_url` | | VERIFIED |
| Barcode lookup (prefill) | `GET items/get_item_upc/{upc}/` — **path segment, not `?upc=`** | → `{data:{upc,title,description,brand,color,size,weight,dimension,images[]}}` (rate‑limited 100/h) | VERIFIED |
| Warehouses (picker) | `GET items/warehouses/?limit=1000` | → `id, name, is_active, is_default, warehouse_type` | VERIFIED |
| Opening stock for a new item | `POST items/warehouse_inventory_items/` | `{inventory_item, warehouse, quantity_on_hand}` ("initial stocking" per the MCP tool description) | ASSUMED body |
| Global search | `GET core/global_search/` | `query=` → ranked typed results | VERIFIED |

**Create body rules (backend team):**

- The seven `*_marketplace` objects (`amazon_`, `ebay_`, `walmart_`, `target_`, `best_buy_`, `macys_`, `mercado_marketplace`)
  are **required** — send `{}` for each one you don't use; Amazon wants `{"amazon_fba_account_1": false, "amazon_fba_account_2": false}`.
- `marketplace_pricing` is **required** — `[]` or rows `{ "marketplace": <id>, "price": "9.99", "price_currency": "USD" }`.
- Send empty optional fields as `null` (or omit them), never `""` — `purchase_price: ""` is a 400.
- Don't send the spec's read‑only fields (`id`, `created_date`, …).
- `name` must be unique per item type — duplicate → 400 *"An item with the same name and type already exists"*.
- `item_type` codes: `INV` verified; `KIT` / `VAR` are the web filter values and still need confirming.

Both `CreateItemRequest` models always emit the seven blocks + `marketplace_pricing: []` and omit nulls (unit‑tested).

## Inventory viewing (VERIFIED — backend team notes, 2026‑10‑07)

| Purpose | Method / path | Params | Status |
|---|---|---|---|
| All inventory (per item, warehouses summed) | `GET items/inventory_items/` | `search, is_available, item, ordering, limit, offset` | VERIFIED |
| All inventory totals | `GET items/inventory_items/get_inventory_totalization/` | same filters; response flattened into cards (`DashboardCards`) | VERIFIED path, shape flattened leniently |
| Warehouse inventory (per item per warehouse) | `GET items/warehouse_inventory_items/` | `warehouse_id, item, is_available, warehouse_type, limit, offset` — note **`warehouse_id`**, not `warehouse` | VERIFIED |
| Warehouse inventory totals | `GET items/warehouse_inventory_items/totalization/` | `warehouse_id, is_available` | VERIFIED path |
| Kit inventory | `GET items/kit_package_items/{id}/kit_package_item_quantity_in_warehouses/` | | VERIFIED |
| Warehouses (picker) | `GET items/warehouses/?limit=1000` | | VERIFIED |

"Below reorder point" has no server parameter and is filtered on the device.

## Inventory operations

| Purpose | Method / path | Params / body | Status |
|---|---|---|---|
| Transfers list | `GET inventory_transfers/inventory_transfers/` | `limit, offset, transfer_status (Completed|In Transit|Voided), from_warehouse_id, to_warehouse_id, date_after/before` | VERIFIED |
| Transfer detail | `GET inventory_transfers/inventory_transfers/{id}/` | | VERIFIED |
| Create transfer | `POST inventory_transfers/inventory_transfers/` | `{from_warehouse, to_warehouse, date, remarks, items:[{item, quantity}]}` | ASSUMED body |
| Complete transfer | `POST inventory_transfers/inventory_transfers/{id}/complete/` | | ASSUMED (MCP task `complete_transfer`) |
| Manual adjustments | `GET|POST inventory_transfers/manual_adjustments/` | create: `{warehouse, date, memo, items:[{item, quantity, reason}]}` | ASSUMED body |
| Purchase orders | `GET purchase_orders/purchase_orders/` | `limit, offset, status[] , vendor, warehouse, date_after/before` → `number, company, warehouse, status, total_amount_new, received_amount_new, total_quantity_ordered_new, items[] (ids)` | VERIFIED |
| PO detail | `GET purchase_orders/purchase_orders/{id}/` | | VERIFIED |
| PO lines | `GET purchase_orders/purchase_order_items/` | `purchase_order={id}` | ASSUMED (MCP `purchase_order_item`) |
| Receive PO | `POST purchase_orders/receipts/` | `{purchase_order, warehouse, received_date, memo, items:[{purchase_order_item, quantity}]}` | ASSUMED body |

## Orders & shipping

| Purpose | Method / path | Params / body | Status |
|---|---|---|---|
| Sales orders | `GET sales_orders/sales_orders/` | `limit, offset, status, marketplace (id), warehouse, number, po_number, date_after/before, search` | VERIFIED |
| Order detail | `GET sales_orders/sales_orders/{id}/` | → header + `items[]` lines + `ship_to{}` | VERIFIED |
| Update order | `PATCH sales_orders/sales_orders/{id}/` | `{memo, po_number, status}` | VERIFIED |
| Fulfill | `POST sales_orders/fulfillments/` | `{sales_order, warehouse, tracking_number, shipping_carrier, items:[{sales_order_item, quantity}]}` | ASSUMED body (MCP task `fulfill_sales_order`) |
| Shipments | `GET shipping/shipments/` | `limit, offset, status (to_ship|on_hold|shipped|voided|exceptions), search` | VERIFIED (web uses `status`) |
| Shipment detail | `GET shipping/shipments/{id}/` | | VERIFIED |
| Buy label | `POST shipping/shipments/{id}/buy/` | `{rate_id}` | ASSUMED body |
| Re‑rate | `POST shipping/shipments/{id}/rerate/` | | VERIFIED path |
| Label | `GET shipping/shipments/{id}/label/` / `download_label/` | → `label_url` / PDF | VERIFIED paths |
| Tracking | `GET shipping/shipments/{id}/tracking_details/` | | VERIFIED path |
| Hold / unhold | `POST shipping/shipments/{id}/hold/` , `unhold/` | `{reason}` | VERIFIED paths |
| Resolve scanned barcode | `GET shipping/shipments/resolve_barcode/` | `barcode=` → shipment | VERIFIED path |
| Scan‑to‑verify pack | `POST shipping/shipments/{id}/verify_scan/` | `{upc}` ; status `GET .../pack_verification/` | VERIFIED paths |
| Shipping balance | `GET shipping/balance/` | | VERIFIED path |

Marketplace ids (VERIFIED): Amazon=2, eBay=4, Target=5, Best Buy=6, Macy's=7, Temu=12 (Walmart id: read from `connector-v2/accounts/`).

### Sales finance (VERIFIED live, 2026‑10‑07) — the web app's Invoices and Payments pages

| Purpose | Method / path | Params / fields | Status |
|---|---|---|---|
| Invoices | `GET sales_orders/invoices/` | `status` (Open · Paid · Partial Paid · Voided), `marketplace`, `sales_order`, `customer_id`, `search` (IN‑…), `date_after/before`, `ordering` (date, status, created_date, total_amount_with_original_currency) → `number, status, invoice_type, return_status, company_name, customer_name, sales_order_number, sales_order_id, marketplace_name, warehouse_name, shipping_method_name, po_number, date, due_date, terms_name, total_amount, open_amount, paid_amount_new, total_tax_amount, shipping_cost, total_quantity` | VERIFIED |
| Invoice detail | `GET sales_orders/invoices/{id}/` | numeric id (not the IN‑ number) | VERIFIED |
| Customer payments | `GET sales_orders/payments/` | `ordering=-date` → `number (PYMT‑…), status, customer_name, payment_method_name, bank_name, ref_number, check_number, memo, date, amount, applied_amount_new, un_applied_amount_new` | VERIFIED (`search` ASSUMED) |
| Payment detail | `GET sales_orders/payments/{id}/` | | VERIFIED path |
| Payment receipt · apply payments · pay single invoice · customer refunds | web‑only for now (`payment-receipt/new`, `apply-payment`, `pay-single-invoice`, `refund`) | MCP: `payment_receipt`, `apply_payment`, `pay_invoice`, `sales_orders_customer_refunds` | not on mobile yet |

## Dashboard & reports

| Purpose | Method / path | Params | Status |
|---|---|---|---|
| Overview cards | `GET dashboard/overview/get_overview_cards_data/` | `date_after, date_before` | VERIFIED path (web dashboard) — shape decoded leniently as key/value cards |
| Best sellers | `GET dashboard/items/get_best_sellers/` | `date_after, date_before, limit` | VERIFIED path |
| Orders $ by marketplace over time | `GET dashboard/marketplace_orders/get_marketplace_orders_total_amount_date_intervals/` | `date_after, date_before` | VERIFIED path |
| Units sold over time | `GET dashboard/invoice_dashboards/get_invoice_items_quantity_sold_date_intervals/` | `date_after, date_before` | VERIFIED path |
| Sales by item (recognised sales) | `GET sales_orders/invoice_items/invoice_analytics/` | `date_after, date_before, split_by_marketplace (false for ranking), ordering (-total_amount|-total_quantity|-total_profit), limit, search, item` → `item_id, item_name, item_upc, total_amount, total_quantity, total_per_qty, total_profit, marketplace, item_images[]` | VERIFIED |
| Carrier spend | `GET shipping/reports/carrier_spend/` , `cost_per_shipment/` | `date_after, date_before` | VERIFIED paths |
| Counts (cheap) | any list with `limit=1` → read `count` | | VERIFIED |

## AI assistant

| Purpose | Method / path | Body | Status |
|---|---|---|---|
| Copilot | `POST ai/ai_copilot_agent_v2/` | `{message, thread_id?}` → `{answer|response|message, thread_id, suggestions[]}` | VERIFIED path; body/response ASSUMED — the web Copilot (`CopilotSurface`) posts here; copy its payload from DevTools |
| Feature flags | `GET ai/ai_feature_settings/` | | VERIFIED |
| Credits | `GET ai/ai_credits/status/` | | VERIFIED |

## Not used on mobile (yet)

`accounting/*`, `connector-v2/*` (except account list for marketplace ids), `zendesk/*`, `shipping/batches/*`, `shipping/scan_forms/*`.
