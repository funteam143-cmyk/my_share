package com.example.data.db

import kotlinx.coroutines.flow.Flow

class TransferRepository(private val dao: TransferRecordDao) {
    val allRecords: Flow<List<TransferRecord>> = dao.getAllRecords()
    val recentRecords: Flow<List<TransferRecord>> = dao.getRecentRecords()

    suspend fun saveRecord(record: TransferRecord): Long = dao.insertRecord(record)
    suspend fun deleteRecord(id: Long) = dao.deleteRecord(id)
    suspend fun clearHistory() = dao.clearAllRecords()
}
