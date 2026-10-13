package com.mpx.infra_manager_java.controller.admin;

// ============================================================
// AI版本  : Claude Opus 5.5 (claude-opus-5-5)
// 修改日期: 2026-10-13
// 變更說明: 新增：admin 寄信紀錄 API（S8b R4）。
//           GET  /api/admin/mail               列表（status／from／to／q／page；預設近 7 天、每頁 20、不回 HTML）
//           GET  /api/admin/mail/settings      測試頁顯示的寄信設定（主機、port、寄件人、開關、改寄地址；無密碼）
//           GET  /api/admin/mail/{id}          單筆（含 HTML、收件人、錯誤、meta）
//           POST /api/admin/mail/{id}/resend   重寄（只限 FAILED，否則 409）→ 204
//           POST /api/admin/mail/test          測試寄信 {to, template, appId} → {mailId, status, error}
//           權限在類別層 @PreAuthorize("hasRole('admin')")；登入與預設密碼閘門照 SecurityConfig 的 /api/** 規則，
//           刻意不在 SecurityConfig 另加 /api/admin/** URL 規則（寫在 /api/** 前面會讓預設密碼未改的 admin 繞過 PWD_OK 閘門）
// ============================================================

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mpx.infra_manager_java.model.auth.AuthUser;
import com.mpx.infra_manager_java.model.mail.MailDetail;
import com.mpx.infra_manager_java.model.mail.MailListQuery;
import com.mpx.infra_manager_java.model.mail.MailListResponse;
import com.mpx.infra_manager_java.model.mail.MailSettings;
import com.mpx.infra_manager_java.model.mail.MailTestRequest;
import com.mpx.infra_manager_java.model.mail.MailTestResult;
import com.mpx.infra_manager_java.service.mail.AdminMailService;

@RestController
@RequestMapping("/api/admin/mail")
@PreAuthorize("hasRole('admin')")
public class AdminMailController {

	private final AdminMailService adminMailService;

	public AdminMailController(AdminMailService adminMailService) {
		this.adminMailService = adminMailService;
	}

	@GetMapping
	public MailListResponse list(@RequestParam(required = false) String status,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page) {
		return adminMailService.list(new MailListQuery(status, from, to, q, page));
	}

	@GetMapping("/settings")
	public MailSettings settings() {
		return adminMailService.settings();
	}

	@GetMapping("/{id}")
	public MailDetail detail(@PathVariable long id) {
		return adminMailService.detail(id);
	}

	@PostMapping("/{id}/resend")
	public ResponseEntity<Void> resend(@PathVariable long id, Authentication authentication) {
		adminMailService.resend(id, principal(authentication));
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/test")
	public MailTestResult test(@RequestBody MailTestRequest request, Authentication authentication) {
		return adminMailService.sendTest(request, principal(authentication));
	}

	private static AuthUser principal(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthUser user)) {
			throw new AuthenticationCredentialsNotFoundException("尚未登入");
		}
		return user;
	}
}
