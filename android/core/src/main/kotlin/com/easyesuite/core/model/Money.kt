package com.easyesuite.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * The API returns money in three shapes, sometimes on the same record:
 * `"180.00"`, `"$82,765.20"`, `166464`. This type decodes all of them.
 * Negative values may also arrive as `"($12.00)"`.
 */
@Serializable(with = MoneySerializer::class)
data class Money(val amount: Double) : Comparable<Money> {

    val isZero: Boolean get() = abs(amount) < 0.005

    /** "$1,234.56" — USD is the only currency in use on the tenants we have seen. */
    fun formatted(currencyCode: String = "USD"): String {
        val fmt = NumberFormat.getCurrencyInstance(Locale.US)
        runCatching { fmt.currency = java.util.Currency.getInstance(currencyCode) }
        return fmt.format(amount)
    }

    /** Plain decimal string the API accepts on write, e.g. "12.50". */
    fun toApiString(): String = String.format(Locale.US, "%.2f", amount)

    operator fun plus(other: Money) = Money(amount + other.amount)
    override fun compareTo(other: Money): Int = amount.compareTo(other.amount)

    companion object {
        val ZERO = Money(0.0)

        fun parse(element: JsonElement): Money? {
            if (element is JsonNull) return null
            val p = element as? JsonPrimitive ?: return null
            if (!p.isString) return p.doubleOrNull?.let(::Money)
            return parse(p.content)
        }

        fun parse(raw: String?): Money? {
            if (raw.isNullOrBlank()) return null
            var s = raw.trim()
            val negativeByParens = s.startsWith("(") && s.endsWith(")")
            s = s.removePrefix("(").removeSuffix(")")
            // Strip currency symbols / codes / grouping separators, keep digits, dot and minus.
            s = s.replace(Regex("[^0-9.\\-]"), "")
            if (s.isEmpty() || s == "-" || s == ".") return null
            val value = s.toDoubleOrNull() ?: return null
            return Money(if (negativeByParens) -value else value)
        }
    }
}

object MoneySerializer : KSerializer<Money> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Money", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Money {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return Money.parse(decoder.decodeString()) ?: Money.ZERO
        return Money.parse(jsonDecoder.decodeJsonElement()) ?: Money.ZERO
    }

    override fun serialize(encoder: Encoder, value: Money) {
        encoder.encodeString(value.toApiString())
    }
}
