package com.echeil.cameraprint

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.echeil.cameraprint.data.AppDatabase
import com.echeil.cameraprint.data.PrintLog
import com.echeil.cameraprint.data.SupabaseSync
import com.echeil.cameraprint.worker.PrintWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 100
        private const val REQUEST_IMAGE_CAPTURE = 101
        private const val KEY_PHOTO_PATH = "current_photo_path"
        private const val KEY_CAMERA_LAUNCHED = "camera_launched"
        private const val KEY_LAST_ENQUEUED = "last_enqueued_path"
    }

    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView
    private lateinit var cameraButton: Button
    private lateinit var logsButton: Button
    private lateinit var titleText: TextView
    private lateinit var countBadge: TextView
    private lateinit var recentList: RecyclerView
    private lateinit var recentLabel: TextView
    private lateinit var recentAdapter: LogAdapter

    private var currentPhotoPath: String? = null
    private var cameraLaunched = false
    private var lastEnqueuedPath: String? = null
    private val dao by lazy { AppDatabase.getInstance(this).printLogDao() }

    private val appScope get() = (application as App).appScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        statusText = findViewById(R.id.statusText)
        cameraButton = findViewById(R.id.cameraButton)
        logsButton = findViewById(R.id.logsButton)
        titleText = findViewById(R.id.titleText)
        countBadge = findViewById(R.id.countBadge)
        recentList = findViewById(R.id.recentList)
        recentLabel = findViewById(R.id.recentLabel)

        recentAdapter = LogAdapter(
            onDelete = { log -> deleteRecent(log) },
            onRetry = { log -> retryRecent(log) },
            onItemClick = { log -> ImageZoom.show(this, log.filePath) }
        )
        recentList.layoutManager = LinearLayoutManager(this)
        recentList.adapter = recentAdapter

        cameraButton.setOnClickListener { checkCameraPermission() }
        logsButton.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        if (savedInstanceState != null) {
            currentPhotoPath = savedInstanceState.getString(KEY_PHOTO_PATH)
            cameraLaunched = savedInstanceState.getBoolean(KEY_CAMERA_LAUNCHED, false)
            lastEnqueuedPath = savedInstanceState.getString(KEY_LAST_ENQUEUED)
        }

        requestNotificationPermission()

        if (!cameraLaunched && savedInstanceState == null) {
            checkCameraPermission()
        } else if (!cameraLaunched) {
            cameraButton.visibility = View.VISIBLE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PHOTO_PATH, currentPhotoPath)
        outState.putBoolean(KEY_CAMERA_LAUNCHED, cameraLaunched)
        outState.putString(KEY_LAST_ENQUEUED, lastEnqueuedPath)
    }

    override fun onResume() {
        super.onResume()
        updateLogCount()
    }

    private fun updateLogCount() {
        appScope.launch {
            val count = dao.getCount()
            val recent = dao.getPage(5, 0)
            withContext(Dispatchers.Main) {
                if (!isFinishing) {
                    if (count > 0) {
                        countBadge.text = "$count"
                        countBadge.visibility = View.VISIBLE
                    } else {
                        countBadge.visibility = View.GONE
                    }
                    recentAdapter.submitList(recent)
                    recentLabel.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun deleteRecent(log: PrintLog) {
        appScope.launch {
            WorkManager.getInstance(applicationContext).cancelUniqueWork("print_${log.id}")
            dao.delete(log)
            SupabaseSync.deleteRemote(applicationContext, log.id)
            try { File(log.filePath).delete() } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                if (!isFinishing) {
                    Toast.makeText(this@MainActivity, "삭제됨", Toast.LENGTH_SHORT).show()
                    updateLogCount()
                }
            }
        }
    }

    private fun retryRecent(log: PrintLog) {
        val file = File(log.filePath)
        if (!file.exists()) {
            Toast.makeText(this, "원본 파일이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        appScope.launch {
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
            WorkManager.getInstance(this@MainActivity)
                .enqueueUniqueWork("print_${log.id}", ExistingWorkPolicy.REPLACE, workRequest)
            withContext(Dispatchers.Main) {
                if (!isFinishing) {
                    Toast.makeText(this@MainActivity, "재전송 접수됨", Toast.LENGTH_SHORT).show()
                    updateLogCount()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200
                )
            }
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION
            )
        } else {
            launchCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            statusText.text = "카메라 권한이 필요합니다"
            statusText.setTextColor(0xFFD32F2F.toInt())
            cameraButton.visibility = View.VISIBLE
        }
    }

    private fun launchCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val photoFile = createImageFile()
        if (photoFile != null) {
            val photoURI = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
            cameraLaunched = true
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_IMAGE_CAPTURE)
        }
    }

    private fun createImageFile(): File? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("PHOTO_${timeStamp}_", ".jpg", storageDir).also {
            currentPhotoPath = it.absolutePath
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        cameraLaunched = false

        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            val path = currentPhotoPath
            if (path != null && path != lastEnqueuedPath) {
                val file = File(path)
                if (file.exists() && file.length() > 0) {
                    lastEnqueuedPath = path
                    imageView.setImageURI(Uri.fromFile(file))
                    imageView.visibility = View.VISIBLE
                    titleText.visibility = View.GONE
                    enqueuePrint(file)
                } else {
                    statusText.text = "사진 저장 실패"
                    statusText.setTextColor(0xFFD32F2F.toInt())
                }
            }
        } else if (requestCode == REQUEST_IMAGE_CAPTURE) {
            statusText.text = "촬영이 취소되었습니다"
            statusText.setTextColor(0xFF757575.toInt())
        }
        cameraButton.visibility = View.VISIBLE
    }

    private fun enqueuePrint(file: File) {
        statusText.text = "프린트 접수 완료!"
        statusText.setTextColor(0xFF388E3C.toInt())
        cameraButton.visibility = View.VISIBLE
        cameraButton.text = "다시 촬영"

        Toast.makeText(this, "백그라운드에서 A4 프린트 전송 중...", Toast.LENGTH_SHORT).show()

        appScope.launch {
            val logId = dao.insert(
                PrintLog(
                    filePath = file.absolutePath,
                    fileName = file.name,
                    status = PrintLog.STATUS_PENDING
                )
            )

            val workRequest = OneTimeWorkRequestBuilder<PrintWorker>()
                .setInputData(
                    workDataOf(
                        PrintWorker.KEY_LOG_ID to logId,
                        PrintWorker.KEY_FILE_PATH to file.absolutePath
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork("print_${logId}", ExistingWorkPolicy.KEEP, workRequest)

            withContext(Dispatchers.Main) {
                if (!isFinishing) updateLogCount()
            }
        }
    }
}
