package com.mpx.infra_manager_java.service.mail;

// ============================================================
// AI版本  : Claude Opus 5.5 (claude-opus-5-5)
// 修改日期: 2026-10-13
// 變更說明: 新增：admin 寄信紀錄與測試寄信（S8b R4）。權限由 AdminMailController 的 @PreAuthorize 擋（只限 admin）。
//           list：狀態只收 PENDING／SENT／FAILED（不合 → 400）、q 去空白並限 100 字、from 與 to 都沒給時預設最近 7 天
//                 （舊系統寄信紀錄頁的預設）、from>to → 400、page<1 視為 1；列表不回 HTML 與收件人地址（只給人數）。
//           detail：找不到 → 404；收件人 JSON 解析失敗時給空清單（紀錄頁仍要能開）。
//           resend：只限 FAILED → PENDING、TRY_CNT 歸零，其餘狀態 409、不存在 404；排程下一輪就會寄。
//           sendTest：收件地址格式、樣板名、單號格式先驗；SMTP 未設定 409、不寫 outbox；
//                 寫 outbox 後立即同步寄一次（MailWorker.sendNow），回寫入的主鍵與寄送後狀態。
//                 im.mail.override-to 有值時照樣改寄（MailOutboxService 寫入、MailDispatcher 寄送兩道）。
//           本類別不掛 @Transactional：測試信的 INSERT 要先 commit，sendNow 才鎖得到該列。
//           log 只記操作人工號、outbox ID、樣板名、單號，不記收件地址
// ============================================================

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.mpx.infra_manager_java.config.MailProperties;
import com.mpx.infra_manager_java.dao.mail.MailOutboxDao;
import com.mpx.infra_manager_java.model.auth.AuthUser;
import com.mpx.infra_manager_java.model.mail.MailDetail;
import com.mpx.infra_manager_java.model.mail.MailListItem;
import com.mpx.infra_manager_java.model.mail.MailListQuery;
import com.mpx.infra_manager_java.model.mail.MailListResponse;
import com.mpx.infra_manager_java.model.mail.MailMessage;
import com.mpx.infra_manager_java.model.mail.MailOutboxRow;
import com.mpx.infra_manager_java.model.mail.MailSettings;
import com.mpx.infra_manager_java.model.mail.MailTestRequest;
import com.mpx.infra_manager_java.model.mail.MailTestResult;
import com.mpx.infra_manager_java.service.mail.MailWorker.SendOutcome;
import com.mpx.infra_manager_java.util.TaiwanTime;
import com.mpx.infra_manager_java.web.ApiBadRequestException;
import com.mpx.infra_manager_java.web.ApiConflictException;
import com.mpx.infra_manager_java.web.ApiNotFoundException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AdminMailService {

	public static final String MSG_MAIL_NOT_FOUND = "找不到信件";
	public static final String MSG_BAD_STATUS = "狀態篩選值不正確";
	public static final String MSG_BAD_RANGE = "起日不得晚於迄日";
	public static final String MSG_Q_TOO_LONG = "關鍵字過長";
	public static final String MSG_RESEND_NOT_FAILED = "只有寄送失敗的信可以重寄";
	public static final String MSG_BAD_TO = "收件地址格式不正確";
	public static final String MSG_BAD_TEMPLATE = "樣板不正確";
	public static final String MSG_APP_NOT_FOUND = "找不到申請單";
	public static final String MSG_SMTP_NOT_CONFIGURED = "寄信主機未設定，無法寄送測試信";
	public static final String MSG_UNREACHABLE = "寄信主機連不上，信留在待寄佇列，恢復後由排程自動寄出";
	public static final String MSG_SKIPPED = "信已寫入待寄佇列，但這次沒有寄出（可能排程正在寄），請到寄信紀錄查看";

	static final int DEFAULT_RANGE_DAYS = 7;
	static final int Q_MAX = 100;
	/** 收件地址長度上限（RFC 5321） */
	static final int TO_MAX = 254;

	private static final Set<String> STATUSES = Set.of(MailOutboxDao.STATUS_PENDING, MailOutboxDao.STATUS_SENT,
			MailOutboxDao.STATUS_FAILED);
	private static final Pattern APP_ID = Pattern.compile("^[A-Z0-9-]{1,20}$");

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};
	private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
	};

	private static final Logger log = LoggerFactory.getLogger(AdminMailService.class);

	private final MailOutboxDao outboxDao;
	private final MailOutboxService outboxService;
	private final MailNotifier notifier;
	private final MailWorker worker;
	private final MailDispatcher dispatcher;
	private final MailProperties properties;
	private final String smtpHost;
	private final int smtpPort;

	public AdminMailService(MailOutboxDao outboxDao, MailOutboxService outboxService, MailNotifier notifier,
			MailWorker worker, MailDispatcher dispatcher, MailProperties properties,
			@Value("${spring.mail.host:}") String smtpHost, @Value("${spring.mail.port:25}") int smtpPort) {
		this.outboxDao = outboxDao;
		this.outboxService = outboxService;
		this.notifier = notifier;
		this.worker = worker;
		this.dispatcher = dispatcher;
		this.properties = properties;
		this.smtpHost = smtpHost == null ? "" : smtpHost.trim();
		this.smtpPort = smtpPort;
	}

	// ---------- 列表 ----------

	public MailListResponse list(MailListQuery raw) {
		MailListQuery q = normalize(raw);
		List<MailListItem> items = outboxDao.findList(q).stream().map(AdminMailService::toListItem).toList();
		long total = outboxDao.countList(q);
		return new MailListResponse(items, total, q.page(), MailListQuery.PAGE_SIZE);
	}

	static MailListQuery normalize(MailListQuery raw) {
		String status = raw.status() == null || raw.status().isBlank() ? null : raw.status().trim();
		if (status != null && !STATUSES.contains(status)) {
			throw new ApiBadRequestException(MSG_BAD_STATUS);
		}
		String q = raw.q() == null ? null : raw.q().trim();
		if (q != null && q.isEmpty()) {
			q = null;
		}
		if (q != null && q.length() > Q_MAX) {
			throw new ApiBadRequestException(MSG_Q_TOO_LONG);
		}
		LocalDate from = raw.from();
		LocalDate to = raw.to();
		if (from == null && to == null) {
			from = TaiwanTime.today().minusDays(DEFAULT_RANGE_DAYS);
		}
		if (from != null && to != null && from.isAfter(to)) {
			throw new ApiBadRequestException(MSG_BAD_RANGE);
		}
		return new MailListQuery(status, from, to, q, Math.max(1, raw.page()));
	}

	static MailListItem toListItem(MailOutboxRow r) {
		Map<String, Object> meta = meta(r.getMetaJson());
		return new MailListItem(r.getMailOutboxId(), r.getMailSubj(), r.getMailStatusCode(), tryCnt(r),
				text(meta.get("appId")), text(meta.get("event")), addresses(r.getToJson()).size(),
				TaiwanTime.formatDateTime(r.getCreateDate()), TaiwanTime.formatDateTime(r.getSendDate()),
				TaiwanTime.formatDateTime(r.getUpdateDate()),
				!MailOutboxDao.STATUS_SENT.equals(r.getMailStatusCode()) && r.getErrorText() != null
						&& !r.getErrorText().isBlank());
	}

	// ---------- 單筆 ----------

	public MailDetail detail(long id) {
		MailOutboxRow r = outboxDao.findById(id);
		if (r == null) {
			throw new ApiNotFoundException(MSG_MAIL_NOT_FOUND);
		}
		Map<String, Object> meta = meta(r.getMetaJson());
		return new MailDetail(r.getMailOutboxId(), r.getMailSubj(), r.getMailStatusCode(), tryCnt(r),
				addresses(r.getToJson()), addresses(r.getCcJson()), addresses(r.getBccJson()),
				stringList(meta.get("originalTo")), stringList(meta.get("originalCc")),
				stringList(meta.get("originalBcc")), r.getErrorText(), r.getSmtpMsgId(), text(meta.get("appId")),
				text(meta.get("event")), meta, r.getHtmlBody(), TaiwanTime.formatDateTime(r.getCreateDate()),
				r.getCreateBy(), TaiwanTime.formatDateTime(r.getSendDate()),
				TaiwanTime.formatDateTime(r.getUpdateDate()), r.getUpdateBy(),
				MailOutboxDao.STATUS_FAILED.equals(r.getMailStatusCode()));
	}

	// ---------- 重寄 ----------

	public void resend(long id, AuthUser me) {
		if (outboxDao.resetFailed(id, me.userId()) == 1) {
			log.info("信件重寄：outbox {} 改回待寄 by={}", id, me.userId());
			return;
		}
		if (outboxDao.findById(id) == null) {
			throw new ApiNotFoundException(MSG_MAIL_NOT_FOUND);
		}
		throw new ApiConflictException(MSG_RESEND_NOT_FAILED);
	}

	// ---------- 測試寄信 ----------

	public MailSettings settings() {
		return new MailSettings(properties.isEnabled(), dispatcher.isConfigured(), smtpHost, smtpPort,
				properties.getFrom(), properties.getOverrideTo(), properties.getSiteUrl());
	}

	public MailTestResult sendTest(MailTestRequest request, AuthUser me) {
		if (request == null) {
			throw new ApiBadRequestException(MSG_BAD_TO);
		}
		String to = request.to() == null ? "" : request.to().trim();
		if (to.length() > TO_MAX || MailOutboxService.normalize(List.of(to), Set.of()).isEmpty()) {
			throw new ApiBadRequestException(MSG_BAD_TO);
		}
		String template = request.template() == null ? "" : request.template().trim();
		if (!MailNotifier.TEST_TEMPLATES.contains(template)) {
			throw new ApiBadRequestException(MSG_BAD_TEMPLATE);
		}
		String appId = request.appId() == null ? "" : request.appId().trim();
		if (!APP_ID.matcher(appId).matches()) {
			throw new ApiNotFoundException(MSG_APP_NOT_FOUND);
		}
		if (!dispatcher.isConfigured()) {
			throw new ApiConflictException(MSG_SMTP_NOT_CONFIGURED);
		}

		String token = UUID.randomUUID().toString();
		MailMessage message = notifier.buildTest(template, appId, to, me.userName(), me.userId(), token);
		if (message == null) {
			throw new ApiNotFoundException(MSG_APP_NOT_FOUND);
		}
		if (!outboxService.enqueue(message)) {
			throw new ApiBadRequestException(MSG_BAD_TO);
		}
		Long id = outboxDao.findIdByTestToken(token, me.userId());
		if (id == null) {
			throw new IllegalStateException("測試信已寫入但找不到主鍵");
		}
		SendOutcome outcome = worker.sendNow(id);
		MailOutboxRow row = outboxDao.findById(id);
		String status = row == null ? MailOutboxDao.STATUS_PENDING : row.getMailStatusCode();
		String error = switch (outcome) {
			case SENT -> row == null ? null : row.getErrorText();
			case FAILED_TRY -> row == null ? null : row.getErrorText();
			case UNREACHABLE -> MSG_UNREACHABLE;
			case SKIPPED -> MSG_SKIPPED;
			case NOT_CONFIGURED -> MSG_SMTP_NOT_CONFIGURED;
		};
		log.info("測試寄信：outbox {} template={} appId={} outcome={} by={}", id, template, appId, outcome, me.userId());
		return new MailTestResult(id, status, error);
	}

	// ---------- 共用 ----------

	private static int tryCnt(MailOutboxRow r) {
		return r.getTryCnt() == null ? 0 : r.getTryCnt();
	}

	/** 收件人 JSON → 清單；空的或壞掉的給空清單（紀錄頁仍要能開） */
	static List<String> addresses(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			List<String> list = MAPPER.readValue(json, STRING_LIST);
			return list == null ? List.of() : list;
		} catch (RuntimeException e) {
			return List.of();
		}
	}

	static Map<String, Object> meta(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			Map<String, Object> map = MAPPER.readValue(json, OBJECT_MAP);
			return map == null ? Map.of() : new LinkedHashMap<>(map);
		} catch (RuntimeException e) {
			return Map.of();
		}
	}

	private static List<String> stringList(Object value) {
		List<String> out = new ArrayList<>();
		if (value instanceof List<?> list) {
			for (Object o : list) {
				if (o != null) {
					out.add(o.toString());
				}
			}
		}
		return out;
	}

	private static String text(Object value) {
		return value == null ? null : value.toString();
	}
}
