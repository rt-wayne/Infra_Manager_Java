package com.mpx.infra_manager_java.model.mail;

// ============================================================
// AI版本  : Claude Opus 5.5 (claude-opus-5-5)
// 修改日期: 2026-10-13
// 變更說明: 新增：admin 測試寄信的結果（S8b R4）。mailId＝寫進 outbox 的主鍵（可到寄信紀錄看內容）；
//           status＝同步寄一次之後的狀態（SENT／PENDING／FAILED）；error＝失敗原因（成功時 null；
//           SMTP 回應已遮掉 email 形狀字串）
// ============================================================

public record MailTestResult(long mailId, String status, String error) {
}
