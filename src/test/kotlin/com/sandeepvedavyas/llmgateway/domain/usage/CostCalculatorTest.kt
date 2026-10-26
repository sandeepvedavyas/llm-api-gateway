package com.sandeepvedavyas.llmgateway.domain.usage

import com.sandeepvedavyas.llmgateway.config.GatewayProperties
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CostCalculatorTest {
    private val calculator = CostCalculator(properties())

    @Test
    fun `prices a known model from the configured table`() {
        val cost = calculator.estimate("gpt-4o-mini", promptTokens = 1_000, completionTokens = 2_000)

        assertThat(cost).isEqualByComparingTo(BigDecimal("0.00135000"))
    }

    @Test
    fun `uses the longest configured prefix for dated model snapshots`() {
        val snapshot = calculator.priceFor("gpt-4o-mini-2024-07-18")
        val exactMini = calculator.priceFor("gpt-4o-mini")
        val larger = calculator.priceFor("gpt-4o")

        assertThat(snapshot).isEqualTo(exactMini)
        assertThat(snapshot.inputPerMillion).isEqualByComparingTo("0.15")
        assertThat(larger.inputPerMillion).isEqualByComparingTo("2.50")
    }

    @Test
    fun `falls back to the default price for an unknown model`() {
        val price = calculator.priceFor("some-local-model")
        val cost = calculator.estimate("some-local-model", promptTokens = 1_000_000, completionTokens = 0)

        assertThat(price.inputPerMillion).isEqualByComparingTo("1.00")
        assertThat(price.outputPerMillion).isEqualByComparingTo("3.00")
        assertThat(cost).isEqualByComparingTo("1.00000000")
    }

    @Test
    fun `zero tokens cost nothing`() {
        assertThat(calculator.estimate("gpt-4o", 0, 0)).isEqualByComparingTo("0.00000000")
    }

    private fun properties(): GatewayProperties {
        val properties = GatewayProperties()
        properties.pricing.defaultInputPerMillion = BigDecimal("1.00")
        properties.pricing.defaultOutputPerMillion = BigDecimal("3.00")
        properties.pricing.models = mapOf(
            "gpt-4o" to price("2.50", "10.00"),
            "gpt-4o-mini" to price("0.15", "0.60"),
        )
        return properties
    }

    private fun price(input: String, output: String): GatewayProperties.ModelPrice {
        return GatewayProperties.ModelPrice().apply {
            inputPerMillion = BigDecimal(input)
            outputPerMillion = BigDecimal(output)
        }
    }
}
