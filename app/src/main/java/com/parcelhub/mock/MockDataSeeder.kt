/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.mock

import android.content.Context
import android.provider.Settings
import com.parcelhub.BuildConfig
import com.parcelhub.data.db.AppDatabase
import com.parcelhub.util.AppLog

/**
 * Mock 测试数据注入器（规格 §10）——只在 Debug 生效，Release 双重拦截。
 *
 * 三条硬规则：
 *  1. **Release 禁止注入**：[BuildConfig.DEBUG] 不满足直接返回（seed / seedIfNeeded / clear 全拦）；
 *  2. **不改数据库结构**：只写现有 shipments / pending_shipments 两张表，主键固定 900001… / 910001…；
 *  3. **重复执行不产生重复数据**：固定主键 + 先查后插；清空按 `LIKE 'MOCK%'` 精确删 Mock 行。
 *
 * 触发方式：
 *  - 自动：Debug 启动 [seedIfNeeded]（[SUPPRESS_SETTING]=0 时抑制——冒烟脚本靠它保住绝对计数断言）；
 *  - 手动：adb 广播 [MockDebugReceiver]（SEED / CLEAR / RESEED 一键清空与重新生成）。
 */
object MockDataSeeder {

    /** Settings.Global 开关：0 = 抑制 Debug 启动自动注入（冒烟脚本设置），缺省 1 = 允许 */
    const val SUPPRESS_SETTING = "parcelhub_mock_seed"

    /**
     * Debug 启动自动注入入口。idempotent：
     * 抑制开关打开时跳过；否则等价于 [seed]（先查后插，重复启动不重复建行）。
     */
    suspend fun seedIfNeeded(context: Context) {
        if (!BuildConfig.DEBUG) return
        val suppressed = Settings.Global.getInt(
            context.contentResolver,
            SUPPRESS_SETTING,
            1,
        ) == 0
        if (suppressed) return
        seed(context)
    }

    /**
     * 注入 50 条标准 + 1 条 72h 边界数据（显式入口，不受抑制开关影响）。
     * @return 本次真正新插入的包裹条数（已存在=0，可判断是否重复执行）
     */
    suspend fun seed(context: Context): Int {
        if (!BuildConfig.DEBUG) return 0
        val db = AppDatabase.get(context)
        val rows = MockParcelDataProvider.parcels(System.currentTimeMillis())
        var inserted = 0
        for (p in rows) {
            if (db.shipmentDao().findById(p.id) == null) {
                db.shipmentDao().insert(MockParcelDataProvider.toShipmentEntity(p))
                inserted++
            }
            val pending = MockParcelDataProvider.toPendingEntity(p)
            if (pending != null && db.pendingShipmentDao().findById(pending.id) == null) {
                db.pendingShipmentDao().insert(pending)
            }
        }
        AppLog.d("mock seed: inserted=$inserted of ${rows.size}")
        return inserted
    }

    /**
     * 一键清空 Mock 数据：按来源/订单键前缀删除，不碰任何真实数据。
     * @return 被清空的包裹条数
     */
    suspend fun clear(context: Context): Int {
        if (!BuildConfig.DEBUG) return 0
        val db = AppDatabase.get(context)
        val pendingCleared = db.pendingShipmentDao().deleteMock()
        val cleared = db.shipmentDao().deleteMock()
        AppLog.d("mock clear: shipments=$cleared pending=$pendingCleared")
        return cleared
    }
}
