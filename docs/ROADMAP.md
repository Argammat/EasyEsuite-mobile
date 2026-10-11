# Roadmap

Scope is mapped page-by-page against the web app in [FEATURE_COVERAGE.md](FEATURE_COVERAGE.md).

## V1.0 — ship the warehouse loop (this repo)
- [x] Sign-in like the web: email + password → 2FA → workspace picker; "Switch workspace" in Settings
- [x] Item creation with UPC prefill, photos, opening stock
- [x] Inventory: company / per-warehouse stock, low-stock filter, item stock by warehouse, item history
- [x] Transfers, receive PO, adjustments
- [x] Ecommerce tab: orders list/detail/fulfil; shipments list/detail; rates → buy label; hold/release; tracking; scan-to-verify; invoices; payments
- [x] Dashboard, sales-by-item report, carrier spend
- [x] Copilot chat
- [x] Web "Modern" theme, app icons (iOS + Android adaptive), vector logo
- [x] CI: both apps compile and SDK tests pass on GitHub Actions
- [x] TestFlight pipeline (`.github/workflows/testflight.yml`, `ios/scripts/asc-bootstrap.py`, `docs/TESTFLIGHT.md`), privacy manifest
- [ ] Apple Developer Program enrollment for the EasyEsuite entity → App Store Connect API key → first TestFlight build
- [ ] First real-device sign-in and scan; fix whatever the live response shapes turn out to be
- [ ] Confirm the remaining **ASSUMED** rows in `docs/API_MAP.md` (write bodies: transfers, receipts, adjustments, fulfillments, buy label, copilot)
- [ ] Receivables screens that are designed but not coded: invoice detail, payment receipt, pay single invoice, apply payments
- [ ] Store listings: screenshots, privacy policy / support URLs, App Privacy answers, demo account for review
- [ ] Crash/analytics SDK (Sentry is already used on the web app)

## V1.1 — warehouse hardening
- [ ] Kit / variant creation (`items/kit_package_items/`, `items/variant_items/`) — designed in the canvas
- [ ] Offline queue for receive / adjust / verify-scan (persist intents, replay with idempotency keys)
- [ ] Bluetooth/HID scanner support (keyboard-wedge input already works in the manual field)
- [ ] Print label to a Bluetooth/AirPrint printer from the phone; batch pick lists (`shipping/batches`)
- [ ] Push notifications: order exceptions, label purchase failures, failed orders/invoices, low stock (needs a device-token endpoint)
- [ ] On-order / on-purchase columns in inventory; backorders chip on orders; cost-per-shipment report; assistant chat history

## V1.2 — owner mode
- [ ] "Brief Me" card on the dashboard (AI Hub) with a morning push
- [ ] Connector health card (connector Monitor) and announcements
- [ ] Settlement reconciliation status (`connector_v2/settlements/summary`) and payout alerts
- [ ] AR / AP aging summaries
- [ ] Scan-to-return at the dock (invoice returns), end-of-day SCAN forms
- [ ] FBA reimbursement case status
- [ ] Widgets (iOS/Android) for today's orders and to-ship count

## Later
- [ ] Marketplace listing status and price edits per marketplace
- [ ] Customer / vendor lookup from the global search (balances, open invoices)
- [ ] Item Intelligence section on item detail; AI Generate quick-add
- [ ] Create a support ticket with a photo; receipt photo → expense
