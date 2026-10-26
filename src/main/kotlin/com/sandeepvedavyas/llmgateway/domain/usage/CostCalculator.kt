package com.sandeepvedavyas.llmgateway.domain.usage

import com.sandeepvedavyas.llmgateway.config.GatewayProperties
import java.math.BigDecimal
import java.math.RoundingMode
import org.springframework.stereotype.Component

data class ModelPrice(
    val inputPerMillion: BigDecimal,
    val outputPerMillion: BigDecimal,
)

@Component
class CostCalculator(properties: GatewayProperties) {
    private val defaultPrice = ModelPrice(
        inputPerMillion = properties.pricing.defaultInputPerMillion,
        outputPerMillion = properties.pricing.defaultOutputPerMillion,
    )
    private val models: Map<String, ModelPrice> = properties.pricing.models.mapValues { (_, price) ->
        ModelPrice(price.inputPerMillion, price.outputPerMillion)
    }

    fun priceFor(model: String): ModelPrice {
        models[model]?.let { return it }
        val prefix = models.keys
            .filter { candidate -> model.startsWith(candidate) }
            .maxByOrNull { it.length }
        return if (prefix == null) defaultPrice else models.getValue(prefix)
    }

    fun estimate(model: String, promptTokens: Long, completionTokens: Long): BigDecimal {
        val price = priceFor(model)
        val input = tokensCost(promptTokens, price.inputPerMillion)
        val output = tokensCost(completionTokens, price.outputPerMillion)
        return input.add(output).setScale(SCALE, RoundingMode.HALF_UP)
    }

    private fun tokensCost(tokens: Long, usdPerMillion: BigDecimal): BigDecimal {
        if (tokens <= 0 || usdPerMillion.signum() == 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP)
        }
        return BigDecimal.valueOf(tokens)
            .multiply(usdPerMillion)
            .divide(MILLION, SCALE, RoundingMode.HALF_UP)
    }

    companion object {
        const val SCALE = 8
        private val MILLION = BigDecimal("1000000")

        fun formatUsd(amount: BigDecimal): String =
            amount.setScale(SCALE, RoundingMode.HALF_UP).toPlainString()
    }
}
