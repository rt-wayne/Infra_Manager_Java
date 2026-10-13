package com.mpx.infra_manager_java.model.mail;

// ============================================================
// AI版本  : Claude Opus 5.5 (claude-opus-5-5)
// 修改日期: 2026-10-13
// 變更說明: 新增：admin 寄信紀錄列表的查詢條件（S8b R4）。由 AdminMailService 正規化後交給 MailOutboxDao：
//           status 為 null 表示不篩；q 已 trim（比對主旨）；from／to 為含頭含尾的建立日期範圍（台灣日期）；
//           page 從 1 起算，每頁固定 PAGE_SIZE 筆（前端規範 20 列／頁）
// ============================================================

import java.time.LocalDate;

public record MailListQuery(String status, LocalDate from, LocalDate to, String q, int page) {

	public static final int PAGE_SIZE = 20;

	public int offset() {
		return (page - 1) * PAGE_SIZE;
	}
}
