# EasyEsuite API map (mobile contract)

Both apps code against this file. Every row is marked:

- **VERIFIED** — observed live (MCP call or the web app's own network traffic) on 2026‑10‑03.
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

| Call | Method / path | Body → Response | Status |
|---|---|---|---|
| Login | `POST auth/login/` | `{email, password}` → `{access, refresh, user, access_expiration, refresh_expiration}` | ASSUMED (dj‑rest‑auth; web stores exactly `accessToken`, `refreshToken`, `access_expiration`, `refresh_expiration`) |
| Login 2FA step | `POST auth/login/2fa/` | `{...challenge from step 1, otp}` → same as login | VERIFIED path, ASSUMED body |
| Refresh | `POST auth/token/refresh/` | `{refresh}` → `{access, access_expiration}` | VERIFIED path (seen in web traffic), ASSUMED body |
| MFA options | `GET auth/2fa/mfa_options/`, `GET auth/mfa_settings/` | | VERIFIED paths |
| Header | `Authorization: Bearer {access}` | | ASSUMED (simplejwt default) |
| Me | `GET users/users/me/` | profile (`first_name`, `last_name`, `email`, `image`, `groups[]`, `is_superuser`, `finished_onboarding`) | VERIFIED |
| My tenants | `GET users/me/tenants/` | list of workspaces the user can switch to | VERIFIED path, shape unconfirmed |

> First thing to do in Xcode/Android Studio: log in on the web with DevTools open, copy the exact login request/response, and adjust `AuthService` if a field name differs. Everything is in one file per platform.

## Items & catalog

| Purpose | Method / path | Params / body | Status |
|---|---|---|---|
| Catalog list (all types) | `GET items/items/` | `limit, offset, search` | VERIFIED (`search` ASSUMED) |
| Item detail | `GET items/items/{id}/` | → full item incl. `marketplace_pricing[]`, per‑marketplace config, `total_quantity_on_hand`, `average_cost`, images | VERIFIED |
| Inventory list (web "Inventory" page) | `GET items/inventory_items/` | `limit, offset, is_available (bool), order_by_quantity_on_hand (number), search` | VERIFIED |
| Inventory item detail / update | `GET|PATCH items/inventory_items/{id}/` | | VERIFIED |
| **Create item** | `POST items/inventory_items/` | see `CreateItemRequest` — keys mirror the web form (`name`, `upc_code`, `marketplace_title`, `description`, `marketplace_brand`, `marketplace_platform`, `manufacturer`, `purchase_price`, `weight`, `weight_unit`, `length/width/height`, `dimension_unit`, `reorder_point`, `item_condition`, `sales_description`, `purchase_description`, `costing_method`, `cost_estimation_type`, `calculate_quantity_discounts_type`, `taxable`, `item_images[]`) | ASSUMED endpoint; field names VERIFIED from the web "Add item" form |
| Stock per warehouse | `GET items/warehouse_inventory_items/` | `item={id}` (also `warehouse=`) → rows with `warehouse`, `quantity_on_hand`, `quantity_available`, `quantity_committed`, `quantity_on_order`, `quantity_in_transit`, `reorder_point`, `average_cost` | VERIFIED |
| Opening stock for a new item | `POST items/warehouse_inventory_items/` | `{inventory_item, warehouse, quantity_on_hand}` ("initial stocking" per the MCP tool description) | ASSUMED body |
| UPC product lookup (prefill) | `GET items/get_item_upc/` | `upc=` → `{data:{upc,title,description,brand,color,size,weight,dimension,images[]}}` (rate‑limited 100/h) | VERIFIED |
| Warehouses | `GET items/warehouses/` | `search` → `id, name, is_active, is_default, warehouse_type` | VERIFIED |
| Item image upload | `POST files/images/` (multipart `image`) → `{image_url}` | | ASSUMED — web "Photos" uploader; confirm path |
| Global search | `GET core/global_search/` | `query=` → ranked typed results | VERIFIED |

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
