/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.parcelhub.data.entity.ParcelEventEntity
import com.parcelhub.data.entity.QueryTaskEntity
import com.parcelhub.data.entity.RuleEntity
import com.parcelhub.data.entity.ShipmentEntity
import com.parcelhub.data.entity.SourceAppEntity

/**
 * Room 数据库（SOP §10）。
 * 保存 Shipment / ParcelEvent / source_apps / parser_rules / query_tasks，不保存无关通知。
 */
@Database(
    entities = [
        ShipmentEntity::class,
        ParcelEventEntity::class,
        SourceAppEntity::class,
        RuleEntity::class,
        QueryTaskEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun shipmentDao(): ShipmentDao
    abstract fun parcelEventDao(): ParcelEventDao
    abstract fun sourceDao(): SourceDao
    abstract fun ruleDao(): RuleDao
    abstract fun queryTaskDao(): QueryTaskDao

    companion object {
        const val NAME = "parcelhub.db"

        /** v1 → v2：新增自动查询任务表（参考 SOP §9 / §29），保留既有数据 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `query_tasks` (
                        `task_id` TEXT NOT NULL,
                        `shipment_id` INTEGER,
                        `tracking_number` TEXT NOT NULL,
                        `carrier` TEXT,
                        `status` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `last_action` TEXT,
                        `retry_count` INTEGER NOT NULL,
                        `failure_reason` TEXT,
                        PRIMARY KEY(`task_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_query_tasks_tracking_number` " +
                        "ON `query_tasks` (`tracking_number`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_query_tasks_status` " +
                        "ON `query_tasks` (`status`)",
                )
            }
        }

        /** v2 → v3：shipments 增加 picked_up_at（记录用户点「已取件」的时间），保留既有数据 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `shipments` ADD COLUMN `picked_up_at` INTEGER")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()

        /** 仅测试使用 */
        fun setInstanceForTest(db: AppDatabase?) {
            instance = db
        }
    }
}
