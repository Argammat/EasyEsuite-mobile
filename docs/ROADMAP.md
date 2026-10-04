# Roadmap

## V1.0 — ship the warehouse loop (this repo)
- [x] Item creation with UPC prefill, photos, opening stock
- [x] Inventory: company / per-warehouse stock, low-stock filter, item stock by warehouse
- [x] Transfers, receive PO, adjustments
- [x] Orders list/detail/fulfil; shipments list/detail; rates → buy label; hold/release; tracking; scan-to-verify
- [x] Dashboard, sales-by-item report, carrier spend
- [x] Copilot chat
- [x] CI: both apps compile and SDK tests pass on GitHub Actions
- [ ] Confirm every **ASSUMED** row in `docs/API_MAP.md` against the web app (one DevTools session)
- [ ] App icons, launch screen, store listings
- [ ] Crash/analytics SDK (Sentry is already used on the web app)

## V1.1 — warehouse hardening
- [ ] Offline queue for receive / adjust / verify-scan (persist intents, replay with idempotency keys)
- [ ] Bluetooth/HID scanner support (keyboard-wedge input already works in the manual field)
- [ ] Print label to a Bluetooth/AirPrint printer from the phone; batch pick lists (`shipping/batches`)
- [ ] Push notifications: order exceptions, label purchase failures, low stock (needs a device-token endpoint)
- [ ] Multi-tenant switcher using `users/me/tenants/`

## V1.2 — owner mode
- [ ] Settlement reconciliation status (`connector_v2/settlements/summary`) and payout alerts
- [ ] AR / AP aging summaries
- [ ] FBA inbound shipment creation and reimbursement cases (ties to the planned FBA module)
- [ ] Widgets (iOS/Android) for today's orders and to-ship count

## Later
- [ ] Kit / variant creation (currently standard items only)
- [ ] Marketplace listing status and price edits per marketplace
- [ ] Customer / vendor lookup from the global search
