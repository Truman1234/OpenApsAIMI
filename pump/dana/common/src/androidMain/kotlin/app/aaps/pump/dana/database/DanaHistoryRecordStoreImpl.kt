package app.aaps.pump.dana.database

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

@Inject
@ContributesBinding(AppScope::class)
class DanaHistoryRecordStoreImpl(
    private val danaHistoryRecordDao: DanaHistoryRecordDao
) : DanaHistoryRecordStore {

    override fun createOrUpdate(record: DanaHistoryRecordData) {
        danaHistoryRecordDao.createOrUpdate(
            DanaHistoryRecord(
                timestamp = record.timestamp,
                code = record.code,
                value = record.value,
                bolusType = record.bolusType,
                stringValue = record.stringValue,
                duration = record.duration,
                dailyBasal = record.dailyBasal,
                dailyBolus = record.dailyBolus,
                alarm = record.alarm
            )
        )
    }
}
