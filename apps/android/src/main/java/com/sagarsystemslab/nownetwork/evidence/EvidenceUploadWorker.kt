package com.sagarsystemslab.nownetwork.evidence

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sagarsystemslab.nownetwork.repository.EvidenceCaptureFailure
import com.sagarsystemslab.nownetwork.repository.EvidenceCaptureRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface EvidenceWorkerEntryPoint {
    fun evidenceCaptureRepository(): EvidenceCaptureRepository
}

class EvidenceUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val evidenceId = inputData.getString(KEY_EVIDENCE_ID)
            ?: return Result.failure()

        val repository = EntryPointAccessors.fromApplication(
            applicationContext,
            EvidenceWorkerEntryPoint::class.java,
        ).evidenceCaptureRepository()

        return try {
            repository.resumeSubmission(evidenceId)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: EvidenceCaptureFailure.Network) {
            Result.retry()
        } catch (_: EvidenceCaptureFailure.Server) {
            Result.retry()
        } catch (_: EvidenceCaptureFailure.UploadNotReady) {
            Result.retry()
        } catch (_: EvidenceCaptureFailure.Storage) {
            Result.retry()
        } catch (_: EvidenceCaptureFailure) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_EVIDENCE_ID = "evidence_id"
    }
}

@Singleton
class EvidenceWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager by lazy { WorkManager.getInstance(context) }

    fun schedule(evidenceId: String) {
        val request = OneTimeWorkRequestBuilder<EvidenceUploadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(EvidenceUploadWorker.KEY_EVIDENCE_ID, evidenceId)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(15, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            workName(evidenceId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(evidenceId: String) {
        workManager.cancelUniqueWork(workName(evidenceId))
    }

    private fun workName(evidenceId: String): String =
        "evidence-upload-$evidenceId"
}
