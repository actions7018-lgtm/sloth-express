/*
 * Copyright © 2026 actionsk
 * SPDX-License-Identifier: MPL-2.0
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.parcelhub.autoquery

/**
 * 自动查询任务状态（参考《快递智能识别 → 菜鸟自动填单号》SOP §9 / §25）。
 *
 * 流转主链：
 * ```
 * PENDING → LAUNCHING → CAINIAO_OPENED → WAITING_AUTOMATION
 *          → INPUT_FOUND → NUMBER_FILLED → COMPLETED
 * ```
 * 任意活跃态可进入 [FAILED]（异常后停止，SOP §31）或 [CANCELLED]（SOP §10.2 延迟二次判断取消）。
 */
enum class QueryTaskStatus {
    /** 已创建，尚未发起启动 */
    PENDING,

    /** 正在启动菜鸟 */
    LAUNCHING,

    /** 菜鸟已打开（SOP §11.4：Opened 只代表启动成功，不等于查询成功） */
    CAINIAO_OPENED,

    /** 等待无障碍服务识别菜鸟页面 */
    WAITING_AUTOMATION,

    /** 已定位输入框 */
    INPUT_FOUND,

    /** 单号已填入并验证通过 */
    NUMBER_FILLED,

    /** 任务完成（第一版止步于此，用户自行点击“查询”，SOP §23） */
    COMPLETED,

    /** 异常终止（SOP §31） */
    FAILED,

    /** 延迟二次判断后取消（SOP §10.2） */
    CANCELLED,
}
