package com.kidguard.app.telegram

import com.kidguard.app.data.TelegramApiResult
import com.kidguard.app.data.TelegramUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

object TelegramClient {

    private const val CONNECT_TIMEOUT_MS = 15_000

    suspend fun getMe(botToken: String): TelegramApiResult = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) {
            return@withContext TelegramApiResult(false, "Token Bot kosong.")
        }
        val endpoint = "https://api.telegram.org/bot$botToken/getMe"
        try {
            val responseJson = executeGetRequest(endpoint, readTimeoutMs = 15_000)
            val root = JSONObject(responseJson)
            val ok = root.optBoolean("ok", false)
            if (ok) {
                val resultObj = root.optJSONObject("result")
                val username = resultObj?.optString("username", "").orEmpty()
                TelegramApiResult(true, "Berhasil terhubung ke @$username", username)
            } else {
                val desc = root.optString("description", "Token Bot ditolak oleh server Telegram.")
                TelegramApiResult(false, desc)
            }
        } catch (e: Exception) {
            TelegramApiResult(false, e.localizedMessage ?: "Gagal menghubungi server Telegram.")
        }
    }

    suspend fun getUpdates(
        botToken: String,
        offset: Long,
        timeoutSec: Int = 25
    ): List<TelegramUpdate> = withContext(Dispatchers.IO) {
        if (botToken.isBlank()) return@withContext emptyList()
        val endpoint =
            "https://api.telegram.org/bot$botToken/getUpdates?offset=$offset&timeout=$timeoutSec&allowed_updates=%5B%22message%22%5D"
        try {
            // Read timeout must exceed Telegram server long-polling timeout to avoid premature socket closure.
            val rawResponse = executeGetRequest(endpoint, readTimeoutMs = (timeoutSec + 10) * 1000)
            val root = JSONObject(rawResponse)
            if (!root.optBoolean("ok", false)) return@withContext emptyList()

            val resultArray = root.optJSONArray("result") ?: return@withContext emptyList()
            val updates = ArrayList<TelegramUpdate>(resultArray.length())

            for (i in 0 until resultArray.length()) {
                val item = resultArray.optJSONObject(i) ?: continue
                val updateId = item.optLong("update_id", -1L)
                val message = item.optJSONObject("message") ?: continue
                val chat = message.optJSONObject("chat") ?: continue
                val chatId = chat.optLong("id", 0L).toString()
                val text = message.optString("text", "").trim()
                if (updateId < 0L || text.isEmpty()) continue

                val fromObj = message.optJSONObject("from")
                val senderName = fromObj?.optString("first_name", "Pengguna").orEmpty()
                updates.add(
                    TelegramUpdate(
                        updateId = updateId,
                        chatId = chatId,
                        senderName = senderName,
                        text = text
                    )
                )
            }
            updates
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun sendMessage(
        botToken: String,
        chatId: String,
        text: String,
        includeReplyKeyboard: Boolean = false
    ): TelegramApiResult = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) {
            return@withContext TelegramApiResult(false, "Token Bot atau Chat ID belum diisi.")
        }
        val endpoint = "https://api.telegram.org/bot$botToken/sendMessage"
        val payload = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            put("disable_web_page_preview", true)
            if (includeReplyKeyboard) {
                put("reply_markup", buildReplyKeyboardMarkup())
            }
        }
        executeJsonPost(endpoint, payload)
    }

    suspend fun sendLocation(
        botToken: String,
        chatId: String,
        latitude: Double,
        longitude: Double
    ): TelegramApiResult = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) {
            return@withContext TelegramApiResult(false, "Token Bot atau Chat ID belum diisi.")
        }
        val endpoint = "https://api.telegram.org/bot$botToken/sendLocation"
        val payload = JSONObject().apply {
            put("chat_id", chatId)
            put("latitude", latitude)
            put("longitude", longitude)
        }
        executeJsonPost(endpoint, payload)
    }

    suspend fun sendPhoto(
        botToken: String,
        chatId: String,
        photoFile: File,
        caption: String
    ): TelegramApiResult = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) {
            return@withContext TelegramApiResult(false, "Token Bot atau Chat ID belum diisi.")
        }
        if (!photoFile.exists()) {
            return@withContext TelegramApiResult(false, "Berkas tangkapan layar tidak ditemukan.")
        }

        val boundary = "----KidGuardBoundary" + UUID.randomUUID().toString().replace("-", "")
        val lineEnd = "\r\n"
        val twoHyphens = "--"
        val endpoint = "https://api.telegram.org/bot$botToken/sendPhoto"

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 30_000
                doInput = true
                doOutput = true
                useCaches = false
                requestMethod = "POST"
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(connection.outputStream).use { output ->
                writeFormField(output, boundary, "chat_id", chatId)
                writeFormField(output, boundary, "caption", caption)

                output.writeBytes(twoHyphens + boundary + lineEnd)
                output.writeBytes(
                    "Content-Disposition: form-data; name=\"photo\"; filename=\"${photoFile.name}\"$lineEnd"
                )
                output.writeBytes("Content-Type: image/png$lineEnd$lineEnd")

                FileInputStream(photoFile).use { fileInput ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fileInput.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
                output.writeBytes(lineEnd)
                output.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
                output.flush()
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = readStream(stream)
            val root = JSONObject(body)
            val ok = root.optBoolean("ok", false)
            val desc = root.optString("description", if (ok) "Terkirim" else "Gagal mengirim foto")
            TelegramApiResult(ok, desc)
        } catch (e: Exception) {
            TelegramApiResult(false, e.localizedMessage ?: "Kesalahan jaringan saat mengunggah foto.")
        } finally {
            connection?.disconnect()
        }
    }

    private fun writeFormField(
        output: DataOutputStream,
        boundary: String,
        name: String,
        value: String
    ) {
        val lineEnd = "\r\n"
        output.writeBytes("--$boundary$lineEnd")
        output.writeBytes("Content-Disposition: form-data; name=\"$name\"$lineEnd$lineEnd")
        output.write(value.toByteArray(StandardCharsets.UTF_8))
        output.writeBytes(lineEnd)
    }

    private fun buildReplyKeyboardMarkup(): JSONObject {
        val row1 = JSONArray().put("/status").put("/ss").put("/lokasi")
        val row2 = JSONArray().put("/yt").put("/screentime").put("/wa")
        val row3 = JSONArray().put("/lock").put("/unlock")
        val row4 = JSONArray().put("/menu")
        val keyboard = JSONArray().put(row1).put(row2).put(row3).put(row4)

        return JSONObject().apply {
            put("keyboard", keyboard)
            put("resize_keyboard", true)
            put("is_persistent", true)
        }
    }

    private fun executeGetRequest(urlStr: String, readTimeoutMs: Int): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return readStream(stream)
        } finally {
            connection?.disconnect()
        }
    }

    private fun executeJsonPost(urlStr: String, payload: JSONObject): TelegramApiResult {
        var connection: HttpURLConnection? = null
        return try {
            val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = readStream(stream)
            val root = JSONObject(responseText)
            val ok = root.optBoolean("ok", false)
            val desc = root.optString(
                "description",
                if (ok) "Pesan terkirim." else "Permintaan ditolak oleh Telegram (HTTP $code)."
            )
            TelegramApiResult(ok, desc)
        } catch (e: Exception) {
            TelegramApiResult(false, e.localizedMessage ?: "Gagal terhubung ke api.telegram.org.")
        } finally {
            connection?.disconnect()
        }
    }

    private fun readStream(stream: InputStream?): String {
        if (stream == null) return "{}"
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            sb.toString()
        }
    }
}
