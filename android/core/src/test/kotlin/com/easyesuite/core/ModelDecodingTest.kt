package com.easyesuite.core

import com.easyesuite.core.model.InventoryItem
import com.easyesuite.core.model.ItemDetail
import com.easyesuite.core.model.ItemSummary
import com.easyesuite.core.model.Money
import com.easyesuite.core.model.Page
import com.easyesuite.core.model.PurchaseOrder
import com.easyesuite.core.model.SalesByItemRow
import com.easyesuite.core.model.SalesOrderDetail
import com.easyesuite.core.model.SalesOrderSummary
import com.easyesuite.core.model.Shipment
import com.easyesuite.core.model.Transfer
import com.easyesuite.core.model.UpcLookupResponse
import com.easyesuite.core.model.UserProfile
import com.easyesuite.core.model.Warehouse
import com.easyesuite.core.model.WarehouseStock
import com.easyesuite.core.net.DefaultJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDecodingTest {
    private val json: Json = DefaultJson

    @Test fun `money parses every shape the API uses`() {
        assertEquals(180.0, Money.parse("180.00")!!.amount, 1e-9)
        assertEquals(82765.20, Money.parse("$82,765.20")!!.amount, 1e-9)
        assertEquals(166464.0, Money.parse(JsonPrimitive(166464))!!.amount, 1e-9)
        assertEquals(745.92, Money.parse(JsonPrimitive(745.92))!!.amount, 1e-9)
        assertEquals(-12.0, Money.parse("($12.00)")!!.amount, 1e-9)
        assertEquals(-3.5, Money.parse("-3.50")!!.amount, 1e-9)
        assertNull(Money.parse(""))
        assertNull(Money.parse("n/a"))
        assertEquals("$1,234.56", Money(1234.56).formatted())
        assertEquals("12.50", Money(12.5).toApiString())
    }

    @Test fun `catalog page decodes`() {
        val page = json.decodeFromString<Page<ItemSummary>>(Fixtures.read("items_list.json"))
        assertEquals(6441, page.count)
        assertTrue(page.hasMore)
        val item = page.results.single()
        assertEquals(6873L, item.id)
        assertEquals("Pokemon-TCG-First-Partner-Series-1", item.name)
        assertEquals("196214150522", item.upcCode)
        assertEquals(0, item.onHand)
        assertEquals(0.0, item.averageCost!!.amount, 1e-9)
        assertEquals("https://go-upc.s3.amazonaws.com/images/474131544.png", item.primaryImage)
        assertTrue(item.displayTitle.startsWith("Pokemon TCG"))
    }

    @Test fun `item detail decodes with pricing, skus and dimensions`() {
        val item = json.decodeFromString<ItemDetail>(Fixtures.read("item_detail.json"))
        assertEquals(6690L, item.id)
        assertEquals(224, item.onHand)
        assertEquals(140.0, item.averageCost!!.amount, 1e-9)
        assertEquals(31360.0, item.totalValue!!.amount, 1e-9)
        assertEquals(2, item.marketplacePricing.size)
        assertEquals(180.0, item.marketplacePricing.first().price!!.amount, 1e-9)
        assertEquals(5, item.marketplacePricing.first().marketplace)
        assertEquals(listOf("eBay", "Target", "Best Buy", "Macy's"), item.marketplaceSkus.map { it.first })
        assertEquals("7.50 × 6.50 × 3.50 Inches", item.dimensionsText)
        assertEquals(3, item.allImages.size)  // 2 gallery + image1
        assertEquals("New", item.conditionName)
    }

    @Test fun `inventory items decode`() {
        val page = json.decodeFromString<Page<InventoryItem>>(Fixtures.read("inventory_items.json"))
        val row = page.results.single()
        assertEquals(1362, page.count)
        assertEquals(1, row.available)
        assertEquals(1, row.onHand)
        assertEquals(10, row.reorderPoint)
        assertTrue(row.belowReorderPoint)
        assertEquals(5.0, row.averageCost!!.amount, 1e-9)
        assertEquals(0.0, row.sellingPrice!!.amount, 1e-9)
    }

    @Test fun `warehouse stock rows decode`() {
        val page = json.decodeFromString<Page<WarehouseStock>>(Fixtures.read("warehouse_stock.json"))
        assertEquals(2, page.results.size)
        val sunvalley = page.results.first { it.warehouse == "Sunvalley Wearhouse" }
        assertEquals(224, sunvalley.onHand)
        assertEquals(219, sunvalley.available)
        assertEquals(5, sunvalley.committed)
        assertEquals(20, sunvalley.reorderPoint)
        assertEquals(300, sunvalley.preferredStockLevel)
        assertEquals(31360.0, sunvalley.totalValue!!.amount, 1e-9)
        assertEquals(180.0, sunvalley.sellingPrice!!.amount, 1e-9)
        assertEquals(6690L, sunvalley.inventoryItemId)
    }

    @Test fun `upc lookup decodes`() {
        val res = json.decodeFromString<UpcLookupResponse>(Fixtures.read("upc_lookup.json"))
        val p = res.data!!
        assertEquals("196214152045", p.upc)
        assertTrue(p.title!!.contains("Destined Rivals"))
        assertEquals(2, p.images.size)
        assertEquals("Multicolor", p.color)
    }

    @Test fun `warehouses decode`() {
        val page = json.decodeFromString<Page<Warehouse>>(Fixtures.read("warehouses.json"))
        assertEquals(3, page.results.size)
        assertTrue(page.results.first { it.id == 9 }.isDefault)
        assertFalse(page.results.first { it.id == 8 }.isDefault)
    }

    @Test fun `sales order list decodes with marketplace name`() {
        val page = json.decodeFromString<Page<SalesOrderSummary>>(Fixtures.read("sales_orders_list.json"))
        val so = page.results.single()
        assertEquals(226956, page.count)
        assertEquals("SO-497408", so.number)
        assertEquals("Target", so.marketplace)
        assertEquals("Pending Fulfillment", so.status)
        assertEquals(180.0, so.amount.amount, 1e-9)
        assertEquals("TEMP NW", so.warehouse)
    }

    @Test fun `sales order detail decodes lines and ship-to`() {
        val so = json.decodeFromString<SalesOrderDetail>(Fixtures.read("sales_order_detail.json"))
        assertEquals(1777461L, so.id)
        assertEquals(5, so.marketplace)
        assertEquals("Target", so.marketplaceName)
        assertEquals(1, so.items.size)
        val line = so.items.single()
        assertEquals("TCG-Destined-ETB", line.name)
        assertEquals(1, line.quantity)
        assertEquals(1, line.unfulfilledQuantity)
        assertEquals(180.0, line.price!!.amount, 1e-9)
        assertEquals(180.0, line.lineTotal.amount, 1e-9)
        assertEquals(6690L, line.item)
        assertTrue(so.canFulfill)
        val ship = so.shipTo!!
        assertEquals("Jane Buyer", ship.contactName)
        assertEquals(listOf("Jane Buyer", "TARGET", "100 Example St", "Springfield CA 90000", "US"), ship.lines)
    }

    @Test fun `shipments decode with string ids and nested items`() {
        val page = json.decodeFromString<Page<Shipment>>(Fixtures.read("shipments_list.json"))
        val s = page.results.single()
        assertEquals("2286", s.id)
        assertEquals("to_ship", s.status)
        assertEquals("SO-497408", s.orderNumber)
        assertEquals("1777461", s.salesOrderId)
        assertEquals(1, s.items.size)
        assertEquals("TCG-Destined-ETB", s.items.single().sku)
        assertEquals(180.0, s.orderTotal!!.amount, 1e-9)
        assertNull(s.shipmentCost)
        assertTrue(s.canBuyLabel)
        assertFalse(s.hasLabel)
        assertEquals("Jane Buyer, CA", s.destination)
    }

    @Test fun `invoice analytics decodes numeric money`() {
        val page = json.decodeFromString<Page<SalesByItemRow>>(Fixtures.read("invoice_analytics.json"))
        val top = page.results.first()
        assertEquals(166464.0, top.revenue.amount, 1e-9)
        assertEquals(288, top.units)
        assertEquals(745.92, top.profit!!.amount, 1e-9)
        assertNull(top.marketplace)
        val second = page.results[1]
        assertNotNull(second.marginPercent)
        assertEquals(18.37, second.marginPercent!!, 0.01)
    }

    @Test fun `purchase orders decode`() {
        val page = json.decodeFromString<Page<PurchaseOrder>>(Fixtures.read("purchase_orders.json"))
        val po = page.results.single()
        assertEquals("PO-003717", po.number)
        assertEquals(82765.20, po.totalAmount!!.amount, 1e-9)
        assertEquals(43200, po.totalQuantityOrdered)
        assertEquals(3, po.items.size)
        assertTrue(po.canReceive)
    }

    @Test fun `transfers decode`() {
        val page = json.decodeFromString<Page<Transfer>>(Fixtures.read("transfers.json"))
        val t = page.results.single()
        assertEquals("TR-003324", t.number)
        assertEquals("TEMP NW", t.fromWarehouse)
        assertEquals("Completed", t.status)
        assertEquals(2, t.totalQuantity)
        assertFalse(t.canComplete)
    }

    @Test fun `user profile decodes`() {
        val me = json.decodeFromString<UserProfile>(Fixtures.read("users_me.json"))
        assertEquals("Test Test", me.displayName)
        assertTrue(me.isAdmin)
        assertEquals("user@example.com", me.email)
    }
}
