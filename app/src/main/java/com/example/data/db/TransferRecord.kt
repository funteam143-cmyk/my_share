package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfer_records")
data class TransferRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val peerName: String,
    val isOutgoing: Boolean,
    val fileCount: Int,
    val totalBytes: Long,
    val averageSpeedMBps: Double,
    val peakSpeedMBps: Double,
    val durationSeconds: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String, // "COMPLETED", "FAILED", "CANCELLED"
    val fileNamesSummary: String
)
