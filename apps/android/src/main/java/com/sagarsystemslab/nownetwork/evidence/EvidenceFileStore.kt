package com.sagarsystemslab.nownetwork.evidence

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface EvidenceFileStore {
    fun fileFor(evidenceId: String): File
    suspend fun delete(file: File)
}

@Singleton
class AppPrivateEvidenceFileStore @Inject constructor(
    @ApplicationContext context: Context,
) : EvidenceFileStore {
    private val directory = File(context.filesDir, "evidence").apply { mkdirs() }

    override fun fileFor(evidenceId: String): File =
        File(directory, "$evidenceId.jpg")

    override suspend fun delete(file: File) {
        withContext(Dispatchers.IO) {
            file.delete()
        }
    }
}
