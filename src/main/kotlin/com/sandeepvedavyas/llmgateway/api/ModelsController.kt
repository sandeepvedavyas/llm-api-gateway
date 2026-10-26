package com.sandeepvedavyas.llmgateway.api

import com.sandeepvedavyas.llmgateway.config.GatewayProperties
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
@Tag(name = "Models")
class ModelsController(
    private val properties: GatewayProperties,
) {
    @GetMapping("/models")
    @Operation(summary = "Models with a configured price")
    fun models(): ModelListResponse {
        val data = properties.pricing.models.keys.sorted().map { id ->
            ModelCard(id = id, ownedBy = "upstream")
        }
        return ModelListResponse(data = data)
    }
}

data class ModelListResponse(
    val `object`: String = "list",
    val data: List<ModelCard>,
)

data class ModelCard(
    val id: String,
    val `object`: String = "model",
    val ownedBy: String,
)
