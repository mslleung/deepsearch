package io.deepsearch.domain.agents.infra.llm

import com.google.genai.types.Schema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Translates Google GenAI SDK [Schema] objects into standard JSON Schema ([JsonObject]).
 *
 * The GenAI Schema is an OpenAPI 3.0 subset, so the mapping to JSON Schema is mostly direct.
 * The type values differ in casing (GenAI uses uppercase "STRING", JSON Schema uses lowercase "string").
 */
object SchemaTranslator {

    fun toJsonSchema(schema: Schema): JsonObject {
        val fields = mutableMapOf<String, JsonElement>()

        schema.type().ifPresent { type ->
            val known = type.knownEnum()
            val jsonType = if (known != null && known != com.google.genai.types.Type.Known.TYPE_UNSPECIFIED) {
                known.name.lowercase()
            } else {
                type.toString().lowercase()
            }
            fields["type"] = JsonPrimitive(jsonType)
        }

        schema.description().ifPresent { fields["description"] = JsonPrimitive(it) }
        schema.title().ifPresent { fields["title"] = JsonPrimitive(it) }
        schema.format().ifPresent { fields["format"] = JsonPrimitive(it) }
        schema.pattern().ifPresent { fields["pattern"] = JsonPrimitive(it) }

        schema.properties().ifPresent { props ->
            fields["properties"] = JsonObject(props.mapValues { (_, v) -> toJsonSchema(v) })
        }

        schema.required().ifPresent { req ->
            fields["required"] = JsonArray(req.map { JsonPrimitive(it) })
        }

        schema.items().ifPresent { items ->
            fields["items"] = toJsonSchema(items)
        }

        schema.enum_().ifPresent { values ->
            fields["enum"] = JsonArray(values.map { JsonPrimitive(it) })
        }

        schema.anyOf().ifPresent { schemas ->
            fields["anyOf"] = JsonArray(schemas.map { toJsonSchema(it) })
        }

        schema.nullable().ifPresent { fields["nullable"] = JsonPrimitive(it) }
        schema.minimum().ifPresent { fields["minimum"] = JsonPrimitive(it) }
        schema.maximum().ifPresent { fields["maximum"] = JsonPrimitive(it) }
        schema.minItems().ifPresent { fields["minItems"] = JsonPrimitive(it) }
        schema.maxItems().ifPresent { fields["maxItems"] = JsonPrimitive(it) }
        schema.minLength().ifPresent { fields["minLength"] = JsonPrimitive(it) }
        schema.maxLength().ifPresent { fields["maxLength"] = JsonPrimitive(it) }

        return JsonObject(fields)
    }
}
