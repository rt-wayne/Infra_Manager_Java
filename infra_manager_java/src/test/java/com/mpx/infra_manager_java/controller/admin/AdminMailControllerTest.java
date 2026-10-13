package com.mpx.infra_manager_java.controller.admin;

// ============================================================
// AI版本  : Claude Opus 5.5 (claude-opus-5-5)
// 修改日期: 2026-10-13
// 變更說明: 新增：/api/admin/mail 的 MockMvc 測試（S8b R4）。比照 TemplateControllerTest 走真的登入流程。
//           鎖定：未登入 401；非 admin 403 且不呼叫服務；admin 但預設密碼未改 403「請先修改預設密碼」；
//           列表 200 且 JSON 欄位名、查詢參數原樣交給服務；重寄 204、非 FAILED 409、缺 CSRF 403 且不呼叫服務；
//           測試寄信 200 回 mailId／status／error 且服務收到登入者
// ============================================================

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.mpx.infra_manager_java.config.SecurityConfig;
import com.mpx.infra_manager_java.controller.auth.AuthController;
import com.mpx.infra_manager_java.dao.auth.UserDao;
import com.mpx.infra_manager_java.model.auth.AuthUser;
import com.mpx.infra_manager_java.model.auth.UserRow;
import com.mpx.infra_manager_java.model.mail.MailListItem;
import com.mpx.infra_manager_java.model.mail.MailListQuery;
import com.mpx.infra_manager_java.model.mail.MailListResponse;
import com.mpx.infra_manager_java.model.mail.MailTestRequest;
import com.mpx.infra_manager_java.model.mail.MailTestResult;
import com.mpx.infra_manager_java.service.auth.AuthService;
import com.mpx.infra_manager_java.service.mail.AdminMailService;
import com.mpx.infra_manager_java.web.ApiConflictException;

import jakarta.servlet.http.Cookie;

@WebMvcTest(controllers = { AdminMailController.class, AuthController.class })
@Import({ SecurityConfig.class, AuthService.class })
class AdminMailControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PasswordEncoder encoder;

	@MockitoBean
	private UserDao userDao;

	@MockitoBean
	private AdminMailService adminMailService;

	private record Session(MockHttpSession session, Cookie xsrf) {
	}

	private Session login(String role, int isDfltPwd) throws Exception {
		UserRow row = new UserRow();
		row.setUserId("E0001");
		row.setLoginId("wayne");
		row.setUserName("測試人員");
		row.setPwdHash(encoder.encode("secret"));
		row.setStatus(1);
		row.setIsDfltPwd(isDfltPwd);
		when(userDao.findByLoginId("wayne")).thenReturn(Optional.of(row));
		when(userDao.findActiveRoleIds("E0001")).thenReturn(List.of(role));

		MvcResult me = mockMvc.perform(get("/api/auth/me")).andExpect(status().isOk()).andReturn();
		Cookie xsrf = me.getResponse().getCookie(SecurityConfig.XSRF_COOKIE);
		MvcResult result = mockMvc.perform(post("/api/auth/login").cookie(xsrf)
				.header(SecurityConfig.XSRF_HEADER, xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"loginId\":\"wayne\",\"password\":\"secret\"}")).andExpect(status().isOk()).andReturn();
		MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
		Cookie rotated = result.getResponse().getCookie(SecurityConfig.XSRF_COOKIE);
		return new Session(session, rotated != null && !rotated.getValue().isEmpty() ? rotated : xsrf);
	}

	private Session admin() throws Exception {
		return login("admin", 0);
	}

	@Test
	void 未登入_401() throws Exception {
		mockMvc.perform(get("/api/admin/mail")).andExpect(status().isUnauthorized());
		verifyNoInteractions(adminMailService);
	}

	@Test
	void 非admin_403且不呼叫服務() throws Exception {
		Session s = login("infra", 0);

		mockMvc.perform(get("/api/admin/mail").session(s.session())).andExpect(status().isForbidden());
		mockMvc.perform(post("/api/admin/mail/5/resend").session(s.session()).cookie(s.xsrf())
				.header(SecurityConfig.XSRF_HEADER, s.xsrf().getValue())).andExpect(status().isForbidden());
		verifyNoInteractions(adminMailService);
	}

	@Test
	void admin但預設密碼未改_403() throws Exception {
		Session s = login("admin", 1);

		mockMvc.perform(get("/api/admin/mail").session(s.session())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(SecurityConfig.MSG_MUST_CHANGE_PWD));
		verifyNoInteractions(adminMailService);
	}

	@Test
	void 列表_200且查詢參數原樣交給服務() throws Exception {
		Session s = admin();
		when(adminMailService.list(any())).thenReturn(new MailListResponse(List.of(new MailListItem(5L,
				"[待簽核] IM2026100001 汰換 - 機房管理員", "FAILED", 5, "IM2026100001", "step-pending", 2,
				"2026-10-13 09:30", null, "2026-10-13 10:00", true)), 1L, 2, 20));

		mockMvc.perform(get("/api/admin/mail").session(s.session()).param("status", "FAILED")
				.param("from", "2026-10-01").param("to", "2026-10-13").param("q", "汰換").param("page", "2"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.items[0].mailId").value(5))
				.andExpect(jsonPath("$.items[0].appId").value("IM2026100001"))
				.andExpect(jsonPath("$.items[0].toCount").value(2))
				.andExpect(jsonPath("$.items[0].hasError").value(true));

		ArgumentCaptor<MailListQuery> q = ArgumentCaptor.forClass(MailListQuery.class);
		verify(adminMailService).list(q.capture());
		assertThat(q.getValue()).isEqualTo(
				new MailListQuery("FAILED", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 13), "汰換", 2));
	}

	@Test
	void 重寄_204_非FAILED_409() throws Exception {
		Session s = admin();
		doThrow(new ApiConflictException(AdminMailService.MSG_RESEND_NOT_FAILED)).when(adminMailService)
				.resend(eq(6L), any());

		mockMvc.perform(post("/api/admin/mail/5/resend").session(s.session()).cookie(s.xsrf())
				.header(SecurityConfig.XSRF_HEADER, s.xsrf().getValue())).andExpect(status().isNoContent());
		mockMvc.perform(post("/api/admin/mail/6/resend").session(s.session()).cookie(s.xsrf())
				.header(SecurityConfig.XSRF_HEADER, s.xsrf().getValue())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(AdminMailService.MSG_RESEND_NOT_FAILED));

		ArgumentCaptor<AuthUser> user = ArgumentCaptor.forClass(AuthUser.class);
		verify(adminMailService).resend(eq(5L), user.capture());
		assertThat(user.getValue().userId()).isEqualTo("E0001");
	}

	@Test
	void 重寄缺CSRF_403且不呼叫服務() throws Exception {
		Session s = admin();

		mockMvc.perform(post("/api/admin/mail/5/resend").session(s.session())).andExpect(status().isForbidden());
		verifyNoInteractions(adminMailService);
	}

	@Test
	void 測試寄信_200回結果且服務收到登入者() throws Exception {
		Session s = admin();
		when(adminMailService.sendTest(any(), any())).thenReturn(new MailTestResult(42L, "SENT", null));

		mockMvc.perform(post("/api/admin/mail/test").session(s.session()).cookie(s.xsrf())
				.header(SecurityConfig.XSRF_HEADER, s.xsrf().getValue()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"to\":\"me@pxmart.com.tw\",\"template\":\"approved\",\"appId\":\"IM2026100001\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.mailId").value(42))
				.andExpect(jsonPath("$.status").value("SENT"));

		ArgumentCaptor<MailTestRequest> req = ArgumentCaptor.forClass(MailTestRequest.class);
		ArgumentCaptor<AuthUser> user = ArgumentCaptor.forClass(AuthUser.class);
		verify(adminMailService).sendTest(req.capture(), user.capture());
		assertThat(req.getValue()).isEqualTo(new MailTestRequest("me@pxmart.com.tw", "approved", "IM2026100001"));
		assertThat(user.getValue().userId()).isEqualTo("E0001");
	}
}
