/*
 * Copyright 2023 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Holds only non-traffic data: cached IP metadata (used by WireGuard/endpoint screens) and the
 * app event log. Connection, DNS and console logs are no longer persisted; their tables are
 * dropped by [MIGRATION_15_16].
 */
@Database(
    entities = [IpInfo::class, Event::class],
    version = 16,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class LogDatabase : RoomDatabase() {

    companion object {
        const val LOGS_DATABASE_NAME = "rethink_logs.db"
        private const val PRAGMA = "pragma wal_checkpoint(full)"

        // Legacy log tables removed in v16. Connection/DNS traffic must not be stored on disk.
        private val LEGACY_LOG_TABLES =
            listOf("ConnectionTracker", "DnsLogs", "DNSLogs", "RethinkLog")

        @Suppress("DEPRECATION")
        fun buildDatabase(context: Context): LogDatabase {
            return Room.databaseBuilder(
                    context.applicationContext,
                    LogDatabase::class.java,
                    LOGS_DATABASE_NAME
                )
                .setJournalMode(JournalMode.AUTOMATIC)
                .addMigrations(MIGRATION_15_16)
                .fallbackToDestructiveMigration() // recreate the database if no migration is found
                .build()
        }

        private val MIGRATION_15_16: Migration =
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    LEGACY_LOG_TABLES.forEach { table ->
                        db.execSQL("DROP TABLE IF EXISTS `$table`")
                    }
                }
            }
    }

    abstract fun ipInfoDao(): IpInfoDAO

    abstract fun eventDao(): EventDao

    fun ipInfoRepository() = IpInfoRepository(ipInfoDao())

    // flush the WAL into the main db file; used before restoring a backup
    fun checkPoint() {
        openHelper.writableDatabase.query(SimpleSQLiteQuery(PRAGMA)).use { it.moveToFirst() }
    }
}
