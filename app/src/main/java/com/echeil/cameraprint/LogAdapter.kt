package com.echeil.cameraprint

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.echeil.cameraprint.data.PrintLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter(
    private val onDelete: (PrintLog) -> Unit,
    private val onRetry: (PrintLog) -> Unit
) : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private var items: List<PrintLog> = emptyList()

    fun submitList(list: List<PrintLog>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumbnail: ImageView = view.findViewById(R.id.thumbnail)
        private val fileName: TextView = view.findViewById(R.id.fileName)
        private val dateText: TextView = view.findViewById(R.id.dateText)
        private val statusText: TextView = view.findViewById(R.id.statusBadge)
        private val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
        private val retryButton: ImageButton = view.findViewById(R.id.retryButton)

        private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

        fun bind(log: PrintLog) {
            fileName.text = log.fileName
            dateText.text = dateFormat.format(Date(log.createdAt))

            // thumbnail
            val file = File(log.filePath)
            if (file.exists()) {
                val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                val bmp = BitmapFactory.decodeFile(log.filePath, options)
                thumbnail.setImageBitmap(bmp)
            } else {
                thumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            // status badge
            when (log.status) {
                PrintLog.STATUS_PENDING -> {
                    statusText.text = "대기"
                    statusText.setBackgroundColor(0xFF9E9E9E.toInt())
                }
                PrintLog.STATUS_UPLOADING -> {
                    statusText.text = "전송중"
                    statusText.setBackgroundColor(0xFF1976D2.toInt())
                }
                PrintLog.STATUS_SUCCESS -> {
                    statusText.text = "완료"
                    statusText.setBackgroundColor(0xFF388E3C.toInt())
                }
                PrintLog.STATUS_FAILED -> {
                    statusText.text = "실패"
                    statusText.setBackgroundColor(0xFFD32F2F.toInt())
                }
            }

            // retry visible only for failed
            retryButton.visibility = if (log.status == PrintLog.STATUS_FAILED) View.VISIBLE else View.GONE

            deleteButton.setOnClickListener { onDelete(log) }
            retryButton.setOnClickListener { onRetry(log) }
        }
    }
}
