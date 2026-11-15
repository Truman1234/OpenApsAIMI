package app.aaps.pump.dana.database

/**
 * Stores one Dana history record.
 *
 * The commonMain counterpart of `DanaHistoryRecordDao` (androidMain only, Room). The Android
 * implementation converts [DanaHistoryRecordData] to the Room entity `DanaHistoryRecord` and writes
 * it with the existing DAO; other platforms can implement this directly once they have storage.
 */
interface DanaHistoryRecordStore {

    fun createOrUpdate(record: DanaHistoryRecordData)
}
