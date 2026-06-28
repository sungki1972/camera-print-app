package com.echeil.cameraprint.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "print_logs")
data class PrintLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val fileName: String,
    val status: String = STATUS_PENDING,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_UPLOADING = "uploading"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
    }
}
