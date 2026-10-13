package com.mpx.infra_manager_java.service.mail;

// ============================================================
// AI版本  : Claude Opus 5.5 (claude-opus-5-5)
// 修改日期: 2026-10-13
// 變更說明: 新增：AdminMailService 的單元測試（S8b R4；DAO／notifier／worker／dispatcher 皆 mock）。鎖定：
//           列表預設近 7 天、狀態值不合 400、q 過長 400、起日晚於迄日 400、page<1 視為 1；
//           列表一列從 META_JSON 取單號與事件、只給收件人數；單筆找不到 404、改寄前原收件人取自 meta、FAILED 才 canResend；
//           重寄 FAILED 成功、非 FAILED 409、不存在 404；
//           測試寄信：地址／樣板／單號不合各自 400／400／404 且不寫 outbox，SMTP 未設定 409 不寫 outbox，
//           成功時 enqueue → 依 token 找主鍵 → sendNow → 回寄送後狀態；連不上回固定訊息
// ============================================================

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.mpx.infra_manager_java.config.MailProperties;
import com.mpx.infra_manager_java.dao.mail.MailOutboxDao;
import com.mpx.infra_manager_java.model.auth.AuthUser;
import com.mpx.infra_manager_java.model.mail.MailDetail;
import com.mpx.infra_manager_java.model.mail.MailListItem;
import com.mpx.infra_manager_java.model.mail.MailListQuery;
import com.mpx.infra_manager_java.model.mail.MailMessage;
import com.mpx.infra_manager_java.model.mail.MailOutboxRow;
import com.mpx.infra_manager_java.model.mail.MailTestRequest;
import com.mpx.infra_manager_java.model.mail.MailTestResult;
import com.mpx.infra_manager_java.util.TaiwanTime;
import com.mpx.infra_manager_java.web.ApiBadRequestException;
import com.mpx.infra_manager_java.web.ApiConflictException;
import com.mpx.infra_manager_java.web.ApiNotFoundException;

class AdminMailServiceTest {

	private static final AuthUser ADMIN = new AuthUser("A0001", "admin1", "管理員", List.of("admin"), false);
	private static final String APP = "IM2026100001";

	private final MailOutboxDao dao = mock(MailOutboxDao.class);
	private final MailOutboxService outboxService = mock(MailOutboxService.class);
	private final MailNotifier notifier = mock(MailNotifier.class);
	private final MailWorker worker = mock(MailWorker.class);
	private final MailDispatcher dispatcher = mock(MailDispatcher.class);
	private final MailProperties properties = new MailProperties();
	private AdminMailService service;

	@BeforeEach
	void setUp() {
		service = new AdminMailService(dao, outboxService, notifier, worker, dispatcher, properties, " mail.example.test ",
				25);
		when(dispatcher.isConfigured()).thenReturn(true);
	}

	private static MailOutboxRow row(long id, String status) {
		MailOutboxRow r = new MailOutboxRow();
		r.setMailOutboxId(id);
		r.setMailStatusCode(status);
		r.setTryCnt(1);
		r.setMailSubj("[待簽核] " + APP + " 汰換 - 機房管理員");
		r.setToJson("[\"a@pxmart.com.tw\",\"b@pxmart.com.tw\"]");
		r.setMetaJson("{\"event\":\"step-pending\",\"appId\":\"" + APP + "\",\"originalTo\":[\"x@pxmart.com.tw\"]}");
		r.setCreateDate(Timestamp.valueOf("2026-10-13 09:30:00"));
		r.setHtmlBody("<p>hi</p>");
		return r;
	}

	// ---------- 列表 ----------

	@Test
	void 列表沒給日期時預設近7天_page小於1視為1() {
		MailListQuery q = AdminMailService.normalize(new MailListQuery(" ", null, null, "  ", 0));

		assertThat(q.status()).isNull();
		assertThat(q.q()).isNull();
		assertThat(q.from()).isEqualTo(TaiwanTime.today().minusDays(7));
		assertThat(q.to()).isNull();
		assertThat(q.page()).isEqualTo(1);
	}

	@Test
	void 列表篩選值不合回400() {
		assertThatThrownBy(() -> AdminMailService.normalize(new MailListQuery("DONE", null, null, null, 1)))
				.isInstanceOf(ApiBadRequestException.class).hasMessage(AdminMailService.MSG_BAD_STATUS);
		assertThatThrownBy(() -> AdminMailService.normalize(new MailListQuery(null, null, null, "x".repeat(101), 1)))
				.isInstanceOf(ApiBadRequestException.class).hasMessage(AdminMailService.MSG_Q_TOO_LONG);
		assertThatThrownBy(() -> AdminMailService.normalize(
				new MailListQuery(null, LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 1), null, 1)))
				.isInstanceOf(ApiBadRequestException.class).hasMessage(AdminMailService.MSG_BAD_RANGE);
	}

	@Test
	void 列表一列從meta取單號事件且只給收件人數() {
		MailOutboxRow r = row(5L, MailOutboxDao.STATUS_FAILED);
		r.setErrorText("MailSendException: 550 ***");

		MailListItem item = AdminMailService.toListItem(r);

		assertThat(item.mailId()).isEqualTo(5L);
		assertThat(item.appId()).isEqualTo(APP);
		assertThat(item.event()).isEqualTo("step-pending");
		assertThat(item.toCount()).isEqualTo(2);
		assertThat(item.createdAt()).isEqualTo("2026-10-13 09:30");
		assertThat(item.sentAt()).isNull();
		assertThat(item.hasError()).isTrue();
	}

	@Test
	void 列表一列meta或收件人壞掉時不丟例外() {
		MailOutboxRow r = row(6L, MailOutboxDao.STATUS_SENT);
		r.setMetaJson("not json");
		r.setToJson("{");

		MailListItem item = AdminMailService.toListItem(r);

		assertThat(item.appId()).isNull();
		assertThat(item.toCount()).isZero();
		assertThat(item.hasError()).isFalse();
	}

	@Test
	void 列表交給DAO的是正規化後的條件() {
		when(dao.findList(any())).thenReturn(List.of(row(1L, MailOutboxDao.STATUS_SENT)));
		when(dao.countList(any())).thenReturn(41L);

		var res = service.list(new MailListQuery(MailOutboxDao.STATUS_SENT, null, null, " 汰換 ", 3));

		ArgumentCaptor<MailListQuery> captor = ArgumentCaptor.forClass(MailListQuery.class);
		verify(dao).findList(captor.capture());
		assertThat(captor.getValue().q()).isEqualTo("汰換");
		assertThat(res.total()).isEqualTo(41L);
		assertThat(res.page()).isEqualTo(3);
		assertThat(res.size()).isEqualTo(20);
		assertThat(res.items()).hasSize(1);
	}

	// ---------- 單筆 ----------

	@Test
	void 單筆找不到回404() {
		when(dao.findById(9L)).thenReturn(null);

		assertThatThrownBy(() -> service.detail(9L)).isInstanceOf(ApiNotFoundException.class)
				.hasMessage(AdminMailService.MSG_MAIL_NOT_FOUND);
	}

	@Test
	void 單筆帶HTML與改寄前原收件人_FAILED才可重寄() {
		when(dao.findById(5L)).thenReturn(row(5L, MailOutboxDao.STATUS_FAILED));
		when(dao.findById(6L)).thenReturn(row(6L, MailOutboxDao.STATUS_SENT));

		MailDetail d = service.detail(5L);

		assertThat(d.to()).containsExactly("a@pxmart.com.tw", "b@pxmart.com.tw");
		assertThat(d.cc()).isEmpty();
		assertThat(d.originalTo()).containsExactly("x@pxmart.com.tw");
		assertThat(d.html()).isEqualTo("<p>hi</p>");
		assertThat(d.appId()).isEqualTo(APP);
		assertThat(d.canResend()).isTrue();
		assertThat(service.detail(6L).canResend()).isFalse();
	}

	// ---------- 重寄 ----------

	@Test
	void 重寄FAILED成功_非FAILED回409_不存在回404() {
		when(dao.resetFailed(1L, "A0001")).thenReturn(1);
		when(dao.resetFailed(2L, "A0001")).thenReturn(0);
		when(dao.findById(2L)).thenReturn(row(2L, MailOutboxDao.STATUS_SENT));
		when(dao.resetFailed(3L, "A0001")).thenReturn(0);
		when(dao.findById(3L)).thenReturn(null);

		service.resend(1L, ADMIN);
		assertThatThrownBy(() -> service.resend(2L, ADMIN)).isInstanceOf(ApiConflictException.class)
				.hasMessage(AdminMailService.MSG_RESEND_NOT_FAILED);
		assertThatThrownBy(() -> service.resend(3L, ADMIN)).isInstanceOf(ApiNotFoundException.class);
		verify(dao, never()).findById(1L);
	}

	// ---------- 測試寄信 ----------

	@Test
	void 測試寄信輸入不合時不組信不寫outbox() {
		assertThatThrownBy(() -> service.sendTest(new MailTestRequest("not-an-email", "approved", APP), ADMIN))
				.isInstanceOf(ApiBadRequestException.class).hasMessage(AdminMailService.MSG_BAD_TO);
		assertThatThrownBy(() -> service.sendTest(new MailTestRequest("a@b.c\r\nBcc: x@y.z", "approved", APP), ADMIN))
				.isInstanceOf(ApiBadRequestException.class).hasMessage(AdminMailService.MSG_BAD_TO);
		assertThatThrownBy(() -> service.sendTest(new MailTestRequest("me@pxmart.com.tw", "recalled", APP), ADMIN))
				.isInstanceOf(ApiBadRequestException.class).hasMessage(AdminMailService.MSG_BAD_TEMPLATE);
		assertThatThrownBy(() -> service.sendTest(new MailTestRequest("me@pxmart.com.tw", "approved", "x' OR 1=1"), ADMIN))
				.isInstanceOf(ApiNotFoundException.class).hasMessage(AdminMailService.MSG_APP_NOT_FOUND);
		verifyNoInteractions(notifier, outboxService, worker);
	}

	@Test
	void 測試寄信SMTP未設定回409不寫outbox() {
		when(dispatcher.isConfigured()).thenReturn(false);

		assertThatThrownBy(() -> service.sendTest(new MailTestRequest("me@pxmart.com.tw", "approved", APP), ADMIN))
				.isInstanceOf(ApiConflictException.class).hasMessage(AdminMailService.MSG_SMTP_NOT_CONFIGURED);
		verifyNoInteractions(notifier, outboxService, worker);
	}

	@Test
	void 測試寄信申請單不存在回404() {
		when(notifier.buildTest(eq("approved"), eq(APP), anyString(), anyString(), anyString(), anyString()))
				.thenReturn(null);

		assertThatThrownBy(() -> service.sendTest(new MailTestRequest("me@pxmart.com.tw", "approved", APP), ADMIN))
				.isInstanceOf(ApiNotFoundException.class);
		verifyNoInteractions(outboxService, worker);
	}

	@Test
	void 測試寄信成功時寫入後依token找主鍵再同步寄一次() {
		MailMessage msg = MailMessage.of(List.of("me@pxmart.com.tw"), "[測試] x", "<p>x</p>", Map.of(), "A0001");
		ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
		when(notifier.buildTest(eq("step-pending"), eq(APP), eq("me@pxmart.com.tw"), eq("管理員"), eq("A0001"),
				token.capture())).thenReturn(msg);
		when(outboxService.enqueue(msg)).thenReturn(true);
		when(dao.findIdByTestToken(anyString(), eq("A0001"))).thenReturn(42L);
		when(worker.sendNow(42L)).thenReturn(MailWorker.SendOutcome.SENT);
		when(dao.findById(42L)).thenReturn(row(42L, MailOutboxDao.STATUS_SENT));

		MailTestResult r = service.sendTest(new MailTestRequest(" me@pxmart.com.tw ", "step-pending", APP), ADMIN);

		assertThat(r.mailId()).isEqualTo(42L);
		assertThat(r.status()).isEqualTo(MailOutboxDao.STATUS_SENT);
		assertThat(r.error()).isNull();
		verify(dao).findIdByTestToken(token.getValue(), "A0001");
	}

	@Test
	void 測試寄信SMTP連不上回固定訊息且信留PENDING() {
		MailMessage msg = MailMessage.of(List.of("me@pxmart.com.tw"), "[測試] x", "<p>x</p>", Map.of(), "A0001");
		when(notifier.buildTest(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
				.thenReturn(msg);
		when(outboxService.enqueue(msg)).thenReturn(true);
		when(dao.findIdByTestToken(anyString(), eq("A0001"))).thenReturn(43L);
		when(worker.sendNow(43L)).thenReturn(MailWorker.SendOutcome.UNREACHABLE);
		when(dao.findById(43L)).thenReturn(row(43L, MailOutboxDao.STATUS_PENDING));

		MailTestResult r = service.sendTest(new MailTestRequest("me@pxmart.com.tw", "approved", APP), ADMIN);

		assertThat(r.status()).isEqualTo(MailOutboxDao.STATUS_PENDING);
		assertThat(r.error()).isEqualTo(AdminMailService.MSG_UNREACHABLE);
	}

	@Test
	void 設定只回主機port寄件人開關改寄地址() {
		properties.setEnabled(true);
		properties.setFrom("Infra-Manager@pxmart.com.tw");
		properties.setOverrideTo("me@pxmart.com.tw");

		var s = service.settings();

		assertThat(s.host()).isEqualTo("mail.example.test");
		assertThat(s.port()).isEqualTo(25);
		assertThat(s.enabled()).isTrue();
		assertThat(s.configured()).isTrue();
		assertThat(s.from()).isEqualTo("Infra-Manager@pxmart.com.tw");
		assertThat(s.overrideTo()).isEqualTo("me@pxmart.com.tw");
	}
}
