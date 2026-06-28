package com.echeil.cameraprint.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import com.echeil.cameraprint.worker.PrintWorker
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 프린트 기록을 Supabase 로 동기화 — 웹 기록탭에서 보기 위함.
 * 전부 best-effort: 실패해도 인쇄/로컬 DB 흐름에 영향 없음.
 */
object SupabaseSync {

    private const val SUPABASE_URL = "https://pvhntshaadmbmpskwqmg.supabase.co"
    private const val ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InB2aG50c2hhYWRtYm1wc2t3cW1nIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Mzg3MzQ0MzUsImV4cCI6MjA1NDMxMDQzNX0.Bxi5dVLrb_t_Y7NdT34If3A0FigTopUWSuT9rdcHzYw"
    private const val BUCKET = "camera-print"
    private const val TABLE = "camera_print_logs"
    private const val THUMB_MAX_DIM = 1024

    private val isoFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private fun deviceId(context: Context): String =
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

    private fun remoteId(context: Context, logId: Long) = "${deviceId(context)}_$logId"

    private fun storagePath(context: Context, logId: Long) = "${deviceId(context)}/$logId.jpg"

    /** 상태만 동기화 (이미지 업로드 없음) — 시작/실패 시 사용 */
    fun syncStatus(
        context: Context,
        log: PrintLog,
        statusOverride: String? = null,
        imageUrl: String? = null
    ) {
        try {
            val body = JSONObject().apply {
                put("id", remoteId(context, log.id))
                put("device_id", deviceId(context))
                put("log_id", log.id)
                put("file_name", log.fileName)
                put("status", statusOverride ?: log.status)
                put("error_message", log.errorMessage ?: JSONObject.NULL)
                put("created_at", isoFormat.format(Date(log.createdAt)))
                if (log.completedAt != null) put("completed_at", isoFormat.format(Date(log.completedAt)))
                if (imageUrl != null) put("image_url", imageUrl)
                put("synced_at", isoFormat.format(Date()))
            }

            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/$TABLE")
                .header("apikey", ANON_KEY)
                .header("Authorization", "Bearer $ANON_KEY")
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            PrintWorker.sharedClient.newCall(request).execute().use { /* best-effort */ }
        } catch (_: Exception) {
            // 무시 — 동기화 실패가 인쇄를 막지 않게
        }
    }

    /**
     * 썸네일 업로드 + 행 동기화. 성공/실패 종료 시 호출.
     * @return 업로드된 공개 URL 또는 null
     */
    fun syncWithImage(context: Context, log: PrintLog, finalStatus: String) {
        var publicUrl: String? = null
        try {
            val file = File(log.filePath)
            if (file.exists() && file.length() > 0) {
                val jpeg = downscaleJpeg(log.filePath)
                if (jpeg != null) {
                    val path = storagePath(context, log.id)
                    val uploadReq = Request.Builder()
                        .url("$SUPABASE_URL/storage/v1/object/$BUCKET/$path")
                        .header("apikey", ANON_KEY)
                        .header("Authorization", "Bearer $ANON_KEY")
                        .header("x-upsert", "true")
                        .post(jpeg.toRequestBody("image/jpeg".toMediaType()))
                        .build()
                    PrintWorker.sharedClient.newCall(uploadReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            publicUrl = "$SUPABASE_URL/storage/v1/object/public/$BUCKET/$path"
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 이미지 업로드 실패는 무시하고 메타데이터만 동기화
        }
        syncStatus(context, log.copy(status = finalStatus), statusOverride = finalStatus, imageUrl = publicUrl)
    }

    /** 행 삭제 동기화 — 앱에서 기록 삭제 시 웹에서도 사라지게 */
    fun deleteRemote(context: Context, logId: Long) {
        try {
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/$TABLE?id=eq.${remoteId(context, logId)}")
                .header("apikey", ANON_KEY)
                .header("Authorization", "Bearer $ANON_KEY")
                .delete()
                .build()
            PrintWorker.sharedClient.newCall(request).execute().use { /* best-effort */ }
        } catch (_: Exception) {
        }
    }

    /** 이 기기의 모든 원격 기록 삭제 — 앱에서 전체 삭제 시 */
    fun deleteAllForDevice(context: Context) {
        try {
            val request = Request.Builder()
                .url("$SUPABASE_URL/rest/v1/$TABLE?device_id=eq.${deviceId(context)}")
                .header("apikey", ANON_KEY)
                .header("Authorization", "Bearer $ANON_KEY")
                .delete()
                .build()
            PrintWorker.sharedClient.newCall(request).execute().use { /* best-effort */ }
        } catch (_: Exception) {
        }
    }

    private fun downscaleJpeg(path: String): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (maxDim / sample > THUMB_MAX_DIM * 2) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(path, opts) ?: return null

            val scale = THUMB_MAX_DIM.toFloat() / maxOf(bmp.width, bmp.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            } else bmp

            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }
}
