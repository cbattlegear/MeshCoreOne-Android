// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MiniJsonTest {
    @Test fun parsesNestedValuesKeepingKeyOrder() {
        val v = MiniJson.parse(""" {"b": [1, -2.5e1, "x\nA", true, false, null], "a": {"k": {}} } """) as Map<*, *>
        assertEquals(listOf("b", "a"), v.keys.toList())
        assertEquals(listOf(1.0, -25.0, "x\nA", true, false, null), v["b"])
        assertEquals(mapOf("k" to emptyMap<String, Any?>()), v["a"])
    }

    @Test fun rejectsMalformedText() {
        for (bad in listOf("", "{", "[1,]", """{"a" 1}""", "tru", "1 2", """"abc""", "{\"a\":1,}", "-")) {
            assertFailsWith<IllegalArgumentException>(bad) { MiniJson.parse(bad) }
        }
    }
}
