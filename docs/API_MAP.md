# EasyEsuite API map (mobile contract)

Both apps code against this file. Every row is marked:

- **VERIFIED** — observed live (MCP call or the web app's own network traffic) on 2026‑10‑03, or confirmed by the
  backend team's *"EasyeSuite API: Items & Inventory (for the mobile app)"* notes (2026‑10‑07) and their
  *"Mobile App — API Integration Answers"* (2026‑10‑09; auth, Firebase, history). Full spec: their `docs/docs/api/openapi-backend.yaml`.
- **ASSUMED** — conventional / inferred from the web bundle; confirm in DevTools before shipping.

## Base URLs

| Name | URL | Status |
|---|---|---|
| API root (production) | `https://api-new.easyesuite.com/` | VERIFIED |
| API root (staging / development) | *not committed* — the backend team's answers doc lists them; builds read `EASYESUITE_API_ROOT` | VERIFIED (team answers 2026‑10‑09) |
| Tenant root | `{API root}clients/{schema_name}/api/v1/` | VERIFIED |
| Global root | `{API root}api/v1/` | VERIFIED (only `auth/login/tenants/` is used there) |

`schema_name` is the workspace identifier returned by step 1 of sign‑in (below). All paths are relative to the
**tenant root** unless noted. A build's base URL and Firebase config must come from the **same environment**;
development and staging builds stay on internal testing tracks.

## Conventions (VERIFIED)

- List endpoints: DRF `limit` / `offset` paging. Response: `{ "count", "next", "previous", "results": [...] }`.
- Dates: ISO‑8601 UTC, `date_after` / `date_before` are inclusive bounds — send same‑day `T00:00:00Z` … `T23:59:59Z`.
  Use the order's own `date` filter, **not** `created_date_*` (that is ERP ingestion time and over‑counts).
- Money comes in three shapes, sometimes on the same record: `"180.00"`, `"$82,765.20"`, `166464`. Decode leniently (see `Money` in both SDKs). Fields suffixed `_new` are plain decimals with a `_new_currency` sibling — prefer those.
- `status` filters on PO lists are **multi‑valued** (`?status=Open&status=Partial%20Received`).
- Standard errors: `{ "errors": {...}, "status_code": n }` (also DRF `{ "detail": "..." }` / `{ "field": ["msg"] }`) with 400/401/403/404; `429` = rate‑limited (back off).

## Auth (VERIFIED — backend team answers, 2026‑10‑09)

**The backend login is the permanent flow.** Every REST call uses the backend JWT. Firebase is a *second, optional*
sign‑in, done after it with a token the backend provides, used only for the Firestore‑backed screens.

| Step | Method / path | Body → Response | Notes |
|---|---|---|---|
| 1. Workspaces | `POST {global root}auth/login/tenants/` | `{email, password}` → `[{tenant_id, schema_name, display_name, company_name (deprecated), logo_url, initials, last_active_at}]`, most recently used first | Returns **no token**. One workspace → skip the picker. `401` is the same body for every reason (wrong password, unknown email, locked, inactive) → one generic message. `429` per IP and per email → back off |
| 2. Login | `POST /clients/{schema_name}/api/v1/auth/login/` | `{email, password}` → `{access (5 min), refresh (1 day), firebase_custom_token? , user: {pk, email, tenant_id, firebase_tenant_id, is_mfa_enabled, is_mfa_mandatory, needs_password_reset, all_role_names[], plan_name}}` | Header from here on: `Authorization: Bearer {access}`. `needs_password_reset` → do not continue (reset on the web). `is_mfa_enabled` / `is_mfa_mandatory` → a one‑time‑code step follows |
| Refresh | `POST auth/token/refresh/` | `{refresh}` → **new `access` and new `refresh`** | Each refresh token works **once**; store both; only one refresh in flight (queue the rest); failure → login screen. Both SDKs also renew ~60 s before expiry (from `access_expiration` or the JWT `exp` claim) |
| 3. Firebase | `POST https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key={apiKey}` | `{token: firebase_custom_token, returnSecureToken: true, tenantId: user.firebase_tenant_id}` → `{idToken, refreshToken, expiresIn}` | Only when `firebase_tenant_id` is not null. **Never `signInWithEmailAndPassword`** (Firebase users have no password). Failure is tolerated: REST keeps working, Firestore screens show "unavailable" |
| Firebase refresh | `POST https://securetoken.googleapis.com/v1/token?key={apiKey}` | form `grant_type=refresh_token&refresh_token=…` → `{id_token, refresh_token, expires_in}` | VERIFIED (Identity Platform REST) |
| Login 2FA step | `POST auth/login/2fa/` | `{email, ephemeral_token, code}` → same as login | VERIFIED path, **ASSUMED body** — exact MFA exchange still to confirm (question sent to the team) |
| Me | `GET users/users/me/` | profile (`first_name`, `last_name`, `email`, `image`, `groups[]`, `is_superuser`, `finished_onboarding`) | VERIFIED |
| Permissions | — | USER role or higher to create/edit items; VIEWER is read‑only | VERIFIED (backend team) |

Not provided, by design: a company‑name → tenant‑id lookup (would let anyone list customers) and a per‑build tenant id
(one person can belong to several companies). Switching workspaces therefore re‑runs steps 1–2 with the password; the
apps never store the password.

### Firebase / Firestore (VERIFIED — backend team answers, 2026‑10‑09)

The web app reads these from Firestore, not from REST: AI Copilot conversations (threads + messages), AI reports /
trends / news / events calendar, marketplace connector accounts and live sync status (all in the **connector**
database), and in‑app notifications — list, unread count, read state (**notifications** database). Documents live
under `tenants/{user.tenant_id}/…` (the **numeric** id, not the Firebase tenant id); notifications under
`tenants/{tenant_id}/users/{user.pk}/notifications`. Sending a Copilot message stays a REST call.

Per‑environment values (`apiKey`, `projectId`, `appId`, the two named databases — never `(default)`) are in the team's
answers doc and are **not committed here** (the repository is public): iOS reads them from
`ios/Config/Environment.local.xcconfig` / CI secrets, Android from `android/easyesuite.properties` / CI secrets.
Both SDKs ship a read‑only Firestore REST client (`FirestoreClient`) authenticated with the Firebase ID token; the exact
document paths and sample documents per screen come from the team once we name the first‑release screens.

### Security rules the apps follow (backend team)

Tokens only in Keychain / Keystore; never logged or sent to crash reporters (OkHttp logging is `BASIC` — no headers or
bodies); HTTPS only; the password is never stored; sign‑out deletes both JWTs and the Firebase tokens; only test accounts
on dev/staging; non‑production builds stay internal; anything unexpected (leaked token, another company's data) is
reported immediately so sessions can be revoked.

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
| History tab | `GET items/inventory_items/{id}/history/` (`kit_package_items` / `variant_items` by `item_type`) | **paginated** (`limit`/`offset`, 25 per page; the OpenAPI array shape is a doc error) → `results: [{id, action ("create"/"update"/"delete"/"access"), actor (email or null = system), timestamp (UTC), content_type, changes: {field: [old, new]}}]` + other audit fields to ignore. Filters: `action` (**integer** 0–3), `field`, `actor` (user id), `search_history`, `start_date_history`, `end_date_history`, `sort_by` (`asc`/`desc`). The web app shows one row per changed field | VERIFIED (team answers 2026‑10‑09) |
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
- `item_type` has exactly three values — `INV` (inventory item), `KIT` (kit/package), `VAR` (variant) — VERIFIED (team answers 2026‑10‑09).

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
