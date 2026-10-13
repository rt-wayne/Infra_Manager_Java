package com.mpx.infra_manager_java.model.mail;

// ============================================================
// AI版本  : Claude Opus 5.5 (claude-opus-5-5)
// 修改日期: 2026-10-13
// 變更說明: 新增：admin 測試寄信頁顯示的寄信設定（S8b R4）。只有主機、port、寄件人、排程開關、改寄地址、
//           信內連結網址；不含任何帳號密碼。configured＝主機與寄件人都有值（測試寄信才會真的送出）
// ============================================================

public record MailSettings(boolean enabled, boolean configured, String host, int port, String from,
		String overrideTo, String siteUrl) {
}
