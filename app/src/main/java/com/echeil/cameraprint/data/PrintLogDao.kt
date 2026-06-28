package com.echeil.cameraprint.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface PrintLogDao {

    @Insert
    suspend fun insert(log: PrintLog): Long

    @Update
    suspend fun update(log: PrintLog)

    @Delete
    suspend fun delete(log: PrintLog)

    @Query("SELECT * FROM print_logs ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<PrintLog>

    @Query("SELECT COUNT(*) FROM print_logs")
    suspend fun getCount(): Int

    @Query("SELECT * FROM print_logs WHERE id = :id")
    suspend fun getById(id: Long): PrintLog?

    @Query("UPDATE print_logs SET status = :status, errorMessage = :error, completedAt = :completedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String? = null, completedAt: Long? = null)

    @Query("SELECT * FROM print_logs WHERE status IN (:statuses)")
    suspend fun getByStatuses(statuses: List<String>): List<PrintLog>

    // BUG1+2 FIX: 원자적 작업 점유 — status가 pending/failed일 때만 uploading으로 전환
    // 이미 다른 Worker가 점유했으면 0 반환 → 중복 실행 차단
    @Query("UPDATE print_logs SET status = 'uploading' WHERE id = :id AND status IN ('pending', 'failed')")
    suspend fun claimJob(id: Long): Int

    // BUG1 FIX: 10분 이상 uploading 상태인 고아 작업을 pending으로 리셋
    @Query("UPDATE print_logs SET status = 'pending' WHERE status = 'uploading' AND createdAt < :olderThan")
    suspend fun resetStaleUploading(olderThan: Long): Int

    @Query("DELETE FROM print_logs")
    suspend fun deleteAll()
}
