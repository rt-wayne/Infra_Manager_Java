package com.mpx.infra_manager_java.service.mail;

// ============================================================
// AI版本  : Claude Fable 5.1 (claude-fable-5-1)
// 修改日期: 2026-10-08
// 變更說明: 新增：MailNotifier 組信的單元測試（S8 R2），用真的 Thymeleaf 引擎讀 classpath 的樣板、其餘全 mock。鎖定：
//           待簽核信主旨格式、收件人＝目前 PENDING 關卡候選人、標題 <script> 被轉義、連結用 site-url 組且去尾斜線；
//           核准完成只寄申請人並列各關簽核紀錄；退件寄退件群組、主旨帶階段名；撤回寄目前關卡；補件版次顯示補件提示；
//           site-url 空白時信內沒有連結；沒有收件人不渲染不寫 outbox；收件人 DAO 丟例外只記 log、不寫 outbox、不往外拋；
//           enqueue 例外原樣往外拋（跟業務同交易 rollback）；meta 帶 event 與 appId
//           2026-10-08 S8 R3（Claude Opus 5.5）：加 rejectedAtVersion——依版次查目前簽核實例 ID 組退件群組；
//           查不到實例用 -1 照樣查（申請人／執行人／治理仍收得到）；查實例失敗只略過不往外拋
// ============================================================

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.mpx.infra_manager_java.config.MailProperties;
import com.mpx.infra_manager_java.dao.changerequest.AppDao;
import com.mpx.infra_manager_java.dao.changerequest.ApprovalDao;
import com.mpx.infra_manager_java.dao.mail.MailRecipientDao;
import com.mpx.infra_manager_java.model.changerequest.AppRow;
import com.mpx.infra_manager_java.model.changerequest.ApprRow;
import com.mpx.infra_manager_java.model.changerequest.ApprStepRow;
import com.mpx.infra_manager_java.model.changerequest.EquipRow;
import com.mpx.infra_manager_java.model.changerequest.OptionRow;
import com.mpx.infra_manager_java.model.changerequest.PlanStepRow;
import com.mpx.infra_manager_java.model.mail.MailMessage;
import com.mpx.infra_manager_java.model.mail.RecipientRow;

class MailNotifierTest {

	private static final String APP = "IM2026100001";

	private final MailOutboxService outbox = mock(MailOutboxService.class);
	private final MailRecipientDao recipientDao = mock(MailRecipientDao.class);
	private final AppDao appDao = mock(AppDao.class);
	private final ApprovalDao approvalDao = mock(ApprovalDao.class);
	private final MailProperties properties = new MailProperties();
	private final MailNotifier notifier;

	MailNotifierTest() {
		properties.setSiteUrl("https://im.example.test/im/");
		notifier = new MailNotifier(outbox, recipientDao, appDao, approvalDao, properties, engine());
	}

	/** 跟 Boot 自動設定等價的最小引擎：classpath:/templates/ 底下的 .html */
	private static SpringTemplateEngine engine() {
		ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
		resolver.setPrefix("templates/");
		resolver.setSuffix(".html");
		resolver.setTemplateMode(TemplateMode.HTML);
		resolver.setCharacterEncoding("UTF-8");
		resolver.setCacheable(false);
		SpringTemplateEngine engine = new SpringTemplateEngine();
		engine.setTemplateResolver(resolver);
		return engine;
	}

	private static RecipientRow recipient(String userId, String email) {
		RecipientRow r = new RecipientRow();
		r.setUserId(userId);
		r.setUserName("使用者" + userId);
		r.setEmail(email);
		return r;
	}

	private static ApprStepRow step(long id, int seq, String name, String status) {
		ApprStepRow s = new ApprStepRow();
		s.setApprStepId(id);
		s.setSeqNo(seq);
		s.setStepName(name);
		s.setStepStatusCode(status);
		return s;
	}

	private AppRow app(String title, int verNo) {
		AppRow a = new AppRow();
		a.setAppId(APP);
		a.setAppTitle(title);
		a.setPrioName("P3");
		a.setFlowName("完整流程");
		a.setCurrVerNo(verNo);
		a.setApplyUserName("王小明");
		a.setApplyDeptName("資訊部");
		a.setApplyTel("1234");
		a.setApplyEmail("applicant@pxmart.com.tw");
		a.setWorkSubj("交換器汰換");
		a.setIsSelfExec(1);
		a.setIsSupExec(1);
		a.setWorkModeCode("REMOTE");
		a.setRemoteMethod("VPN");
		a.setSupName("甲廠商");
		a.setSupCntct("李四");
		a.setSupTel("0912");
		a.setSupHeadCnt(2);
		a.setSchedStartDate(Timestamp.valueOf("2026-10-10 20:00:00"));
		a.setSchedEndDate(Timestamp.valueOf("2026-10-10 22:30:00"));
		a.setEstHourQty(new BigDecimal("2.50"));
		a.setAreaName("A 區");
		a.setRackName("R01");
		a.setUnitRange("10-12");
		a.setWorkDetail("第一行\n第二行");
		a.setResubMemo(verNo > 1 ? "已補齊資料" : null);
		when(appDao.findById(APP)).thenReturn(Optional.of(a));
		OptionRow catg = new OptionRow();
		catg.setGroupCode("CATG_ITEM");
		catg.setOptionName("網路設備");
		OptionRow reason = new OptionRow();
		reason.setGroupCode("REASON");
		reason.setOptionName("汰舊換新");
		when(appDao.findOptions(APP)).thenReturn(List.of(catg, reason));
		EquipRow e = new EquipRow();
		e.setSeqNo(1);
		e.setEquipName("SW-01");
		e.setMgmtIp("10.0.0.1");
		when(appDao.findEquipments(APP)).thenReturn(List.of(e));
		PlanStepRow p = new PlanStepRow();
		p.setSeqNo(1);
		p.setStepText("備份設定");
		when(appDao.findPlanSteps(APP)).thenReturn(List.of(p));
		return a;
	}

	private MailMessage enqueued() {
		ArgumentCaptor<MailMessage> captor = ArgumentCaptor.forClass(MailMessage.class);
		verify(outbox).enqueue(captor.capture());
		return captor.getValue();
	}

	// ---------- 待簽核 ----------

	@Test
	void 待簽核信寄給目前關卡候選人且標題被轉義() {
		app("<script>alert(1)</script>汰換", 1);
		when(approvalDao.findSteps(77L)).thenReturn(List.of(step(11, 1, "機房管理員", "PENDING"), step(12, 2, "部門主管", "WAITING")));
		when(recipientDao.findStepCandidates(11L)).thenReturn(List.of(recipient("U1", "u1@pxmart.com.tw"),
				recipient("U2", " u2@pxmart.com.tw "), recipient("U3", null)));

		notifier.stepPending(APP, 77L, "T0001");

		MailMessage m = enqueued();
		assertThat(m.to()).containsExactly("u1@pxmart.com.tw", "u2@pxmart.com.tw");
		assertThat(m.cc()).isEmpty();
		assertThat(m.subject()).isEqualTo("[待簽核] IM2026100001 <script>alert(1)</script>汰換 - 機房管理員");
		assertThat(m.htmlBody()).contains("&lt;script&gt;alert(1)&lt;/script&gt;汰換").doesNotContain("<script>");
		assertThat(m.htmlBody()).contains("https://im.example.test/im/#/apps/IM2026100001");
		assertThat(m.htmlBody()).contains("機房管理員 您好").doesNotContain("補件後重新送審");
		assertThat(m.htmlBody()).contains("自行執行／委外廠商／遠端作業（VPN）")
				.contains("甲廠商（聯絡人 李四、電話 0912、2 人）").contains("網路設備").contains("汰舊換新")
				.contains("2026-10-10 20:00 ～ 2026-10-10 22:30（預計 2.5 小時）").contains("區域 A 區、機櫃 R01、U 位 10-12")
				.contains("#1 SW-01（資產 —／型號 —／序號 —／IP 10.0.0.1）").contains("1. 備份設定")
				.contains("機房設備異動申請系統");
		assertThat(m.meta()).containsEntry("event", "step-pending").containsEntry("appId", APP);
		assertThat(m.createdBy()).isEqualTo("T0001");
		verify(recipientDao, never()).findStepCandidates(12L);
	}

	@Test
	void 補件版次的待簽核信顯示補件提示與補件說明() {
		app("汰換", 2);
		when(approvalDao.findSteps(88L)).thenReturn(List.of(step(21, 1, "機房管理員", "PENDING")));
		when(recipientDao.findStepCandidates(21L)).thenReturn(List.of(recipient("U1", "u1@pxmart.com.tw")));

		notifier.stepPending(APP, 88L, "T0001");

		String html = enqueued().htmlBody();
		assertThat(html).contains("補件後重新送審").contains("補件說明").contains("已補齊資料").contains("v2／完整流程");
	}

	@Test
	void 沒有待簽關卡時不查收件人不寫outbox() {
		when(approvalDao.findSteps(77L)).thenReturn(List.of(step(11, 1, "機房管理員", "APPROVED")));

		notifier.stepPending(APP, 77L, "T0001");

		verifyNoInteractions(recipientDao, outbox, appDao);
	}

	// ---------- 核准完成 ----------

	@Test
	void 核准完成只寄申請人並列出各關簽核紀錄() {
		app("汰換", 1);
		ApprStepRow s1 = step(11, 1, "機房管理員", "APPROVED");
		s1.setUserName("張三");
		s1.setDecideDate(Timestamp.valueOf("2026-10-08 09:30:00"));
		ApprStepRow s2 = step(12, 2, "部門主管", "APPROVED");
		s2.setUserName("李四");
		s2.setDecideDate(Timestamp.valueOf("2026-10-08 10:00:00"));
		ApprStepRow skipped = step(13, 3, "知會", "SKIPPED");
		when(approvalDao.findSteps(77L)).thenReturn(List.of(s1, s2, skipped));
		when(recipientDao.findApplicant(APP)).thenReturn(List.of(recipient("T0001", "applicant@pxmart.com.tw")));

		notifier.approved(APP, 77L, "S4U002");

		MailMessage m = enqueued();
		assertThat(m.to()).containsExactly("applicant@pxmart.com.tw");
		assertThat(m.subject()).isEqualTo("[核准完成] IM2026100001 汰換");
		assertThat(m.htmlBody()).contains("張三").contains("2026-10-08 09:30").contains("李四").contains("2026-10-08 10:00")
				.doesNotContain("知會");
		assertThat(m.meta()).containsEntry("event", "approved");
		verify(recipientDao, never()).findStepCandidates(anyLong());
	}

	// ---------- 退件 ----------

	@Test
	void 退件寄給退件群組且主旨帶階段名() {
		app("汰換", 1);
		when(recipientDao.findRejectGroup(APP, 77L)).thenReturn(List.of(recipient("T0001", "applicant@pxmart.com.tw"),
				recipient("U1", "u1@pxmart.com.tw"), recipient("G1", "gov@pxmart.com.tw")));

		notifier.rejected(APP, 77L, "部門主管", "李四", "資料不全\n請補附件", "S4U003");

		MailMessage m = enqueued();
		assertThat(m.to()).containsExactly("applicant@pxmart.com.tw", "u1@pxmart.com.tw", "gov@pxmart.com.tw");
		assertThat(m.subject()).isEqualTo("[退件] IM2026100001 汰換 - 部門主管");
		assertThat(m.htmlBody()).contains("部門主管").contains("李四").contains("資料不全\n請補附件");
		assertThat(m.meta()).containsEntry("event", "rejected");
		verifyNoInteractions(approvalDao);
	}

	@Test
	void 執行階段退件依版次查目前簽核實例再組退件群組() {
		app("汰換", 2);
		ApprRow appr = new ApprRow();
		appr.setApprId(77L);
		when(approvalDao.findCurrent(APP, 2)).thenReturn(Optional.of(appr));
		when(recipientDao.findRejectGroup(APP, 77L)).thenReturn(List.of(recipient("T0001", "applicant@pxmart.com.tw")));

		notifier.rejectedAtVersion(APP, 2, MailNotifier.STAGE_EXECUTION, "王五", "執行失敗", "S4U004");

		MailMessage m = enqueued();
		assertThat(m.subject()).isEqualTo("[退件] IM2026100001 汰換 - 執行階段");
		assertThat(m.htmlBody()).contains("王五").contains("執行失敗");
		assertThat(m.meta()).containsEntry("event", "rejected");
	}

	// ---------- admin 測試寄信（S8b R4） ----------

	@Test
	void 測試信只寄指定地址且主旨加測試前綴與token() {
		app("汰換", 1);
		ApprRow appr = new ApprRow();
		appr.setApprId(77L);
		when(approvalDao.findCurrent(APP, 1)).thenReturn(Optional.of(appr));
		when(approvalDao.findSteps(77L)).thenReturn(List.of(step(11, 1, "機房管理員", "PENDING")));

		MailMessage m = notifier.buildTest(MailNotifier.EVENT_STEP_PENDING, APP, "me@pxmart.com.tw", "管理員", "A0001",
				"tok-1");

		assertThat(m.to()).containsExactly("me@pxmart.com.tw");
		assertThat(m.subject()).isEqualTo("[測試] [待簽核] IM2026100001 汰換 - 機房管理員");
		assertThat(m.htmlBody()).contains("機房管理員 您好");
		assertThat(m.meta()).containsEntry("event", "test-step-pending").containsEntry("appId", APP)
				.containsEntry("testToken", "tok-1");
		assertThat(m.createdBy()).isEqualTo("A0001");
		verifyNoInteractions(recipientDao, outbox);
	}

	@Test
	void 測試退件信沒有簽核實例時用測試關卡名與操作人() {
		app("汰換", 1);
		when(approvalDao.findCurrent(APP, 1)).thenReturn(Optional.empty());

		MailMessage m = notifier.buildTest(MailNotifier.EVENT_REJECTED, APP, "me@pxmart.com.tw", "管理員", "A0001", "t");

		assertThat(m.subject()).isEqualTo("[測試] [退件] IM2026100001 汰換 - （測試關卡）");
		assertThat(m.htmlBody()).contains("管理員").contains("（測試退件說明）");
		verify(approvalDao, never()).findSteps(anyLong());
	}

	@Test
	void 測試信申請單不存在回null_樣板不支援丟例外() {
		when(appDao.findById(APP)).thenReturn(Optional.empty());

		assertThat(notifier.buildTest(MailNotifier.EVENT_APPROVED, APP, "me@pxmart.com.tw", "管理員", "A0001", "t"))
				.isNull();
		assertThatThrownBy(() -> notifier.buildTest(MailNotifier.EVENT_RECALLED, APP, "me@pxmart.com.tw", "管理員",
				"A0001", "t")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 該版次查不到簽核實例時退件群組仍查得到申請人執行人與治理() {
		app("汰換", 2);
		when(approvalDao.findCurrent(APP, 2)).thenReturn(Optional.empty());
		when(recipientDao.findRejectGroup(APP, -1L)).thenReturn(List.of(recipient("T0001", "applicant@pxmart.com.tw")));

		notifier.rejectedAtVersion(APP, 2, MailNotifier.STAGE_GOVERNANCE, "趙六", "紀錄不完整", "S4U005");

		assertThat(enqueued().subject()).isEqualTo("[退件] IM2026100001 汰換 - 資訊治理審核");
	}

	@Test
	void 查簽核實例失敗時只略過不往外拋() {
		when(approvalDao.findCurrent(APP, 2)).thenThrow(new DataAccessResourceFailureException("db down"));

		notifier.rejectedAtVersion(APP, 2, MailNotifier.STAGE_EXECUTION, "王五", "執行失敗", "S4U004");

		verifyNoInteractions(recipientDao, outbox);
	}

	// ---------- 撤回 ----------

	@Test
	void 撤回寄給目前關卡候選人並帶撤回原因() {
		app("汰換", 1);
		when(approvalDao.findSteps(77L)).thenReturn(List.of(step(11, 1, "機房管理員", "PENDING")));
		when(recipientDao.findStepCandidates(11L)).thenReturn(List.of(recipient("U1", "u1@pxmart.com.tw")));

		notifier.recalled(APP, 77L, "資料要補", "T0001");

		MailMessage m = enqueued();
		assertThat(m.to()).containsExactly("u1@pxmart.com.tw");
		assertThat(m.subject()).isEqualTo("[撤回通知] IM2026100001 汰換 - 申請人撤回修改");
		assertThat(m.htmlBody()).contains("資料要補").contains("機房管理員");
		assertThat(m.meta()).containsEntry("event", "recalled");
	}

	// ---------- 治理（R3 接事件，方法先鎖定） ----------

	@Test
	void 送治理審查寄給所有治理人員() {
		app("汰換", 1);
		when(recipientDao.findGovernance()).thenReturn(List.of(recipient("G1", "g1@pxmart.com.tw"), recipient("G2", "g2@pxmart.com.tw")));

		notifier.govReviewRequest(APP, "執行成功", "S4U002");

		MailMessage m = enqueued();
		assertThat(m.to()).containsExactly("g1@pxmart.com.tw", "g2@pxmart.com.tw");
		assertThat(m.subject()).isEqualTo("[待審核執行結果] IM2026100001 汰換");
		assertThat(m.htmlBody()).contains("執行成功");
		assertThat(m.meta()).containsEntry("event", "gov-review-request");
	}

	@Test
	void 治理通過只寄申請人並帶治理意見() {
		app("汰換", 1);
		when(recipientDao.findApplicant(APP)).thenReturn(List.of(recipient("T0001", "applicant@pxmart.com.tw")));

		notifier.govPassed(APP, "結果完整", "G1");

		MailMessage m = enqueued();
		assertThat(m.to()).containsExactly("applicant@pxmart.com.tw");
		assertThat(m.subject()).isEqualTo("[執行結果已通過] IM2026100001 汰換");
		assertThat(m.htmlBody()).contains("結果完整");
		assertThat(m.meta()).containsEntry("event", "gov-passed");
	}

	// ---------- 連結、收件人為空、例外邊界 ----------

	@Test
	void siteUrl空白時信內沒有連結() {
		properties.setSiteUrl("  ");
		app("汰換", 1);
		when(recipientDao.findApplicant(APP)).thenReturn(List.of(recipient("T0001", "applicant@pxmart.com.tw")));

		notifier.govPassed(APP, null, "G1");

		assertThat(notifier.linkOf(APP)).isNull();
		assertThat(enqueued().htmlBody()).doesNotContain("#/apps/").doesNotContain("href");
	}

	@Test
	void linkOf去掉多餘尾斜線() {
		properties.setSiteUrl("https://im.example.test///");
		assertThat(notifier.linkOf(APP)).isEqualTo("https://im.example.test/#/apps/IM2026100001");
	}

	@Test
	void 候選人都沒有email時不讀申請單不寫outbox() {
		when(approvalDao.findSteps(77L)).thenReturn(List.of(step(11, 1, "機房管理員", "PENDING")));
		when(recipientDao.findStepCandidates(11L)).thenReturn(List.of(recipient("U1", null), recipient("U2", "  ")));

		notifier.stepPending(APP, 77L, "T0001");

		verifyNoInteractions(outbox, appDao);
	}

	@Test
	void 收件人查詢失敗時只略過不往外拋() {
		when(approvalDao.findSteps(77L)).thenReturn(List.of(step(11, 1, "機房管理員", "PENDING")));
		when(recipientDao.findStepCandidates(11L)).thenThrow(new DataAccessResourceFailureException("連線中斷"));

		notifier.stepPending(APP, 77L, "T0001");

		verifyNoInteractions(outbox);
	}

	@Test
	void 申請單不存在時只略過不往外拋() {
		when(appDao.findById(APP)).thenReturn(Optional.empty());
		when(recipientDao.findApplicant(APP)).thenReturn(List.of(recipient("T0001", "applicant@pxmart.com.tw")));

		notifier.approved(APP, 77L, "S4U002");

		verifyNoInteractions(outbox);
	}

	@Test
	void 寫outbox失敗的例外原樣往外拋() {
		app("汰換", 1);
		when(recipientDao.findApplicant(APP)).thenReturn(List.of(recipient("T0001", "applicant@pxmart.com.tw")));
		doThrow(new DataIntegrityViolationException("寫入失敗")).when(outbox).enqueue(any());

		assertThatThrownBy(() -> notifier.govPassed(APP, "ok", "G1")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 位置未填時顯示省略原因且空欄位用破折號() {
		AppRow a = app("汰換", 1);
		a.setAreaName(null);
		a.setRackName(null);
		a.setUnitRange(null);
		a.setOmitReason("雲端主機");
		a.setIsSupExec(0);
		a.setSchedStartDate(null);
		a.setSchedEndDate(null);
		when(appDao.findEquipments(APP)).thenReturn(List.of());
		when(appDao.findPlanSteps(APP)).thenReturn(List.of());
		when(recipientDao.findApplicant(APP)).thenReturn(List.of(recipient("T0001", "applicant@pxmart.com.tw")));
		when(approvalDao.findSteps(77L)).thenReturn(List.of());

		notifier.approved(APP, 77L, "S4U002");

		String html = enqueued().htmlBody();
		assertThat(html).contains("未填設備位置：雲端主機").contains("自行執行／遠端作業（VPN）").doesNotContain("異動設備")
				.doesNotContain("執行步驟").doesNotContain("甲廠商");
		verify(approvalDao).findSteps(77L);
	}
}
