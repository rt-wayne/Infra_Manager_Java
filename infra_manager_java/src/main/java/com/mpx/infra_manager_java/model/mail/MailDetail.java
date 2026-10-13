package com.mpx.infra_manager_java.model.mail;

// ============================================================
// AI版本  : Claude Opus 5.5 (claude-opus-5-5)
// 修改日期: 2026-10-13
// 變更說明: 新增：admin 寄信紀錄單筆（S8b R4）。含信件 HTML（前端放進 sandbox iframe 顯示）、實際收件人、
//           改寄前的原收件人（META_JSON 的 originalTo／Cc／Bcc，沒改寄時為空清單）、錯誤文字與 meta 原樣。
//           canResend＝目前是 FAILED、可按重寄
// ============================================================

import java.util.List;
import java.util.Map;

public record MailDetail(
		long mailId,
		String subject,
		String status,
		int tryCnt,
		List<String> to,
		List<String> cc,
		List<String> bcc,
		List<String> originalTo,
		List<String> originalCc,
		List<String> originalBcc,
		String errorText,
		String smtpMsgId,
		String appId,
		String event,
		Map<String, Object> meta,
		String html,
		String createdAt,
		String createdBy,
		String sentAt,
		String updatedAt,
		String updatedBy,
		boolean canResend) {
}
