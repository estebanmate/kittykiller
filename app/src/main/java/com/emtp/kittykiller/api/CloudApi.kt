package com.emtp.kittykiller.api

import com.emtp.kittykiller.data.CloudResponse
import retrofit2.http.Body
import retrofit2.http.POST

data class CloudRequest(
    val mode: String,   // "extract" o "generate"
    val content: String // Texto extraído del PDF
)

interface CloudApi {
    // Reemplaza con la URL base de tu función (ej: https://us-central1-tu-proyecto.cloudfunctions.net/)
    @POST("procesarDocumentoTest")
    suspend fun procesarDocumento(@Body request: CloudRequest): CloudResponse
}