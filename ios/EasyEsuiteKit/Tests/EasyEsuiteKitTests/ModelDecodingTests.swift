import XCTest
@testable import EasyEsuiteKit

final class ModelDecodingTests: XCTestCase {
    private let decoder: JSONDecoder = { let d = JSONDecoder(); d.keyDecodingStrategy = .convertFromSnakeCase; return d }()

    private func fixture(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
        return try Data(contentsOf: url)
    }

    func testMoneyParsesEveryShape() throws {
        XCTAssertEqual(try XCTUnwrap(Money.parse("180.00")).doubleValue, 180, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(Money.parse("$82,765.20")).doubleValue, 82765.20, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(Money.parse("($12.00)")).doubleValue, -12, accuracy: 1e-9)
        XCTAssertNil(Money.parse(""))
        XCTAssertNil(Money.parse("n/a"))
        XCTAssertEqual(Money(1234.56).formatted(), "$1,234.56")
        XCTAssertEqual(Money(12.5).apiString, "12.50")
        struct Wrap: Decodable { let a: Money; let b: Money; let c: Money? }
        let w = try decoder.decode(Wrap.self, from: Data(#"{"a": 166464, "b": "$1,000.50", "c": null}"#.utf8))
        XCTAssertEqual(w.a.doubleValue, 166464, accuracy: 1e-9)
        XCTAssertEqual(w.b.doubleValue, 1000.5, accuracy: 1e-9)
        XCTAssertNil(w.c)
    }

    func testCatalogPage() throws {
        let page = try decoder.decode(Page<ItemSummary>.self, from: fixture("items_list"))
        XCTAssertEqual(page.count, 6441)
        XCTAssertTrue(page.hasMore)
        let item = try XCTUnwrap(page.results.first)
        XCTAssertEqual(item.id, 6873)
        XCTAssertEqual(item.name, "Pokemon-TCG-First-Partner-Series-1")
        XCTAssertEqual(item.upcCode, "196214150522")
        XCTAssertEqual(item.onHand, 0)
        XCTAssertEqual(item.averageCost?.doubleValue, 0)
        XCTAssertEqual(item.primaryImage, "https://go-upc.s3.amazonaws.com/images/474131544.png")
    }

    func testItemDetail() throws {
        let item = try decoder.decode(ItemDetail.self, from: fixture("item_detail"))
        XCTAssertEqual(item.id, 6690)
        XCTAssertEqual(item.onHand, 224)
        XCTAssertEqual(item.averageCost?.doubleValue, 140)
        XCTAssertEqual(item.totalValue?.doubleValue, 31360)
        XCTAssertEqual(item.marketplacePricing?.count, 2)
        XCTAssertEqual(item.marketplacePricing?.first?.price?.doubleValue, 180)
        XCTAssertEqual(item.marketplaceSkus.map { $0.0 }, ["eBay", "Target", "Best Buy", "Macy's"])
        XCTAssertEqual(item.dimensionsText, "7.50 × 6.50 × 3.50 Inches")
        XCTAssertEqual(item.allImages.count, 3)
        XCTAssertEqual(item.itemConditionName, "New")
    }

    func testInventoryItems() throws {
        let page = try decoder.decode(Page<InventoryItem>.self, from: fixture("inventory_items"))
        let row = try XCTUnwrap(page.results.first)
        XCTAssertEqual(page.count, 1362)
        XCTAssertEqual(row.available, 1)
        XCTAssertEqual(row.reorderPoint, 10)
        XCTAssertTrue(row.belowReorderPoint)
        XCTAssertEqual(row.averageCost?.doubleValue, 5)
    }

    func testWarehouseStock() throws {
        let page = try decoder.decode(Page<WarehouseStock>.self, from: fixture("warehouse_stock"))
        let sv = try XCTUnwrap(page.results.first { $0.warehouse == "Sunvalley Wearhouse" })
        XCTAssertEqual(sv.onHand, 224)
        XCTAssertEqual(sv.available, 219)
        XCTAssertEqual(sv.committed, 5)
        XCTAssertEqual(sv.reorderPoint, 20)
        XCTAssertEqual(sv.totalValNew?.doubleValue, 31360)
        XCTAssertEqual(sv.inventoryItemId, 6690)
    }

    func testUpcLookupAndWarehouses() throws {
        let res = try decoder.decode(UpcLookupResponse.self, from: fixture("upc_lookup"))
        XCTAssertEqual(res.data?.upc, "196214152045")
        XCTAssertEqual(res.data?.images?.count, 2)
        let wh = try decoder.decode(Page<Warehouse>.self, from: fixture("warehouses"))
        XCTAssertEqual(wh.results.count, 3)
        XCTAssertTrue(try XCTUnwrap(wh.results.first { $0.id == 9 }).default)
    }

    func testSalesOrderList() throws {
        let page = try decoder.decode(Page<SalesOrderSummary>.self, from: fixture("sales_orders_list"))
        let so = try XCTUnwrap(page.results.first)
        XCTAssertEqual(page.count, 226956)
        XCTAssertEqual(so.number, "SO-497408")
        XCTAssertEqual(so.marketplace, "Target")
        XCTAssertEqual(so.amount.doubleValue, 180)
    }

    func testSalesOrderDetail() throws {
        let so = try decoder.decode(SalesOrderDetail.self, from: fixture("sales_order_detail"))
        XCTAssertEqual(so.marketplace, 5)
        XCTAssertEqual(so.marketplaceName, "Target")
        XCTAssertEqual(so.lines.count, 1)
        let line = try XCTUnwrap(so.lines.first)
        XCTAssertEqual(line.qty, 1)
        XCTAssertEqual(line.unfulfilled, 1)
        XCTAssertEqual(line.lineTotal.doubleValue, 180)
        XCTAssertTrue(so.canFulfill)
        XCTAssertEqual(so.shipTo?.lines, ["Jane Buyer", "TARGET", "100 Example St", "Springfield CA 90000", "US"])
    }

    func testShipments() throws {
        let page = try decoder.decode(Page<Shipment>.self, from: fixture("shipments_list"))
        let s = try XCTUnwrap(page.results.first)
        XCTAssertEqual(s.id, "2286")
        XCTAssertEqual(s.status, "to_ship")
        XCTAssertEqual(s.orderNumber, "SO-497408")
        XCTAssertEqual(s.salesOrderId, "1777461")
        XCTAssertEqual(s.lines.first?.itemId, "TCG-Destined-ETB")
        XCTAssertEqual(s.orderTotal?.doubleValue, 180)
        XCTAssertNil(s.shipmentCost)
        XCTAssertTrue(s.canBuyLabel)
        XCTAssertEqual(s.destination, "Jane Buyer, CA")
        // Numeric id variant (detail endpoint) still decodes.
        let numeric = try decoder.decode(Shipment.self, from: Data(#"{"id": 42, "status": "shipped", "sales_order_id": 7}"#.utf8))
        XCTAssertEqual(numeric.id, "42")
        XCTAssertEqual(numeric.salesOrderId, "7")
        let missing = try decoder.decode(Shipment.self, from: Data(#"{"id": "9"}"#.utf8))
        XCTAssertNil(missing.salesOrderId)
    }

    func testInvoiceAnalytics() throws {
        let page = try decoder.decode(Page<SalesByItemRow>.self, from: fixture("invoice_analytics"))
        let top = try XCTUnwrap(page.results.first)
        XCTAssertEqual(top.revenue.doubleValue, 166464)
        XCTAssertEqual(top.units, 288)
        XCTAssertEqual(top.profit?.doubleValue ?? 0, 745.92, accuracy: 1e-6)
        XCTAssertEqual(try XCTUnwrap(page.results[1].marginPercent), 18.37, accuracy: 0.01)
    }

    func testPurchaseOrdersAndTransfers() throws {
        let pos = try decoder.decode(Page<PurchaseOrder>.self, from: fixture("purchase_orders"))
        let po = try XCTUnwrap(pos.results.first)
        XCTAssertEqual(po.number, "PO-003717")
        XCTAssertEqual(po.totalAmountNew?.doubleValue ?? 0, 82765.20, accuracy: 1e-6)
        XCTAssertEqual(po.totalQuantityOrdered, 43200)
        XCTAssertTrue(po.canReceive)
        let tr = try decoder.decode(Page<Transfer>.self, from: fixture("transfers"))
        let t = try XCTUnwrap(tr.results.first)
        XCTAssertEqual(t.number, "TR-003324")
        XCTAssertEqual(t.status, "Completed")
        XCTAssertFalse(t.canComplete)
    }

    func testUserProfile() throws {
        let me = try decoder.decode(UserProfile.self, from: fixture("users_me"))
        XCTAssertEqual(me.displayName, "Test Test")
        XCTAssertTrue(me.isAdmin)
    }

    func testCreateItemEncoding() throws {
        var req = CreateItemRequest(name: "TCG-Destined-ETB")
        req.upcCode = "196214152045"
        req.purchasePrice = "140.00"
        req.itemImages = [ItemImage(imageUrl: "https://example.com/a.png")]
        let enc = JSONEncoder(); enc.keyEncodingStrategy = .convertToSnakeCase
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: enc.encode(req)) as? [String: Any])
        XCTAssertEqual(obj["name"] as? String, "TCG-Destined-ETB")
        XCTAssertEqual(obj["upc_code"] as? String, "196214152045")
        XCTAssertEqual(obj["purchase_price"] as? String, "140.00")
        XCTAssertEqual(obj["item_type"] as? String, "INV")
        XCTAssertEqual(obj["costing_method"] as? String, "AVG")
        XCTAssertEqual(obj["weight_unit"] as? String, "Pounds")
        XCTAssertNil(obj["description"])
        XCTAssertEqual(((obj["item_images"] as? [[String: Any]])?.first?["image_url"]) as? String, "https://example.com/a.png")
    }

    func testAssistantParsing() throws {
        let a = AssistantRepository.parse(try decoder.decode(JSONValue.self, from: Data(#"{"answer":"You have 12 open orders.","thread_id":"t-9","suggestions":["Show them","Ship them"]}"#.utf8)), previousThread: nil)
        XCTAssertEqual(a.text, "You have 12 open orders.")
        XCTAssertEqual(a.threadId, "t-9")
        XCTAssertEqual(a.suggestions.count, 2)
        let b = AssistantRepository.parse(try decoder.decode(JSONValue.self, from: Data(#"{"data":{"response":"hi"}}"#.utf8)), previousThread: "t-1")
        XCTAssertEqual(b.text, "hi")
        XCTAssertEqual(b.threadId, "t-1")
    }

    func testDateRangeBoundsInUsersZone() throws {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        let day = cal.date(from: DateComponents(year: 2026, month: 10, day: 3))!
        let q = DateRange(start: day, end: day, label: "day").query(calendar: cal)
        XCTAssertEqual(q["date_after"] as? String, "2026-10-03T07:00:00Z")
        XCTAssertEqual(q["date_before"] as? String, "2026-10-04T06:59:59Z")
        XCTAssertNotNil(DateText.parse("2026-10-03T19:54:02.861763Z"))
        XCTAssertNotNil(DateText.parse("2026-09-03 16:14:32.325946+00:00"))
        XCTAssertNotNil(DateText.parse("2026-09-24"))
    }
}
