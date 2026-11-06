package com.youloft.linkup.net

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * Retrofit validates a `@Body` parameter's generic type at call time and refuses anything containing a
 * type variable or a wildcard:
 *
 * > Parameter type must not include a type variable or wildcard: java.util.Map<java.lang.String, ?>
 *
 * Kotlin produces exactly that signature when the read-only `Map<String, Any?>` is used, because
 * `kotlin.collections.Map` declares its value parameter covariantly (`out V`) — the nullability is not
 * the problem, the variance is. The shipped interface compiles to `java.util.Map<String, Object>`
 * (`_raw/src166/sources/gh/a.java:293`), i.e. the original declared an **invariant** `MutableMap`.
 *
 * This test exists because nothing else in the JVM suite would ever catch it: Robolectric screens drive
 * fake repositories, so the real Retrofit method is never invoked. The first time the rebuilt client met
 * the rebuilt gateway, every one of these routes threw.
 */
class BodyParameterTypesTest {

    private fun violations(): List<String> {
        val bad = mutableListOf<String>()
        for (method in LinkUpApi::class.java.declaredMethods) {
            val bodyIndex = retrofitBodyIndex(method) ?: continue
            val generic = method.genericParameterTypes[bodyIndex]
            val offender = firstWildcard(generic)
            if (offender != null) {
                bad += "${method.name}: $offender"
            }
        }
        return bad
    }

    private fun retrofitBodyIndex(method: Method): Int? {
        for (i in method.parameterAnnotations.indices) {
            if (method.parameterAnnotations[i].any { it.annotationClass.simpleName == "Body" }) return i
        }
        return null
    }

    private fun firstWildcard(type: Type): String? {
        if (type is WildcardType) return type.toString()
        if (type is ParameterizedType) {
            for (argument in type.actualTypeArguments) {
                firstWildcard(argument)?.let { return it }
            }
        }
        return null
    }

    @Test
    fun noBodyParameterCarriesAWildcard() {
        val bad = violations()
        assertTrue(
            "这些 @Body 参数会被 Retrofit 拒绝（调用即抛 IllegalArgumentException）：\n" +
                bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    @Test
    fun mapBodiesCompileToTheOriginalsInvariantSignature() {
        // The shipped interface's body parameter is java.util.Map<String, Object>; anything that reads
        // as `Map<String, ?>` in the class file is a rebuild regression, not a cosmetic choice.
        val signature = LinkUpApi::class.java.declaredMethods
            .first { it.name == "smsClickToLogin" }
            .genericParameterTypes[0]
            .toString()
        assertTrue("smsClickToLogin 的 body 签名变成 $signature 了", signature == "java.util.Map<java.lang.String, java.lang.Object>")
    }
}
