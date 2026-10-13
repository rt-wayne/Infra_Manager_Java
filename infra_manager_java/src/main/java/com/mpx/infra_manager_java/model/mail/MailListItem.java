package com.mpx.infra_manager_java.model.mail;

// ============================================================
// AI版本  : Claude Opus 5.5 (claude-opus-5-5)
// 修改日期: 2026-10-13
// 變更說明: 新增：admin 寄信紀錄列表一列（S8b R4）。不含信件 HTML 與收件人地址（列表只給人數；地址與內容看單筆）；
//           appId／event 取自 META_JSON，沒有時為 null；時間欄位為台灣時間 yyyy-MM-dd HH:mm
// ============================================================

public record MailListItem(
		long mailId,
		String subject,
		String status,
		int tryCnt,
		String appId,
		String event,
		int toCount,
		String createdAt,
		String sentAt,
		String updatedAt,
		boolean hasError) {
}
