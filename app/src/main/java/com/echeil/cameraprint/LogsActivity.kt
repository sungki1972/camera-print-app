package com.echeil.cameraprint

import android.app.AlertDialog
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import java.util.concurrent.TimeUnit

class LogsActivity : AppCompatActivity() {

    companion object {
        private const val PAGE_SIZE = 10
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var pageInfo: TextView
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var adapter: LogAdapter

    private val dao by lazy { AppDatabase.getInstance(this).printLogDao() }
    private val appScope get() = (application as App).appScope
    private var currentPage = 0
    private var totalCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "프린트 기록"

        recyclerView = findViewById(R.id.recyclerView)
        emptyText = findViewById(R.id.emptyText)
        pageInfo = findViewById(R.id.pageInfo)
        prevButton = findViewById(R.id.prevButton)
        nextButton = findViewById(R.id.nextButton)

        adapter = LogAdapter(
            onDelete = { log -> confirmDelete(log) },
            onRetry = { log -> retryPrint(log) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        prevButton.setOnClickListener {
            if (currentPage > 0) {
                currentPage--
                loadPage()
            }
        }
        nextButton.setOnClickListener {
            val maxPage = (totalCount - 1) / PAGE_SIZE
            if (currentPage < maxPage) {
                currentPage++
                loadPage()
            }
        }

        loadPage()
    }

    override fun onResume() {
        super.onResume()
        loadPage()
    }

    private fun loadPage() {
        appScope.launch {
            totalCount = dao.getCount()
            val offset = currentPage * PAGE_SIZE
            val logs = dao.getPage(PAGE_SIZE, offset)
            val maxPage = if (totalCount > 0) (totalCount - 1) / PAGE_SIZE else 0

            withContext(Dispatchers.Main) {
                if (isFinishing) return@withContext
                adapter.submitList(logs)

                if (totalCount == 0) {
                    emptyText.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                    pageInfo.text = "기록 없음"
                    prevButton.isEnabled = false
                    nextButton.isEnabled = false
                } else {
                    emptyText.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    pageInfo.text = "${currentPage + 1} / ${maxPage + 1} 페이지 (총 ${totalCount}건)"
                    prevButton.isEnabled = currentPage > 0
                    nextButton.isEnabled = currentPage < maxPage
                }
            }
        }
    }

    private fun confirmDelete(log: PrintLog) {
        AlertDialog.Builder(this)
            .setTitle("삭제")
            .setMessage("이 기록을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ -> deleteLog(log) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteLog(log: PrintLog) {
        appScope.launch {
            WorkManager.getInstance(applicationContext).cancelUniqueWork("print_${log.id}")
            dao.delete(log)
            SupabaseSync.deleteRemote(applicationContext, log.id)
            try { File(log.filePath).delete() } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                if (!isFinishing) {
                    Toast.makeText(this@LogsActivity, "삭제됨", Toast.LENGTH_SHORT).show()
                    loadPage()
                }
            }
        }
    }

    // BUG-F FIX: dao.updateStatus(PENDING) 제거 → 비원자적 갭 제거
    // claimJob이 failed 상태를 직접 수용하므로 status 변경 불필요
    // REPLACE 정책으로 기존 작업 교체 — rescue와의 경합 원천 차단
    private fun retryPrint(log: PrintLog) {
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

            WorkManager.getInstance(this@LogsActivity)
                .enqueueUniqueWork("print_${log.id}", ExistingWorkPolicy.REPLACE, workRequest)

            withContext(Dispatchers.Main) {
                if (!isFinishing) {
                    Toast.makeText(this@LogsActivity, "재전송 접수됨", Toast.LENGTH_SHORT).show()
                    loadPage()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "전체 삭제").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 2, 0, "새로고침").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            1 -> { confirmDeleteAll(); true }
            2 -> { loadPage(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle("전체 삭제")
            .setMessage("모든 프린트 기록을 삭제하시겠습니까?")
            .setPositiveButton("전체 삭제") { _, _ ->
                appScope.launch {
                    WorkManager.getInstance(applicationContext).cancelAllWork()
                    dao.deleteAll()
                    SupabaseSync.deleteAllForDevice(applicationContext)
                    currentPage = 0
                    withContext(Dispatchers.Main) {
                        if (!isFinishing) {
                            Toast.makeText(this@LogsActivity, "전체 삭제 완료", Toast.LENGTH_SHORT).show()
                            loadPage()
                        }
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
