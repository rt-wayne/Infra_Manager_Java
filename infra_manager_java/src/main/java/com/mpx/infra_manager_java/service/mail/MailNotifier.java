package com.mpx.infra_manager_java.service.mail;

// ============================================================
// AI版本  : Claude Fable 5.1 (claude-fable-5-1)
// 修改日期: 2026-10-08
// 變更說明: 新增：流程事件 → 組信 → 寫 outbox（S8 R2；事件 1～6 由 AppFlowService 呼叫，7～10 的方法先寫好、R3 由
//           AppExecutionService 接）。每個事件方法都在呼叫端的業務交易內執行、不掛 @Transactional（交易邊界方案 B）：
//           收件人查詢、重讀申請單、樣板渲染的任何 RuntimeException 都在這裡 catch、記 warn（事件＋單號＋例外類別）後略過不寄，
//           不影響業務；寫 outbox（MailOutboxService.enqueue）的例外照樣往外拋、跟業務一起 rollback。
//           收件人為空時不渲染、不寫入，只記 info。log 不記 email 地址（個資），只記單號、事件、人數。
//           樣板只用 th:text，所有欄位（含標題帶 <script>）都會被轉義；信內連結用 im.mail.site-url 組，不用 Host header
//           2026-10-08 S8 R3：加 rejectedAtVersion（事件 8／9 執行端與治理退回只有版次，照單號＋版次找該版簽核實例）；
//           找實例也在組信段內，查詢失敗一樣只記 warn 不影響退回
//           2026-10-13 S8b R4（Claude Opus 5.5）：加 buildTest（admin 測試寄信，用真實申請單資料組待簽核／退件／核准完成，
//           主旨加「[測試] 」）；核准完成的簽核紀錄抽成 approvalRecords 共用
// ============================================================

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

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
import com.mpx.infra_manager_java.model.mail.MailDetailModel;
import com.mpx.infra_manager_java.model.mail.MailMessage;
import com.mpx.infra_manager_java.model.mail.RecipientRow;
import com.mpx.infra_manager_java.service.changerequest.DecisionPolicy;
import com.mpx.infra_manager_java.util.TaiwanTime;

@Service
public class MailNotifier {

	public static final String EVENT_STEP_PENDING = "step-pending";
	public static final String EVENT_APPROVED = "approved";
	public static final String EVENT_REJECTED = "rejected";
	public static final String EVENT_RECALLED = "recalled";
	public static final String EVENT_GOV_REVIEW_REQUEST = "gov-review-request";
	public static final String EVENT_GOV_PASSED = "gov-passed";

	/** 事件 8／9 的階段名（退件樣板的 stageName），R3 的 AppExecutionService 用 */
	public static final String STAGE_EXECUTION = "執行階段";
	public static final String STAGE_GOVERNANCE = "資訊治理審核";

	/** admin 測試寄信可選的樣板（照舊系統測試頁的三種） */
	public static final Set<String> TEST_TEMPLATES = Set.of(EVENT_STEP_PENDING, EVENT_REJECTED, EVENT_APPROVED);
	static final String TEST_PREFIX = "[測試] ";

	private static final String EMPTY = "—";

	/** 找不到簽核實例時的占位主鍵（IDENTITY 從 1 起，-1 不會對到任何列） */
	private static final long NO_APPR_ID = -1L;

	private static final Logger log = LoggerFactory.getLogger(MailNotifier.class);

	private final MailOutboxService outbox;
	private final MailRecipientDao recipientDao;
	private final AppDao appDao;
	private final ApprovalDao approvalDao;
	private final MailProperties properties;
	private final ITemplateEngine templateEngine;

	public MailNotifier(MailOutboxService outbox, MailRecipientDao recipientDao, AppDao appDao,
			ApprovalDao approvalDao, MailProperties properties, ITemplateEngine templateEngine) {
		this.outbox = outbox;
		this.recipientDao = recipientDao;
		this.appDao = appDao;
		this.approvalDao = approvalDao;
		this.properties = properties;
		this.templateEngine = templateEngine;
	}

	/** 簽核紀錄一列（核准完成信） */
	public static class ApprovalRecord {
		private final String stepName;
		private final String userName;
		private final String decidedAt;

		ApprovalRecord(String stepName, String userName, String decidedAt) {
			this.stepName = stepName;
			this.userName = userName;
			this.decidedAt = decidedAt;
		}

		public String getStepName() { return stepName; }
		public String getUserName() { return userName; }
		public String getDecidedAt() { return decidedAt; }
	}

	// ---------- 事件 1～3：待簽核 ----------

	/**
	 * 目前 PENDING 關卡的候選人收「[待簽核]」信（送審、補件重送、進下一關共用）。
	 * 要在關卡已 PENDING、候選人已固化之後呼叫
	 */
	public void stepPending(String appId, long apprId, String by) {
		send(EVENT_STEP_PENDING, appId, () -> {
			ApprStepRow current = DecisionPolicy.currentStep(approvalDao.findSteps(apprId));
			if (current == null) {
				log.info("信件 {} 略過：沒有待簽關卡 單號={}", EVENT_STEP_PENDING, appId);
				return null;
			}
			List<String> to = emails(recipientDao.findStepCandidates(current.getApprStepId()));
			if (noRecipient(EVENT_STEP_PENDING, appId, to)) {
				return null;
			}
			AppRow app = loadApp(appId);
			String subject = "[待簽核] " + app.getAppId() + " " + nz(app.getAppTitle()) + " - " + nz(current.getStepName());
			Map<String, Object> model = baseModel(app, subject);
			model.put("stepName", nz(current.getStepName()));
			model.put("isResubmit", verNo(app) > 1);
			return message(to, subject, render("mail/step-pending", model), EVENT_STEP_PENDING, appId, by);
		});
	}

	// ---------- 事件 4：末關同意 ----------

	/** 只寄申請人，附各關簽核紀錄（關卡、簽核人、時間）；在 closeAppr APPROVED 之後呼叫 */
	public void approved(String appId, long apprId, String by) {
		send(EVENT_APPROVED, appId, () -> {
			List<String> to = emails(recipientDao.findApplicant(appId));
			if (noRecipient(EVENT_APPROVED, appId, to)) {
				return null;
			}
			AppRow app = loadApp(appId);
			List<ApprovalRecord> records = approvalRecords(approvalDao.findSteps(apprId));
			String subject = "[核准完成] " + app.getAppId() + " " + nz(app.getAppTitle());
			Map<String, Object> model = baseModel(app, subject);
			model.put("records", records);
			return message(to, subject, render("mail/approved", model), EVENT_APPROVED, appId, by);
		});
	}

	// ---------- 事件 5／8／9：退件 ----------

	/**
	 * 退件群組收「[退件]」信。stageName＝退件發生的階段（簽核關卡名、執行階段、資訊治理審核），
	 * rejecterName＝退件人姓名、memo＝退件說明；apprId＝該版簽核實例（用來找候選人與簽核人）
	 */
	public void rejected(String appId, long apprId, String stageName, String rejecterName, String memo, String by) {
		send(EVENT_REJECTED, appId, () -> buildRejected(appId, apprId, stageName, rejecterName, memo, by));
	}

	/**
	 * 執行階段／治理退回用（事件 8／9）：呼叫端只有版次，這裡照單號＋版次找該版簽核實例（已 APPROVED）再組退件信。
	 * 找不到實例（理論上不會）時用 -1：候選人與簽核人兩段查不到人，申請人、執行人、治理照寄
	 */
	public void rejectedAtVersion(String appId, int verNo, String stageName, String rejecterName, String memo,
			String by) {
		send(EVENT_REJECTED, appId, () -> {
			long apprId = approvalDao.findCurrent(appId, verNo).map(ApprRow::getApprId).orElse(NO_APPR_ID);
			return buildRejected(appId, apprId, stageName, rejecterName, memo, by);
		});
	}

	private MailMessage buildRejected(String appId, long apprId, String stageName, String rejecterName, String memo,
			String by) {
		List<String> to = emails(recipientDao.findRejectGroup(appId, apprId));
		if (noRecipient(EVENT_REJECTED, appId, to)) {
			return null;
		}
		AppRow app = loadApp(appId);
		String subject = "[退件] " + app.getAppId() + " " + nz(app.getAppTitle()) + " - " + nz(stageName);
		Map<String, Object> model = baseModel(app, subject);
		model.put("stageName", nz(stageName));
		model.put("rejecterName", nz(rejecterName));
		model.put("memo", nz(memo));
		return message(to, subject, render("mail/rejected", model), EVENT_REJECTED, appId, by);
	}

	// ---------- 事件 6：撤回 ----------

	/** 撤回當下 PENDING 關卡的候選人收「[撤回通知]」信；必須在 closeOpenSteps 之前呼叫（關卡關掉就找不到目前關卡） */
	public void recalled(String appId, long apprId, String reason, String by) {
		send(EVENT_RECALLED, appId, () -> {
			ApprStepRow current = DecisionPolicy.currentStep(approvalDao.findSteps(apprId));
			if (current == null) {
				log.info("信件 {} 略過：沒有待簽關卡 單號={}", EVENT_RECALLED, appId);
				return null;
			}
			List<String> to = emails(recipientDao.findStepCandidates(current.getApprStepId()));
			if (noRecipient(EVENT_RECALLED, appId, to)) {
				return null;
			}
			AppRow app = loadApp(appId);
			String subject = "[撤回通知] " + app.getAppId() + " " + nz(app.getAppTitle()) + " - 申請人撤回修改";
			Map<String, Object> model = baseModel(app, subject);
			model.put("stepName", nz(current.getStepName()));
			model.put("reason", reason);
			return message(to, subject, render("mail/recalled", model), EVENT_RECALLED, appId, by);
		});
	}

	// ---------- 事件 7：送治理審查（R3 接） ----------

	/** 所有啟用中 governance 收「[待審核執行結果]」信；resultName＝執行結果名稱 */
	public void govReviewRequest(String appId, String resultName, String by) {
		send(EVENT_GOV_REVIEW_REQUEST, appId, () -> {
			List<String> to = emails(recipientDao.findGovernance());
			if (noRecipient(EVENT_GOV_REVIEW_REQUEST, appId, to)) {
				return null;
			}
			AppRow app = loadApp(appId);
			String subject = "[待審核執行結果] " + app.getAppId() + " " + nz(app.getAppTitle());
			Map<String, Object> model = baseModel(app, subject);
			model.put("resultName", nz(resultName));
			return message(to, subject, render("mail/gov-review-request", model), EVENT_GOV_REVIEW_REQUEST, appId, by);
		});
	}

	// ---------- 事件 10：治理通過結案（R3 接） ----------

	/** 只寄申請人「[執行結果已通過]」；memo＝治理意見 */
	public void govPassed(String appId, String memo, String by) {
		send(EVENT_GOV_PASSED, appId, () -> {
			List<String> to = emails(recipientDao.findApplicant(appId));
			if (noRecipient(EVENT_GOV_PASSED, appId, to)) {
				return null;
			}
			AppRow app = loadApp(appId);
			String subject = "[執行結果已通過] " + app.getAppId() + " " + nz(app.getAppTitle());
			Map<String, Object> model = baseModel(app, subject);
			model.put("memo", memo);
			return message(to, subject, render("mail/gov-passed", model), EVENT_GOV_PASSED, appId, by);
		});
	}

	// ---------- admin 測試寄信（S8b R4） ----------

	/**
	 * 用一張真實申請單的資料組一封測試信（照舊系統測試頁：待簽核、退件、核准完成三種），收件人只有 to、
	 * 主旨加「[測試] 」；meta 的 event 為「test-樣板名」、另帶 testToken 供寫入後找回主鍵。
	 * 申請單不存在回 null；樣板名不在 TEST_TEMPLATES 丟 IllegalArgumentException。不寫 outbox（由呼叫端 enqueue）
	 */
	public MailMessage buildTest(String template, String appId, String to, String operatorName, String by,
			String testToken) {
		if (!TEST_TEMPLATES.contains(template)) {
			throw new IllegalArgumentException("不支援的測試樣板");
		}
		AppRow app = appDao.findById(appId).orElse(null);
		if (app == null) {
			return null;
		}
		long apprId = approvalDao.findCurrent(appId, verNo(app)).map(ApprRow::getApprId).orElse(NO_APPR_ID);
		List<ApprStepRow> steps = apprId == NO_APPR_ID ? List.of() : approvalDao.findSteps(apprId);
		ApprStepRow current = DecisionPolicy.currentStep(steps);
		String stepName = current == null ? "（測試關卡）" : nz(current.getStepName());
		String base = app.getAppId() + " " + nz(app.getAppTitle());
		String subject;
		Map<String, Object> model;
		String html;
		switch (template) {
			case EVENT_STEP_PENDING -> {
				subject = TEST_PREFIX + "[待簽核] " + base + " - " + stepName;
				model = baseModel(app, subject);
				model.put("stepName", stepName);
				model.put("isResubmit", verNo(app) > 1);
				html = render("mail/step-pending", model);
			}
			case EVENT_REJECTED -> {
				subject = TEST_PREFIX + "[退件] " + base + " - " + stepName;
				model = baseModel(app, subject);
				model.put("stageName", stepName);
				model.put("rejecterName", nz(operatorName));
				model.put("memo", "（測試退件說明）請補充風險評估章節。");
				html = render("mail/rejected", model);
			}
			default -> {
				subject = TEST_PREFIX + "[核准完成] " + base;
				model = baseModel(app, subject);
				model.put("records", approvalRecords(steps));
				html = render("mail/approved", model);
			}
		}
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("event", "test-" + template);
		meta.put("appId", appId);
		meta.put("testToken", testToken);
		return MailMessage.of(List.of(to), subject, html, meta, by);
	}

	// ---------- 共用 ----------

	private static List<ApprovalRecord> approvalRecords(List<ApprStepRow> steps) {
		List<ApprovalRecord> records = new ArrayList<>();
		for (ApprStepRow s : steps) {
			if ("APPROVED".equals(s.getStepStatusCode())) {
				records.add(new ApprovalRecord(nz(s.getStepName()), nz(s.getUserName()),
						nz(TaiwanTime.formatDateTime(s.getDecideDate()))));
			}
		}
		return records;
	}

	/**
	 * 組信（builder）失敗只記 warn 不往外拋；builder 回 null 表示不用寄；寫 outbox 的例外原樣往外拋
	 */
	private void send(String event, String appId, Supplier<MailMessage> builder) {
		MailMessage message;
		try {
			message = builder.get();
		} catch (RuntimeException e) {
			// 例外訊息可能含 SQL，只記類別；完整堆疊留 debug
			log.warn("信件 {} 組信失敗、略過不寄 單號={} 例外={}", event, appId, e.getClass().getName());
			log.debug("信件組信失敗詳細", e);
			return;
		}
		if (message == null) {
			return;
		}
		outbox.enqueue(message);
	}

	private boolean noRecipient(String event, String appId, List<String> to) {
		if (to.isEmpty()) {
			log.info("信件 {} 略過：沒有有 email 的收件人 單號={}", event, appId);
			return true;
		}
		return false;
	}

	private static MailMessage message(List<String> to, String subject, String html, String event, String appId,
			String by) {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("event", event);
		meta.put("appId", appId);
		return MailMessage.of(to, subject, html, meta, by);
	}

	private AppRow loadApp(String appId) {
		return appDao.findById(appId).orElseThrow(() -> new IllegalStateException("申請單不存在，無法組信"));
	}

	private String render(String template, Map<String, Object> model) {
		Context ctx = new Context();
		ctx.setVariables(model);
		return templateEngine.process(template, ctx);
	}

	/** 所有樣板共用的變數：d（明細）、subject、link、siteName、sentAt */
	private Map<String, Object> baseModel(AppRow app, String subject) {
		Map<String, Object> model = new LinkedHashMap<>();
		model.put("d", detailOf(app));
		model.put("subject", subject);
		model.put("link", linkOf(app.getAppId()));
		model.put("siteName", properties.getSiteName());
		model.put("sentAt", TaiwanTime.formatDateTime(new Timestamp(System.currentTimeMillis())));
		return model;
	}

	/** im.mail.site-url 空白時不放連結（樣板的 link 片段 th:if 會略過） */
	String linkOf(String appId) {
		String base = properties.getSiteUrl();
		if (base == null || base.isBlank()) {
			return null;
		}
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		return base + "/#/apps/" + appId;
	}

	private static List<String> emails(List<RecipientRow> rows) {
		List<String> out = new ArrayList<>();
		for (RecipientRow r : rows) {
			if (r.getEmail() != null && !r.getEmail().isBlank()) {
				out.add(r.getEmail().trim());
			}
		}
		return out;
	}

	private static int verNo(AppRow app) {
		return app.getCurrVerNo() == null ? 1 : app.getCurrVerNo();
	}

	/** 把主檔與子表組成信件明細的顯示字串（照舊系統 renderDetailSection 的欄位） */
	MailDetailModel detailOf(AppRow app) {
		String appId = app.getAppId();
		List<OptionRow> options = appDao.findOptions(appId);
		List<EquipRow> equips = appDao.findEquipments(appId);
		List<PlanStepRow> plans = appDao.findPlanSteps(appId);

		List<String> execParts = new ArrayList<>();
		if (flag(app.getIsSelfExec())) {
			execParts.add("自行執行");
		}
		if (flag(app.getIsSupExec())) {
			execParts.add("委外廠商");
		}
		if ("REMOTE".equals(app.getWorkModeCode())) {
			execParts.add("遠端作業" + (blank(app.getRemoteMethod()) ? "" : "（" + app.getRemoteMethod() + "）"));
		} else if ("ONSITE".equals(app.getWorkModeCode())) {
			execParts.add("現場作業");
		}

		String supplier = EMPTY;
		if (flag(app.getIsSupExec()) && !blank(app.getSupName())) {
			supplier = app.getSupName() + "（聯絡人 " + nz(app.getSupCntct()) + "、電話 " + nz(app.getSupTel())
					+ (app.getSupHeadCnt() == null ? "" : "、" + app.getSupHeadCnt() + " 人") + "）";
		}

		List<String> categories = new ArrayList<>();
		List<String> reasons = new ArrayList<>();
		List<String> scopes = new ArrayList<>();
		for (OptionRow o : options) {
			switch (nz(o.getGroupCode())) {
			case "CATG_ITEM" -> categories.add(nz(o.getOptionName()));
			case "CATG" -> {
				if (!blank(o.getOtherText())) {
					categories.add("其他：" + o.getOtherText());
				}
			}
			case "REASON" -> reasons.add(nz(o.getOptionName()));
			case "SCOPE" -> scopes.add(nz(o.getOptionName()));
			default -> {
			}
			}
		}
		if (!blank(app.getOtherReason())) {
			reasons.add("其他：" + app.getOtherReason());
		}

		String schedule = EMPTY;
		if (app.getSchedStartDate() != null || app.getSchedEndDate() != null) {
			schedule = nz(TaiwanTime.formatDateTime(app.getSchedStartDate())) + " ～ "
					+ nz(TaiwanTime.formatDateTime(app.getSchedEndDate()))
					+ (app.getEstHourQty() == null ? "" : "（預計 " + app.getEstHourQty().stripTrailingZeros().toPlainString() + " 小時）");
		}

		String location;
		if (blank(app.getAreaName()) && blank(app.getRackName()) && blank(app.getUnitRange())) {
			location = blank(app.getOmitReason()) ? EMPTY : "未填設備位置：" + app.getOmitReason();
		} else {
			location = "區域 " + nz(app.getAreaName()) + "、機櫃 " + nz(app.getRackName()) + "、U 位 " + nz(app.getUnitRange());
		}

		List<String> equipments = new ArrayList<>();
		for (EquipRow e : equips) {
			equipments.add("#" + e.getSeqNo() + " " + nz(e.getEquipName()) + "（資產 " + nz(e.getAssetNo()) + "／型號 "
					+ nz(e.getModelNo()) + "／序號 " + nz(e.getSerialNo()) + "／IP " + nz(e.getMgmtIp()) + "）"
					+ (blank(e.getPurpDesc()) ? "" : " 用途：" + e.getPurpDesc()));
		}
		List<String> planSteps = new ArrayList<>();
		for (PlanStepRow p : plans) {
			planSteps.add(p.getSeqNo() + ". " + nz(p.getStepText()));
		}

		return new MailDetailModel(appId, nz(app.getAppTitle()), nz(app.getPrioName()), nz(app.getApplyUserName()),
				nz(app.getApplyDeptName()), nz(app.getApplyTel()), nz(app.getApplyEmail()),
				"v" + verNo(app) + "／" + nz(app.getFlowName()), nz(app.getWorkSubj()),
				execParts.isEmpty() ? EMPTY : String.join("／", execParts), supplier, join(categories), join(reasons),
				join(scopes), schedule, location, nz(app.getWorkDetail()), nz(app.getImpactDesc()), nz(app.getRiskDesc()),
				nz(app.getRollBackPlan()), equipments, planSteps,
				blank(app.getResubMemo()) ? null : app.getResubMemo());
	}

	private static String join(List<String> parts) {
		return parts.isEmpty() ? EMPTY : String.join("、", parts);
	}

	private static boolean flag(Integer value) {
		return value != null && value == 1;
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	private static String nz(String s) {
		return blank(s) ? EMPTY : s;
	}
}
