package com.mpx.infra_manager_java.model.mail;

// ============================================================
// AI版本  : Claude Opus 5.5 (claude-opus-5-5)
// 修改日期: 2026-10-13
// 變更說明: 新增：admin 寄信紀錄列表回應（S8b R4）。total 是套用篩選後的總筆數
// ============================================================

import java.util.List;

public record MailListResponse(List<MailListItem> items, long total, int page, int size) {
}
