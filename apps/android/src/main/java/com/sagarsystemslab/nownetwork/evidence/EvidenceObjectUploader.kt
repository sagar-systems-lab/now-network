package com.sagarsystemslab.nownetwork.evidence

import com.sagarsystemslab.nownetwork.config.PublicRuntimeConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class EvidenceObjectUploadFailure(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class InvalidTarget(message: String) : EvidenceObjectUploadFailure(message)
    class MissingFile : EvidenceObjectUploadFailure("Captured evidence file is missing.")
    class Rejected(status: Int) :
        EvidenceObjectUploadFailure("Evidence storage rejected the upload ($status).")
    class Network(cause: Throwable) :
        EvidenceObjectUploadFailure("Evidence storage is temporarily unavailable.", cause)
}

interface EvidenceObjectUploader {
    suspend fun upload(
        signedUrl: String,
        method: String,
        contentType: String,
        file: File,
    )
}

@Singleton
class KtorEvidenceObjectUploader @Inject constructor(
    private val client: HttpClient,
    private val config: PublicRuntimeConfig,
) : EvidenceObjectUploader {
    override suspend fun upload(
        signedUrl: String,
        method: String,
        contentType: String,
        file: File,
    ) {
        if (!file.isFile || file.length() <= 0L) {
            throw EvidenceObjectUploadFailure.MissingFile()
        }
        if (method.uppercase() != "PUT") {
            throw EvidenceObjectUploadFailure.InvalidTarget("Unsupported evidence upload method.")
        }

        val target = runCatching { URI(signedUrl) }
            .getOrElse {
                throw EvidenceObjectUploadFailure.InvalidTarget("Evidence upload target is invalid.")
            }
        val expected = runCatching { URI(config.supabaseUrl) }.getOrNull()

        if (
            target.scheme != "https" ||
            target.host.isNullOrBlank() ||
            target.rawUserInfo != null ||
            expected?.host.isNullOrBlank() ||
            !target.host.equals(expected?.host, ignoreCase = true)
        ) {
            throw EvidenceObjectUploadFailure.InvalidTarget(
                "Evidence upload target does not match configured storage.",
            )
        }

        val bytes = try {
            withContext(Dispatchers.IO) {
                file.readBytes()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw EvidenceObjectUploadFailure.MissingFile()
        }

        val response = try {
            client.put(signedUrl) {
                header(HttpHeaders.ContentType, contentType)
                setBody(bytes)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw EvidenceObjectUploadFailure.Network(error)
        }

        if (response.status.value == 409) return
        if (!response.status.isSuccess()) {
            // Consume a bounded response body without surfacing the signed URL or token.
            val responseBody = runCatching { response.bodyAsText().take(512) }.getOrDefault("")
            val duplicate = runCatching {
                val body = kotlinx.serialization.json.Json.parseToJsonElement(responseBody) as? kotlinx.serialization.json.JsonObject
                val code = (body?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
                val message = (body?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                code == "Duplicate" || message == "The resource already exists"
            }.getOrDefault(false)
            if (response.status.value == 400 && duplicate) return
            throw EvidenceObjectUploadFailure.Rejected(response.status.value)
        }
    }
}
