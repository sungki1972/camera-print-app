package com.echeil.cameraprint.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.echeil.cameraprint.R
import com.echeil.cameraprint.data.AppDatabase
import com.echeil.cameraprint.data.PrintLog
import com.echeil.cameraprint.data.SupabaseSync
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class PrintWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_LOG_ID = "log_id"
        const val KEY_FILE_PATH = "file_path"
        const val SERVER_URL = "https://barcode1.echeil.com/api/print/image"
        private const val CHANNEL_ID = "print_channel"
        private const val MAX_NETWORK_RETRIES = 3
        private const val MAX_UPLOADING_RETRIES = 5

        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val logId get() = inputData.getLong(KEY_LOG_ID, -1)

    override suspend fun doWork(): Result {
        val logId = inputData.getLong(KEY_LOG_ID, -1)
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val file = File(filePath)

        val dao = AppDatabase.getInstance(applicationContext).printLogDao()

        if (!file.exists() || logId == -1L) {
            if (logId != -1L) {
                dao.updateStatus(logId, PrintLog.STATUS_FAILED, error = "원본 파일 없음")
            }
            return Result.failure()
        }

        // 원자적 작업 점유: pending/failed → uploading
        val claimed = dao.claimJob(logId)
        if (claimed == 0) {
            val existing = dao.getById(logId)
            return when (existing?.status) {
                PrintLog.STATUS_SUCCESS -> Result.success()
                PrintLog.STATUS_UPLOADING -> {
                    // BUG-D FIX: 다른 Worker가 처리 중이거나 죽었음
                    // retry로 반환하여 backoff 후 재시도 — 죽은 Worker는 stale reset 후 처리
                    if (runAttemptCount < MAX_UPLOADING_RETRIES) Result.retry()
                    else {
                        dao.updateStatus(logId, PrintLog.STATUS_FAILED, error = "처리 시간 초과")
                        dao.getById(logId)?.let { SupabaseSync.syncStatus(applicationContext, it, PrintLog.STATUS_FAILED) }
                        Result.failure()
                    }
                }
                else -> Result.success()
            }
        }

        // 서버 멱등성 키: 같은 logId로 중복 POST해도 서버가 1번만 프린트
        val printId = "cprint_${logId}"

        // 웹 기록탭용: 전송 시작 상태를 Supabase에 반영 (best-effort)
        dao.getById(logId)?.let { SupabaseSync.syncStatus(applicationContext, it, PrintLog.STATUS_UPLOADING) }

        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", file.name, file.asRequestBody("image/jpeg".toMediaType()))
                .build()

            val request = Request.Builder()
                .url(SERVER_URL)
                .header("X-Print-Id", printId)
                .post(requestBody)
                .build()

            sharedClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    dao.updateStatus(logId, PrintLog.STATUS_SUCCESS, completedAt = System.currentTimeMillis())
                    showNotification("A4 프린트 완료", file.name)
                    dao.getById(logId)?.let { SupabaseSync.syncWithImage(applicationContext, it, PrintLog.STATUS_SUCCESS) }
                    Result.success()
                } else {
                    val msg = "서버 오류 (${response.code})"
                    dao.updateStatus(logId, PrintLog.STATUS_FAILED, error = msg)
                    showNotification("프린트 실패", msg)
                    dao.getById(logId)?.let { SupabaseSync.syncWithImage(applicationContext, it, PrintLog.STATUS_FAILED) }
                    Result.failure()
                }
            }
        } catch (e: CancellationException) {
            // BUG-A FIX (핵심): CancellationException은 반드시 재던지기
            // 여기서 잡으면 status=failed 설정 → 교체 Worker가 또 POST → 중복 프린트
            // 재던지면 status=uploading 유지 → 교체 Worker는 claimJob 실패 → 중복 차단
            throw e
        } catch (e: IOException) {
            // BUG-C FIX: 네트워크 에러는 재시도 (일시적 장애)
            if (runAttemptCount < MAX_NETWORK_RETRIES) {
                Result.retry()
            } else {
                val msg = "네트워크 오류: ${e.message}"
                dao.updateStatus(logId, PrintLog.STATUS_FAILED, error = msg)
                showNotification("프린트 실패", msg)
                dao.getById(logId)?.let { SupabaseSync.syncWithImage(applicationContext, it, PrintLog.STATUS_FAILED) }
                Result.failure()
            }
        } catch (e: Exception) {
            val msg = e.message ?: "알 수 없는 오류"
            dao.updateStatus(logId, PrintLog.STATUS_FAILED, error = msg)
            showNotification("프린트 실패", msg)
            dao.getById(logId)?.let { SupabaseSync.syncWithImage(applicationContext, it, PrintLog.STATUS_FAILED) }
            Result.failure()
        }
    }

    private fun showNotification(title: String, message: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "프린트 알림", NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        manager.notify(logId.hashCode(), notification)
    }
}
