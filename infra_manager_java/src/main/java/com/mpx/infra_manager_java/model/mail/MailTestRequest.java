package com.mpx.infra_manager_java.model.mail;

// ============================================================
// AI版本  : Claude Opus 5.5 (claude-opus-5-5)
// 修改日期: 2026-10-13
// 變更說明: 新增：admin 測試寄信的請求本文（S8b R4）。to＝收件地址；template＝step-pending／rejected／approved
//           （照舊系統測試頁的三種）；appId＝拿哪張申請單的資料組信（必填，與舊系統一致）
// ============================================================

public record MailTestRequest(String to, String template, String appId) {
}
