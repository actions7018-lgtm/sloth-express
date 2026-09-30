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
import com.parcelhub.data.entity.PendingShipmentEntity
import com.parcelhub.data.entity.QueryTaskEntity
import com.parcelhub.data.entity.RuleEntity
import com.parcelhub.data.entity.ShipmentEntity
import com.parcelhub.data.entity.SourceAppEntity

/**
 * Room 数据库（SOP §10）。
 * 保存 Shipment / ParcelEvent / source_apps / parser_rules / query_tasks /
 * pending_shipments（SOP §21 待补全），不保存无关通知。
 */
@Database(
    entities = [
        ShipmentEntity::class,
        ParcelEventEntity::class,
        SourceAppEntity::class,
        RuleEntity::class,
        QueryTaskEntity::class,
        PendingShipmentEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun shipmentDao(): ShipmentDao
    abstract fun parcelEventDao(): ParcelEventDao
    abstract fun sourceDao(): SourceDao
    abstract fun ruleDao(): RuleDao
    abstract fun queryTaskDao(): QueryTaskDao
    abstract fun pendingShipmentDao(): PendingShipmentDao

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

        /** v3 → v4：新增待补全订单表（SOP §21 ShipmentPending），保留既有数据 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_shipments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `order_key` TEXT,
                        `platform` TEXT,
                        `shipment_id` INTEGER,
                        `shipped_at` INTEGER NOT NULL,
                        `tracking_number` TEXT,
                        `carrier` TEXT,
                        `status` TEXT NOT NULL,
                        `last_check_at` INTEGER,
                        `next_check_at` INTEGER,
                        `expire_at` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_shipments_status` " +
                        "ON `pending_shipments` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_shipments_shipped_at` " +
                        "ON `pending_shipments` (`shipped_at`)",
                )
            }
        }

        /** v4 → v5：待补全订单加来源识别与商品/商家列（需求 §二/§五/§十），保留既有数据 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pending_shipments` ADD COLUMN `source_type` TEXT")
                db.execSQL("ALTER TABLE `pending_shipments` ADD COLUMN `source_package_name` TEXT")
                db.execSQL("ALTER TABLE `pending_shipments` ADD COLUMN `source_app_name` TEXT")
                db.execSQL("ALTER TABLE `pending_shipments` ADD COLUMN `product_title` TEXT")
                db.execSQL("ALTER TABLE `pending_shipments` ADD COLUMN `seller_name` TEXT")
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()

        /** 仅测试使用 */
        fun setInstanceForTest(db: AppDatabase?) {
            instance = db
        }
    }
}
