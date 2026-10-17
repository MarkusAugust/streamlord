package io.github.markusaugust.streamlord.json.jackson2

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.AnnotationIntrospector
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException
import com.fasterxml.jackson.databind.introspect.Annotated
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector
import io.github.markusaugust.streamlord.core.SignalsCodecException
import io.github.markusaugust.streamlord.core.SignalsDecodeException
import io.github.markusaugust.streamlord.core.port.driven.SignalsCodec
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.full.valueParameters
import kotlin.reflect.javaType
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.kotlinFunction

/**
 * A [SignalsCodec] backed by Jackson 2 (`com.fasterxml.jackson`).
 *
 * Hand it the `ObjectMapper` your application already owns so signals obey the same rules as
 * every other JSON in the realm. [default] builds a mapper with every module on the classpath
 * registered, including `jackson-module-kotlin` when present.
 *
 * Five rules hold whatever the mapper says, because the type is what a request may send. A
 * `null` for a primitive, such as `val id: Long`, is refused rather than read as `0`, which is
 * what Jackson 2 and Spring Boot's mapper do by default; this holds when the parameter has a
 * default and when the mapper has the Kotlin module's `NullIsSameAsDefault` on, so declare it
 * `Int?` if the page may send `null`. A `null` inside a Kotlin parameter's `List<Int>`,
 * `Set<Item>`, `Array<String>` or `Map<String, Int>` is refused too, as the Kotlin module's
 * `StrictNullChecks` refuses it, and so is one inside a `List<Int>` or `Map<String, List<Int>>`
 * read whole, at any depth; declare the element `Int?` if it may be `null`. A Java class's
 * collections keep taking `null`. A Kotlin
 * parameter must be sent unless it has a default or, unlike with kotlinx, takes `null`; a Java
 * creator keeps the mapper's rule. A key the type does not name is ignored, as the other codecs
 * ignore it, where Jackson 2's own default refuses it. And nothing may follow the JSON value.
 *
 * The codec reads through a copy of the mapper, made once, so the mapper you hand it is never
 * changed. A mapper of a subclass that does not override `copy()` cannot be used.
 *
 * A Kotlin class or interface in the type needs `jackson-module-kotlin` on the mapper, even one
 * Jackson could build without it, such as a class with `var` properties. Without the module
 * Jackson reads a Kotlin class past its constructor, puts `null` into a property that cannot hold one and refuses a class
 * without defaults, so decoding throws
 * [SignalsCodecException] instead.
 *
 * ```kotlin
 * @Bean fun streamlord(mapper: ObjectMapper) = Streamlord(codec = Jackson2SignalsCodec(mapper))
 * ```
 */
public class Jackson2SignalsCodec(
    mapper: ObjectMapper,
) : SignalsCodec {
    /*
     * A copy, so the caller's mapper keeps its own rules and its own cache of deserializers: one
     * built with the rule below would otherwise be cached for every other read it makes.
     */
    private val mapper: ObjectMapper =
        mapper.copy().also { copy ->
            if (hasKotlinModule(copy)) {
                copy.setAnnotationIntrospectors(
                    copy.serializationConfig.annotationIntrospector,
                    AnnotationIntrospector.pair(copy.deserializationConfig.annotationIntrospector, ContentNulls),
                )
            }
        }

    override fun encode(
        value: Any?,
        type: KType,
    ): String =
        try {
            mapper.writeValueAsString(value)
        } catch (e: JsonProcessingException) {
            throw SignalsCodecException("Could not encode $type as signals", e)
        }

    @OptIn(ExperimentalStdlibApi::class)
    override fun <T : Any> decode(
        json: String,
        type: KType,
    ): T =
        try {
            val kotlin = kotlinClassIn(type)
            if (kotlin != null && !hasKotlinModule(mapper)) throw noKotlinModule(type, kotlin)
            val javaType = mapper.typeFactory.constructType(type.javaType)
            mapper
                .readerFor(javaType)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // A Kotlin parameter with no default must be sent, as kotlinx has it; the Kotlin module
                // still lets one that takes null go missing. A Java creator keeps the mapper's rule.
                .let { if (kotlin != null) it.with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES) else it }
                .readValue<T>(json)
                ?.also { refuseNullValues(it, type) }
                ?: throw SignalsDecodeException("Signals decoded to null for $type")
        } catch (e: InvalidDefinitionException) {
            // The type itself cannot be built, whatever the request sends: the server's to fix.
            throw SignalsCodecException("Could not decode signals into $type", e)
        } catch (e: JsonProcessingException) {
            throw SignalsDecodeException("Could not decode signals into $type", e)
        }

    public companion object {
        private const val KOTLIN_MODULE = "com.fasterxml.jackson.module.kotlin.KotlinModule"

        /*
         * The module id is recorded only while IGNORE_DUPLICATE_MODULE_REGISTRATIONS is on, its
         * default; the introspector it adds is there either way.
         */
        private fun hasKotlinModule(mapper: ObjectMapper): Boolean =
            KOTLIN_MODULE in mapper.registeredModuleIds ||
                mapper.deserializationConfig.annotationIntrospector
                    .allIntrospectors()
                    .any { it.javaClass.name.startsWith("com.fasterxml.jackson.module.kotlin.") }

        /*
         * The Kotlin module checks what a constructor holds, not the type asked for, so a
         * List<Int> or Map<String, List<Int>> read whole is checked here, through every list,
         * set, array and map it nests.
         */
        private fun refuseNullValues(
            value: Any?,
            type: KType,
            whole: KType = type,
        ) {
            val (items, item) =
                when (value) {
                    is Map<*, *> -> value.values to type.arguments.getOrNull(1)?.type
                    is Collection<*> -> value to type.arguments.getOrNull(0)?.type
                    is Array<*> -> value.asList() to type.arguments.getOrNull(0)?.type
                    else -> return
                }
            if (item == null) return
            for (each in items) {
                if (each == null && !item.isMarkedNullable) {
                    throw SignalsDecodeException("Signals held null where $whole takes none")
                }
                refuseNullValues(each, item, whole)
            }
        }

        /** The first Kotlin class or interface in [type] or its arguments; an enum Jackson reads by name either way. */
        private fun kotlinClassIn(type: KType): KClass<*>? {
            val klass = type.classifier as? KClass<*>
            val java = klass?.java
            if (java != null && java.isAnnotationPresent(Metadata::class.java) && !java.isEnum) return klass
            return type.arguments.firstNotNullOfOrNull { argument -> argument.type?.let { kotlinClassIn(it) } }
        }

        private fun noKotlinModule(
            type: KType,
            klass: KClass<*>,
        ) = SignalsCodecException(
            "Could not decode signals into $type: ${klass.qualifiedName} is a Kotlin class and the mapper has no " +
                "jackson-module-kotlin, without which Jackson reads it past its constructor",
        )

        /** A codec over a fresh mapper with every module on the classpath registered. */
        public fun default(): Jackson2SignalsCodec = Jackson2SignalsCodec(ObjectMapper().findAndRegisterModules())
    }
}

/*
 * The Kotlin module's StrictNullChecks, for a mapper that has it off, as it is by default in
 * Jackson 2: a Kotlin creator parameter whose collection, array or map holds a type that cannot
 * be null fails on a null in it, the way @JsonSetter(contentNulls = FAIL) would. It runs after
 * the mapper's own introspectors, so an annotation on the parameter still decides. Installed only
 * beside the Kotlin module, which brings kotlin-reflect with it.
 */
private object ContentNulls : NopAnnotationIntrospector() {
    private fun readResolve(): Any = ContentNulls

    override fun findSetterInfo(ann: Annotated): JsonSetter.Value {
        val parameter = ann as? AnnotatedParameter ?: return JsonSetter.Value.empty()
        val content =
            when {
                parameter.type.isArrayType || parameter.type.isCollectionLikeType -> 0
                parameter.type.isMapLikeType -> 1
                else -> return JsonSetter.Value.empty()
            }
        val owner = parameter.owner.member
        // A Java parameter's platform type is not marked nullable, yet takes null.
        if (!owner.declaringClass.isAnnotationPresent(Metadata::class.java)) return JsonSetter.Value.empty()
        val function =
            when (owner) {
                is Constructor<*> -> owner.kotlinFunction ?: owner.behindSynthetic()
                is Method -> owner.kotlinFunction
                else -> null
            }
        val nonNull =
            function
                ?.valueParameters
                ?.getOrNull(parameter.index)
                ?.type
                ?.arguments
                ?.getOrNull(content)
                ?.type
                ?.isMarkedNullable == false
        return if (nonNull) JsonSetter.Value.forContentNulls(Nulls.FAIL) else JsonSetter.Value.empty()
    }
}

/*
 * A constructor that takes a value class is private, and Jackson reads it, while kotlin-reflect
 * knows it only as the synthetic one beside it, which takes a DefaultConstructorMarker last.
 */
private fun Constructor<*>.behindSynthetic(): KFunction<*>? =
    declaringClass.kotlin.constructors.firstOrNull { constructor ->
        val types = constructor.javaConstructor?.parameterTypes ?: return@firstOrNull false
        types.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker" &&
            types.copyOfRange(0, types.size - 1).contentEquals(parameterTypes)
    }
