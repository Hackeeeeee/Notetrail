package com.mechanicalaxiom.notetrail.ui.theme

import android.util.Log
import com.mechanicalaxiom.notetrail.LinkProtector
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles all authenticated communication with the private IGCSE repository.
 */
object GitHubScanner {

    // 1. Fetch this from Firebase Remote Config or a secure backend.
    // DO NOT hardcode this in a production app.
    var GITHUB_TOKEN = ""

    const val REPO_OWNER = "Hackeeeeee"
    const val REPO_NAME = "Igcse-data"
    const val BRANCH = "main"

    private val client = OkHttpClient()

    /**
     * Updates the token from a secure source.
     */
    fun updateToken(token: String) {
        if (token.isNotEmpty()) {
            GITHUB_TOKEN = token
            Log.d("GitHubScanner", "GitHub Token updated successfully")
        } else {
            Log.w("GitHubScanner", "Attempted to update with an empty token")
        }
    }

    /**
     * Scans the private repository to see which papers actually exist.
     * This is used to dynamically update the app UI for 2025, 2026, etc.
     */
    suspend fun fetchRepoFiles(): List<String> = withContext(Dispatchers.IO) {
        val fileList = mutableListOf<String>()
        // API endpoint to list contents of the root directory
        val url = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents/"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $GITHUB_TOKEN")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", LinkProtector.getUserAgent())
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("GitHubScanner", "Access Denied (Check Token/Repo): ${response.code}")
                    return@withContext emptyList()
                }

                val jsonData = response.body?.string() ?: ""
                val jsonArray = JSONArray(jsonData)

                for (i in 0 until jsonArray.length()) {
                    val fileObj = jsonArray.getJSONObject(i)
                    val name = fileObj.getString("name")
                    // Only track exam-related assets
                    if (name.endsWith(".pdf") || name.endsWith(".mp3")) {
                        fileList.add(name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GitHubScanner", "Network Error: ${e.message}")
        }

        return@withContext fileList
    }

    /**
     * Creates an authenticated request for a specific file.
     * For private repos, we use the API with the 'vnd.github.v3.raw' header.
     */
    fun getAuthenticatedRequest(fileName: String): Request {
        val url = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents/$fileName?ref=$BRANCH"
        
        val authHeader = "Bearer $GITHUB_TOKEN"

        Log.d("GitHubScanner", "Request URL: $url")
        Log.d("GitHubScanner", "Auth Header Used: Bearer ${GITHUB_TOKEN.take(4)}***${GITHUB_TOKEN.takeLast(4)}")

        return Request.Builder()
            .url(url)
            .addHeader("Authorization", authHeader)
            .addHeader("Accept", "application/vnd.github.v3.raw")
            .addHeader("User-Agent", LinkProtector.getUserAgent())
            .build()
    }
}