package com.emtp.kittykiller.repository

import android.util.Log
import com.emtp.kittykiller.MotorIA
import com.emtp.kittykiller.ParseadorExamenes
import com.emtp.kittykiller.Pregunta
import com.emtp.kittykiller.ProcesadorDocumentos.TipoDoc
import com.emtp.kittykiller.api.CloudApi
import com.emtp.kittykiller.api.CloudRequest
import com.emtp.kittykiller.data.toPreguntaDominio
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

enum class ModoEjecucion {
    LOCAL, // Regex o Gemma (IA en dispositivo)
    NUBE   // Firebase Functions + Gemini
}

class GeneradorTestsRepository {

    // Asegúrate de que la URL termina en "/"
    private val baseUrl =
        "https://us-central1-kittykiller-api-v1.cloudfunctions.net/"

    // Cliente HTTP con tiempo de espera extendido para IA
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(90, TimeUnit.SECONDS) // Conexión inicial
            .readTimeout(90, TimeUnit.SECONDS)    // Espera de respuesta (IA lentas)
            .writeTimeout(90, TimeUnit.SECONDS)   // Envío de datos (PDF grandes)
            .build()
    }

    private val cloudApi: CloudApi by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient) // Asignamos el cliente configurado
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CloudApi::class.java)
    }

    suspend fun generarTest(
        texto: String,
        tipoDoc: TipoDoc,
        modo: ModoEjecucion,
        cantidadPreguntasTeoria: Int = 20
    ): List<Pregunta> {

        return when (modo) {
            ModoEjecucion.LOCAL -> procesarLocal(texto, tipoDoc, cantidadPreguntasTeoria)
            ModoEjecucion.NUBE -> procesarNube(texto, tipoDoc)
        }
    }

    // --- LÓGICA LOCAL ---
    private suspend fun procesarLocal(
        texto: String,
        tipoDoc: TipoDoc,
        cantidad: Int
    ): List<Pregunta> {
        return if (tipoDoc == TipoDoc.TEST) {
            Log.d("Repo", "Procesando Test Local con Regex")
            // Caso PDF Test: Usamos el parseador de PDFs (sin IA)
            ParseadorExamenes.parsearTexto(texto)
        } else {
            Log.d("Repo", "Generando Teoría Local con MotorIA (Gemma)")

            // --- AQUÍ ESTABA EL ERROR ---
            // Ahora pasamos el tercer parámetro (onProgress) que exige tu función.
            // Simplemente logueamos el progreso, ya que la UI la controla MainActivity.
            val textoGenerado = MotorIA.generarPreguntas(texto, cantidad) { actual, total ->
                Log.d("Repo", "Progreso IA Local: Chunk $actual de $total")
            }

            // Usamos el parser específico de la IA para convertir el texto generado en objetos Pregunta
            MotorIA.parsearRespuestaIA(textoGenerado)
        }
    }

    // --- LÓGICA NUBE ---
    private suspend fun procesarNube(texto: String, tipoDoc: TipoDoc): List<Pregunta> {
        val modeApi = if (tipoDoc == TipoDoc.TEST) "extract" else "generate"

        Log.d("Repo", "Llamando a Cloud ($modeApi)")

        // SANITIZACIÓN: Eliminar caracteres de control problemáticos antes de enviar
        val textoSanitizado = texto.replace("\u000C", "") // Eliminar Form Feed

        try {
            val response = cloudApi.procesarDocumento(
                CloudRequest(mode = modeApi, content = textoSanitizado)
            )

            if (response.success && !response.data.isNullOrEmpty()) {
                return response.data.map { it.toPreguntaDominio() }
            } else {
                throw Exception("La nube respondió correctamente pero sin datos.")
            }
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e("Repo", "Error HTTP Cloud: $errorBody", e)
            // Intentar extraer mensaje JSON si es posible
            throw Exception("Error del Servidor (${e.code()}): $errorBody")
        } catch (e: Exception) {
            Log.e("Repo", "Error Cloud", e)
            throw e
        }
    }
}