package com.saketh.legalaid

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

class DocumentSummarizer(private val context: Context) {
    private val client = OkHttpClient()

    // Set this to false to use real Gemini API
    private val USE_MOCK_DATA = false

    companion object {
        // Using localhost for the server
        private const val SERVER_IP = "10.0.2.2"  // localhost for Android Emulator
        private const val SERVER_PORT = "5000"
        private const val SERVER_URL = "http://$SERVER_IP:$SERVER_PORT/summarize"
    }

    interface SummaryCallback {
        fun onSuccess(response: JSONObject)
        fun onError(error: String)
    }

    fun summarizeDocument(file: File, callback: SummaryCallback) {
        if (USE_MOCK_DATA) {
            // Return mock data immediately
            provideMockResponse(callback)
            return
        }

        android.util.Log.d("DocumentSummarizer", "Starting document summarization")
        android.util.Log.d("DocumentSummarizer", "Server URL: $SERVER_URL")
        android.util.Log.d("DocumentSummarizer", "File path: ${file.absolutePath}")
        android.util.Log.d("DocumentSummarizer", "File exists: ${file.exists()}")
        android.util.Log.d("DocumentSummarizer", "File size: ${file.length()} bytes")

        val mediaType = when (file.extension.toLowerCase()) {
            "pdf" -> "application/pdf".toMediaTypeOrNull()
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document".toMediaTypeOrNull()
            "doc" -> "application/msword".toMediaTypeOrNull()
            "txt" -> "text/plain".toMediaTypeOrNull()
            "jpg", "jpeg" -> "image/jpeg".toMediaTypeOrNull()
            "png" -> "image/png".toMediaTypeOrNull()
            else -> "application/octet-stream".toMediaTypeOrNull()
        } ?: throw IllegalArgumentException("Invalid media type")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody(mediaType)
            )
            .build()

        val request = Request.Builder()
            .url(SERVER_URL)
            .post(requestBody)
            .build()

        android.util.Log.d("DocumentSummarizer", "Sending request to server")

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                android.util.Log.e("DocumentSummarizer", "Network error: ${e.message}")
                callback.onError("Network error: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                android.util.Log.d("DocumentSummarizer", "Received response: ${response.code}")
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    android.util.Log.d("DocumentSummarizer", "Response body: $responseBody")
                    val summary = responseBody?.let { parseSummary(it) }
                    if (summary != null) {
                        callback.onSuccess(summary)
                    } else {
                        android.util.Log.e("DocumentSummarizer", "Failed to parse response")
                        callback.onError("Failed to parse response")
                    }
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    android.util.Log.e("DocumentSummarizer", "Server error: ${response.code}, Body: $errorBody")
                    callback.onError("Server error: ${response.code}\n$errorBody")
                }
            }
        })
    }

    private fun parseSummary(jsonResponse: String): JSONObject? {
        return try {
            val response = JSONObject(jsonResponse)

            // Create a new JSON object with properly structured data
            JSONObject().apply {
                // Extract and clean title
                val rawTitle = response.optString("title", "Document Summary")
                    .replace("*", "")
                    .replace("Title:", "", ignoreCase = true)
                    .replace("##", "")
                    .trim()
                put("title", rawTitle)

                // Extract and clean summary
                val rawContent = response.optString("content", "")
                val summaryText = when {
                    rawContent.contains("1. Summary", ignoreCase = true) -> {
                        val afterSummaryHeader = rawContent.substringAfter("1. Summary", "")
                        if (afterSummaryHeader.contains("2. Key Points", ignoreCase = true)) {
                            afterSummaryHeader.substringBefore("2. Key Points").trim()
                        } else {
                            afterSummaryHeader.trim()
                        }
                    }
                    rawContent.contains("Summary:", ignoreCase = true) -> {
                        val afterSummary = rawContent.substringAfter("Summary:", "")
                        if (afterSummary.contains("Key Points:", ignoreCase = true)) {
                            afterSummary.substringBefore("Key Points:").trim()
                        } else {
                            afterSummary.trim()
                        }
                    }
                    else -> response.optString("summary", "").trim()
                }

                val cleanedSummary = summaryText
                    .replace("##", "")
                    .replace("Document Analysis", "", ignoreCase = true)
                    .replace("*", "")
                    .replace("Summary:", "", ignoreCase = true)
                    .replace("Concise Summary:", "", ignoreCase = true)
                    .replace("Document Summary:", "", ignoreCase = true)
                    .trim()
                put("summary", cleanedSummary)

                // Extract and clean key points
                val keyPointsArray = JSONArray()

                // Try to find the key points section
                val keyPointsText = when {
                    rawContent.contains("2. Key Points", ignoreCase = true) ->
                        rawContent.substringAfter("2. Key Points", "")
                    rawContent.contains("Key Points:", ignoreCase = true) ->
                        rawContent.substringAfter("Key Points:", "")
                    else -> ""
                }

                // Process the key points text
                val lines = keyPointsText.split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                // Extract actual key points
                lines.forEach { line ->
                    // Skip metadata-like lines and headers
                    if (line.contains("File Type:", ignoreCase = true) ||
                        line.contains("Document Type:", ignoreCase = true) ||
                        line.startsWith("2.", ignoreCase = true) ||
                        line.startsWith("Key Points:", ignoreCase = true)) {
                        return@forEach
                    }

                    // Clean and format the point
                    val cleanedPoint = line
                        .replace("*", "")
                        .replace("##", "")
                        .replace("Content:", "")
                        .replace("Purpose:", "")
                        .replace("Length:", "")
                        .replace("Readability:", "")
                        .replace("Functionality:", "")
                        .replace("•", "") // Remove any existing bullet points
                        .replace("-", "") // Remove any existing dashes
                        .trim()

                    if (cleanedPoint.isNotEmpty()) {
                        keyPointsArray.put(cleanedPoint)
                    }
                }

                put("key_points", keyPointsArray)
            }
        } catch (e: Exception) {
            android.util.Log.e("DocumentSummarizer", "Error parsing response: ${e.message}")
            null
        }
    }

    private fun provideMockResponse(callback: SummaryCallback) {
        // Create a mock JSON response
        val mockResponse = JSONObject().apply {
            put("title", "Confidentiality Agreement")
            put("summary", "This is a comprehensive confidentiality agreement between Company X and Party Y. " +
                    "The agreement outlines the terms and conditions for handling sensitive information shared " +
                    "between the parties during their business relationship. It includes specific provisions for " +
                    "data protection, permitted uses, and breach consequences.")
            
            // Add key points
            val keyPoints = JSONArray().apply {
                put("Defines confidential information and its scope")
                put("Specifies permitted uses of confidential information")
                put("Outlines security measures for data protection")
                put("Details breach notification requirements")
                put("Sets duration and termination conditions")
            }
            put("key_points", keyPoints)
        }
        
        // Return the mock response
        callback.onSuccess(mockResponse)
    }
}