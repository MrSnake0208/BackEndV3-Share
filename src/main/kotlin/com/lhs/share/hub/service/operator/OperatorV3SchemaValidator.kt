package com.lhs.share.hub.service.operator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

@Service
class OperatorV3SchemaValidator(objectMapper: ObjectMapper) {
    private val schema = ClassPathResource("schema/operator-growth-exchange-v3.schema.json").inputStream.use {
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(objectMapper.readTree(it))
    }

    fun validate(document: JsonNode) {
        val errors = schema.validate(document)
        if (errors.isNotEmpty()) {
            val first = errors.sortedBy { it.message }.first()
            throw OperatorApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "schema_validation_failed",
                first.message,
                fieldPath = first.instanceLocation.toString().removePrefix("$.").removePrefix("/"),
            )
        }
    }
}
