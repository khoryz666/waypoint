package com.khoryz666.waypoint.fakes

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.khoryz666.waypoint.data.SecretCipher
import com.khoryz666.waypoint.data.SettingsStore
import com.khoryz666.waypoint.data.UsageDao
import com.khoryz666.waypoint.data.UsageRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.Base64

/** Reversible "encryption" for JVM tests; Robolectric has no AndroidKeyStore. */
class FakeCipher : SecretCipher {
    override fun encrypt(plain: ByteArray): String = Base64.getEncoder().encodeToString(plain)
    override fun decrypt(sealed: String): ByteArray? = Base64.getDecoder().decode(sealed)
}

fun testSettings(scope: CoroutineScope, dir: File): SettingsStore =
    SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }, FakeCipher())

class FakeUsageDao : UsageDao {
    val records = MutableStateFlow(emptyList<UsageRecord>())
    override fun observeSince(from: Long): Flow<List<UsageRecord>> = records.map { list -> list.filter { it.at >= from } }
    override suspend fun costSince(from: Long) = records.value.filter { it.at >= from }.sumOf { it.costCny }
    override suspend fun since(from: Long) = records.value.filter { it.at >= from }
    override suspend fun insert(record: UsageRecord) {
        records.value += record
    }
}
