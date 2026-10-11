# Web → mobile feature coverage

Keyed to the EasyEsuite web feature list **v2.44.0** (≈110 pages in 14 areas; the same list lives in the product
audit doc under "Feature list"). The mobile apps are deliberately *not* the whole ERP: they cover the work that
happens away from a desk — receiving, counting, picking, shipping, looking something up, approving — plus an
owner's glance at the numbers. Everything else stays on the web, one tap away.

**Legend** — ✅ built in both apps · ◐ partly built · 🎨 designed in the canvas, not coded · 🔜 on the roadmap ·
💡 good mobile fit, proposed · 🖥 stays web-only (desk work, setup, or bulk editing)

**Totals (by web page/feature):** ✅ 21 · ◐ 10 · 🎨 4 · 🔜 5 · 💡 10 · 🖥 ≈ 60. Roughly a quarter of the web app's pages
are on the phone today, which is the warehouse + sales loop plus the dashboard; the designed and roadmap items
add receivables actions and kit/variant creation on top.

## Dashboard
| Web | Mobile | Notes |
|---|---|---|
| Sales, units, profit, inventory value vs prior period | ✅ | `dashboard/overview/get_overview_cards_data/` tiles |
| Sales & units chart with date range | ✅ | Swift Charts / Compose; `…total_amount_date_intervals`, `…quantity_sold_date_intervals` |
| Connector health | 💡 V1.2 | health card on the dashboard + push on failure (connector Monitor data) |
| Top products | ✅ | `dashboard/items/get_best_sellers/` |
| Announcements | 💡 | `public_announcements` — one card, cheap |

## Items
| Web | Mobile | Notes |
|---|---|---|
| Items Info: inventory / kit / variant catalog with photos | ✅ | list with type badges, detail with stock by warehouse and history (type-specific endpoints) |
| Quick-add by UPC Lookup | ✅ | scan or type → `items/get_item_upc/{upc}/` prefill |
| Quick-add by AI Generate | 🖥 | later 💡 once the AI generate endpoint is mapped |
| Full Listing | ◐ | core fields, photos, opening stock; marketplace listing fields stay on the web |
| SEO Optimize | 🖥 | |
| Marketplace settings / publishing per item, List on Temu, sync, export | 🖥 | *Later*: read-only marketplace status per item |
| Transfers between warehouses | ✅ | list, create (`inventory_transfers/…`) |
| Inventory by item or warehouse | ✅ | on hand / available, company and per-warehouse totals; on-order / on-purchase columns 🔜 |
| Movements history | ◐ | per-item history tab ✅; the global Movements page 🖥 |
| Adjustments | ◐ | create ✅; audit trail, print, void 🖥 |

## Purchase
| Web | Mobile | Notes |
|---|---|---|
| Purchase Orders (vendor, warehouse, terms, attachments, expenses, prepaid) | ◐ | open-PO list to pick from ✅; PO creation/editing 🖥 |
| Purchase Details (line level) | ✅ | lines with ordered / received inside Receive |
| Receive PO | ✅ | `purchase_orders/receipts/` with per-line quantities |
| Bills, Expense Bills, Expense Bill Details | 🖥 | |

## Ecommerce
| Web | Mobile | Notes |
|---|---|---|
| Orders (create, sync, void, print; invoice / fulfillment / audit tabs) | ◐ | list + detail ✅ with invoices tab via Ecommerce → Invoices; create / sync / void / print / audit 🖥 |
| Orders Details | ✅ | lines, addresses, fulfil, memo |
| Orders Analytics | 🖥 | sales-by-item report covers the mobile need |
| Fulfill Orders | ✅ | `sales_orders/fulfillments/` |
| Backorders | 💡 | a status chip on the orders list |
| Failed Orders & Invoices (retry, skip, import failures) | 💡 / 🖥 | push alert on failures 💡; resolution on the web |
| Marketplace setup (Amazon, eBay, Walmart) | 🖥 | |

## EasyShip
| Web | Mobile | Notes |
|---|---|---|
| To-Ship: buy label, group by, due dates | ◐ | rates → buy ✅, ship-by date ✅; group-by 🖥 |
| On Hold | ✅ | hold / release |
| Shipments: tracking, print, scan packing slip, tags | ◐ | tracking ✅, scan packing slip / verify ✅ (`resolve_barcode`, `verify_scan`); print from phone 🔜 V1.1; tags 🖥 |
| Voided (re-ship, refund status) | ◐ | tab ✅; re-ship 🖥 |
| Delivery Exceptions | ✅ | tab with exception code/detail |
| Batches | 🔜 V1.1 | pick lists from `shipping/batches` |
| Scan Forms | 💡 | end-of-day SCAN form at carrier pickup is a dock task |
| Carriers (USPS, DHL Express, FedEx, own accounts) | 🖥 | buying uses whatever is configured |
| Ship From / return addresses, Printing Setup, Automation Rules | 🖥 | |
| Billing: prepaid balance and auto-reload | ◐ | balance read ✅; top-up / auto-reload 🖥 |
| Reports: cost per shipment, carrier spend, refund and void | ◐ | carrier spend ✅; cost per shipment 🔜 (endpoint mapped); refund & void 🖥 |
| Settings: EasyShip or ShipStation, ShipStation import | 🖥 | |

## Claims (Amazon FBA)
| Web | Mobile | Notes |
|---|---|---|
| Inventory Ledger, Removal Shipment, Returns Report, Shipment Item, Shipments Fee Report, Reimbursement | 🖥 | V1.2 idea: reimbursement case status for the owner |

## Invoices
| Web | Mobile | Notes |
|---|---|---|
| Invoices | ✅ | Ecommerce → Invoices: Open / Paid / Partial Paid / Voided, open amount, tap → order |
| Invoice Details | 🎨 | designed; `sales_orders/invoices/{id}/` |
| Invoices Analytics | ✅ | Reports → sales by item (`invoice_items/invoice_analytics/`) |
| Invoice Returns, Return Refund, Credit Memo | 💡 V1.2 | scan-to-return at the receiving dock; today 🖥 |
| Return Setup | 🖥 | |

## Payments
| Web | Mobile | Notes |
|---|---|---|
| Payments List | ✅ | Ecommerce → Payments with unapplied filter |
| Payment Receipt | 🎨 | designed (`payment_receipt`) |
| Pay Single Invoice | 🎨 | designed (`pay_invoice`) |
| Apply Payments (auto / manual, credit memos, unapplied) | 🎨 | designed (`apply_payment`) |
| Customer Refunds, Bill Payments, Vendor Prepayments | 🖥 | |

## Users
| Web | Mobile | Notes |
|---|---|---|
| Customers and Vendors (balances, credit limits) | 💡 Later | customer / vendor lookup from search, balance + open invoices |
| Users (active, staff, locked, must-reset, super admin) | 🖥 | |

## Finance
| Web | Mobile | Notes |
|---|---|---|
| Extras, Expenses, Banks, Expense Transactions | 🖥 | later 💡: photograph a receipt → expense |

## Accounting
| Web | Mobile | Notes |
|---|---|---|
| AP, AR, Open Invoices, Open Bills, Expense Bills reports | ◐ | Open Invoices ≈ Invoices "Open" filter ✅; the rest 🖥 |
| Balance Checkpoint, Chart of Accounts | 🖥 | |
| Operations, Journals, Operation Config | 🖥 | |
| Reports (Trial Balance … Inventory Listing) | 🖥 | AR / AP aging summaries 🔜 V1.2 (owner mode); inventory value already on the dashboard ✅ |

## AI
| Web | Mobile | Notes |
|---|---|---|
| AI Agent: assistant panel with chat history | ◐ | chat ✅ (`ai/ai_copilot_agent_v2/`), credits status ✅; history list 🔜 |
| AI Hub: Brief Me | 💡 V1.2 | morning brief as a dashboard card + push |
| AI Hub: Item Intelligence | 💡 | section on item detail |
| AI Hub: Command Center, Supply Chain map, Analytics, Decisions, Buying Decision Engine, Reports, AI Cost, Market Intel, Marketing | 🖥 | |
| MCP Instructions | 🖥 | |
| AI Settings (triggers, profile, Telegram bot, tool access, credits, features) | 🖥 | |

## Configuration
| Web | Mobile | Notes |
|---|---|---|
| Company Configuration, Plan & Billing, Roles & Permissions | 🖥 | roles are respected: the API hides what the user may not do |
| Connectors V1 (Walmart, ShipStation, Amazon, eBay, Target, Mirakl, Mercado Libre) | 🖥 | |
| Connectors Preview (eBay, Target Plus, Macy's, TikTok Shop, Best Buy, Temu) + Monitor | 🖥 | Monitor feeds the 💡 connector-health card |
| Dynamic Webhook, Log Entries, Exports, Import Jobs | 🖥 | |

## Across the app
| Web | Mobile | Notes |
|---|---|---|
| Global search of pages and records | ◐ | record search in every list + scan-anything ✅; page search n/a on mobile |
| Notifications | 🔜 V1.1 | push: order exceptions, label failures, low stock |
| Support chat, Tickets (Zendesk) | 💡 Later | create a ticket with a photo from the phone |
| Profile: two-factor sign-in, connected AI apps | ◐ | 2FA at sign-in ✅; profile management 🖥 |
| Light and dark themes; Classic and Modern designs | ✅ | light + dark; Modern design tokens only |
| Saved grid views, column chooser, density, filters, totals, export | ◐ | status filters + search + totals ✅; saved views / export 🖥 |

## What this says about the next cuts

1. **Before external TestFlight** — finish the four 🎨 receivables screens (invoice detail, payment receipt, pay
   single invoice, apply payments): the endpoints are live-verified and the designs exist.
2. **V1.1 (warehouse)** — kit / variant creation, print from phone, batches / pick lists, push notifications,
   on-order / on-purchase columns, backorders chip, cost-per-shipment report.
3. **V1.2 (owner)** — Brief Me card, connector health, AR / AP aging, scan-to-return, SCAN forms, announcements.
4. Everything marked 🖥 stays on the web on purpose; the app deep-links to erp.easyesuite.com where it makes sense.
