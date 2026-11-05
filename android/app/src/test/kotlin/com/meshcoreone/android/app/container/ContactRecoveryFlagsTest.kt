// AndroidOnly: WP-209 Reconciliation flags commit to disk and cannot silently accept a failed commit.
package com.meshcoreone.android.app.container

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ContactRecoveryFlagsTest {
    @Test
    fun recordedRecoveryFlagSurvivesAdapterReplacementAndIsWrittenToDisk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SharedPreferencesContactFlags(context).set("recovery-test", true)
        assertTrue(SharedPreferencesContactFlags(context).bool("recovery-test"))
        val file = File(context.applicationInfo.dataDir, "shared_prefs/mc1.contact-flags.xml")
        assertTrue(file.readText().contains("recovery-test"))
    }

    @Test
    fun failedDurableCommitIsReportedExplicitly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val actual = context.getSharedPreferences("commit-failure-test", Context.MODE_PRIVATE)
        val failing = object : SharedPreferences by actual {
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by actual.edit() {
                override fun commit(): Boolean = false
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            }
        }
        val wrapper = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = failing
        }
        assertFailsWith<IllegalStateException> { SharedPreferencesContactFlags(wrapper).set("recovery-test", true) }
    }
}
