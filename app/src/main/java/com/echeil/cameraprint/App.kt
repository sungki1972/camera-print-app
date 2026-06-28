package com.echeil.cameraprint

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.echeil.cameraprint.data.AppDatabase
import com.echeil.cameraprint.data.PrintLog
import com.echeil.cameraprint.worker.PrintWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "print_channel",
                "프린트 알림",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        rescueOrphanedJobs()
    }

    private fun rescueOrphanedJobs() {
        appScope.launch {
            val dao = AppDatabase.getInstance(this@App).printLogDao()

            // BUG-G FIX: 3분으로 단축 (10분 → 3분)
            // 프로세스 사망 후 빠른 복구를 위해
            val staleThreshold = System.currentTimeMillis() - 3 * 60 * 1000
            dao.resetStaleUploading(staleThreshold)

            // PENDING만 복구 (UPLOADING은 진행 중일 수 있으므로 건드리지 않음)
            val pending = dao.getByStatuses(listOf(PrintLog.STATUS_PENDING))

            for (log in pending) {
                val file = File(log.filePath)
                if (file.exists()) {
                    val workRequest = OneTimeWorkRequestBuilder<PrintWorker>()
                        .setInputData(
                            workDataOf(
                                PrintWorker.KEY_LOG_ID to log.id,
                                PrintWorker.KEY_FILE_PATH to log.filePath
                            )
                        )
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .build()

                    WorkManager.getInstance(this@App)
                        .enqueueUniqueWork("print_${log.id}", ExistingWorkPolicy.KEEP, workRequest)
                } else {
                    dao.updateStatus(log.id, PrintLog.STATUS_FAILED, error = "원본 파일 없음")
                }
            }
        }
    }
}
