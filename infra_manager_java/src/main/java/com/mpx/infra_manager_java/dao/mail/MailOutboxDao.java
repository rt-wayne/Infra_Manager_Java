package com.mpx.infra_manager_java.dao.mail;

// ============================================================
// AI版本  : Claude Fable 5.1 (claude-fable-5-1)
// 修改日期: 2026-10-08
// 變更說明: 新增：IM_MAIL_OUTBOX 的讀寫（S8 R1）。每個方法單句；交易由呼叫端決定——
//           enqueue 的 INSERT 跟著業務交易一起 commit／rollback，worker 則每封信開自己的交易。
//           TO_JSON／CC_JSON／BCC_JSON／HTML_BODY／META_JSON 以 Types.CLOB 綁定。
//           worker 取信分兩步：findDueIds 不鎖、只找候選主鍵；lockPending 逐列 FOR UPDATE SKIP LOCKED
//           （Oracle 不允許 FOR UPDATE 與 FETCH FIRST 同句，ORA-02014）。
//           markFailedTry 的 SET 子句以舊值算 TRY_CNT+1，達上限直接標 FAILED
//           2026-10-13 S8b R4（Claude Opus 5.5）：加 admin 寄信紀錄用的 findList／countList（篩狀態、建立日期、主旨關鍵字，
//           LIKE 跳脫萬用字元，不讀 HTML_BODY）、resetFailed（只限 FAILED → PENDING、TRY_CNT 歸零）、
//           findIdByTestToken（測試信寫入後依 META_JSON 的一次性 token 找回主鍵）
// ============================================================

import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;

import com.mpx.common.db.DbClient;
import com.mpx.infra_manager_java.config.DbSchema;
import com.mpx.infra_manager_java.model.changerequest.CountRow;
import com.mpx.infra_manager_java.model.mail.MailIdRow;
import com.mpx.infra_manager_java.model.mail.MailListQuery;
import com.mpx.infra_manager_java.model.mail.MailOutboxRow;
import com.mpx.infra_manager_java.util.TaiwanTime;

@Repository
public class MailOutboxDao {

	public static final String STATUS_PENDING = "PENDING";
	public static final String STATUS_SENT = "SENT";
	public static final String STATUS_FAILED = "FAILED";
	/** worker 更新列時的 UPDATE_BY（DDL 註解：排程產生者為 SYSTEM） */
	public static final String SYSTEM_USER = "SYSTEM";

	private static final String COLUMNS = "MAIL_OUTBOX_ID, TO_JSON, CC_JSON, BCC_JSON, MAIL_SUBJ, HTML_BODY, MAIL_STATUS_CODE,"
			+ " TRY_CNT, ERROR_TEXT, SMTP_MSG_ID, SEND_DATE, META_JSON, CREATE_DATE, CREATE_BY, UPDATE_DATE, UPDATE_BY";
	/** 列表用：不含 HTML_BODY、CC／BCC */
	private static final String LIST_COLUMNS = "MAIL_OUTBOX_ID, TO_JSON, MAIL_SUBJ, MAIL_STATUS_CODE, TRY_CNT, ERROR_TEXT,"
			+ " SEND_DATE, META_JSON, CREATE_DATE, UPDATE_DATE";

	private final DbClient dbClient;
	private final DbSchema schema;
	private final String itflowDb;

	public MailOutboxDao(DbClient dbClient, DbSchema schema, @Value("${db.connect.itflow}") String itflowDb) {
		this.dbClient = dbClient;
		this.schema = schema;
		this.itflowDb = itflowDb;
	}

	/** 寫入一封待寄信（PENDING、TRY_CNT 0）；JSON 參數已是序列化好的字串，cc／bcc／meta 可為 null */
	public void insert(String toJson, String ccJson, String bccJson, String subject, String htmlBody, String metaJson,
			String createdBy) {
		String sql = "INSERT INTO " + schema.table("IM_MAIL_OUTBOX")
				+ " (TO_JSON, CC_JSON, BCC_JSON, MAIL_SUBJ, HTML_BODY, MAIL_STATUS_CODE, TRY_CNT, META_JSON, STATUS,"
				+ " CREATE_DATE, CREATE_BY)"
				+ " VALUES (:toJson, :ccJson, :bccJson, :subj, :html, '" + STATUS_PENDING + "', 0, :metaJson, 1,"
				+ " SYSDATE, :createdBy)";
		Map<String, Object> p = new HashMap<>();
		p.put("toJson", clob(toJson));
		p.put("ccJson", clob(ccJson));
		p.put("bccJson", clob(bccJson));
		p.put("subj", subject);
		p.put("html", clob(htmlBody));
		p.put("metaJson", clob(metaJson));
		p.put("createdBy", createdBy);
		dbClient.update(itflowDb, sql, p);
	}

	/**
	 * 到期待寄的候選主鍵（不鎖）：PENDING、未達重試上限、且已過退避時間
	 * （第一次立即寄；之後以上次嘗試時間 + 已嘗試次數 × backoffMinutes 計）。舊的先寄
	 */
	public List<Long> findDueIds(int limit, int maxTry, int backoffMinutes) {
		String sql = "SELECT MAIL_OUTBOX_ID FROM " + schema.table("IM_MAIL_OUTBOX")
				+ " WHERE STATUS = 1 AND MAIL_STATUS_CODE = '" + STATUS_PENDING + "' AND TRY_CNT < :maxTry"
				+ " AND (TRY_CNT = 0 OR NVL(UPDATE_DATE, CREATE_DATE) + (TRY_CNT * :backoff) / 1440 <= SYSDATE)"
				+ " ORDER BY CREATE_DATE, MAIL_OUTBOX_ID FETCH FIRST :limit ROWS ONLY";
		Map<String, Object> p = Map.of("maxTry", maxTry, "backoff", backoffMinutes, "limit", limit);
		return dbClient.query(itflowDb, sql, p, MailIdRow.class).stream().map(MailIdRow::getMailOutboxId).toList();
	}

	/** 鎖住一封仍是 PENDING 的信並讀出全部欄位；已被別的執行緒鎖住或狀態已變則回 null。必須在交易內呼叫 */
	public MailOutboxRow lockPending(long mailOutboxId) {
		String sql = "SELECT " + COLUMNS + " FROM " + schema.table("IM_MAIL_OUTBOX")
				+ " WHERE MAIL_OUTBOX_ID = :id AND STATUS = 1 AND MAIL_STATUS_CODE = '" + STATUS_PENDING + "'"
				+ " FOR UPDATE SKIP LOCKED";
		return first(dbClient.query(itflowDb, sql, Map.of("id", mailOutboxId), MailOutboxRow.class));
	}

	/** 單筆（不鎖、不限狀態）；找不到回 null */
	public MailOutboxRow findById(long mailOutboxId) {
		String sql = "SELECT " + COLUMNS + " FROM " + schema.table("IM_MAIL_OUTBOX")
				+ " WHERE MAIL_OUTBOX_ID = :id AND STATUS = 1";
		return first(dbClient.query(itflowDb, sql, Map.of("id", mailOutboxId), MailOutboxRow.class));
	}

	/** 標成已寄出：TRY_CNT+1、記 Message-ID 與寄出時間；note 為部分收件人無效等備註（可 null）。回更新列數 */
	public int markSent(long mailOutboxId, String smtpMsgId, String note) {
		String sql = "UPDATE " + schema.table("IM_MAIL_OUTBOX")
				+ " SET MAIL_STATUS_CODE = '" + STATUS_SENT + "', TRY_CNT = TRY_CNT + 1, SMTP_MSG_ID = :msgId,"
				+ " SEND_DATE = SYSDATE, ERROR_TEXT = :note, UPDATE_DATE = SYSDATE, UPDATE_BY = :by"
				+ " WHERE MAIL_OUTBOX_ID = :id AND MAIL_STATUS_CODE = '" + STATUS_PENDING + "'";
		Map<String, Object> p = new HashMap<>();
		p.put("id", mailOutboxId);
		p.put("msgId", smtpMsgId);
		p.put("note", note);
		p.put("by", SYSTEM_USER);
		return dbClient.update(itflowDb, sql, p);
	}

	/** 記一次失敗：TRY_CNT+1、存錯誤文字；達 maxTry 標 FAILED、否則維持 PENDING 等下次重試。回更新列數 */
	public int markFailedTry(long mailOutboxId, String errorText, int maxTry) {
		String sql = "UPDATE " + schema.table("IM_MAIL_OUTBOX")
				+ " SET TRY_CNT = TRY_CNT + 1,"
				+ " MAIL_STATUS_CODE = CASE WHEN TRY_CNT + 1 >= :maxTry THEN '" + STATUS_FAILED + "'"
				+ " ELSE '" + STATUS_PENDING + "' END,"
				+ " ERROR_TEXT = :err, UPDATE_DATE = SYSDATE, UPDATE_BY = :by"
				+ " WHERE MAIL_OUTBOX_ID = :id AND MAIL_STATUS_CODE = '" + STATUS_PENDING + "'";
		Map<String, Object> p = new HashMap<>();
		p.put("id", mailOutboxId);
		p.put("maxTry", maxTry);
		p.put("err", errorText);
		p.put("by", SYSTEM_USER);
		return dbClient.update(itflowDb, sql, p);
	}

	// ---------- admin 寄信紀錄（S8b） ----------

	/** 列表一頁（新到舊）；不讀 HTML_BODY（一封可能數百 KB） */
	public List<MailOutboxRow> findList(MailListQuery q) {
		String sql = "SELECT " + LIST_COLUMNS + listFrom(q) + " ORDER BY CREATE_DATE DESC, MAIL_OUTBOX_ID DESC"
				+ " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY";
		Map<String, Object> p = listParams(q);
		p.put("offset", q.offset());
		p.put("size", MailListQuery.PAGE_SIZE);
		return dbClient.query(itflowDb, sql, p, MailOutboxRow.class);
	}

	/** 套用同樣篩選的總筆數 */
	public long countList(MailListQuery q) {
		String sql = "SELECT COUNT(*) AS CNT" + listFrom(q);
		List<CountRow> rows = dbClient.query(itflowDb, sql, listParams(q), CountRow.class);
		return rows.isEmpty() || rows.get(0).getCnt() == null ? 0L : rows.get(0).getCnt();
	}

	/** 重寄：只有 FAILED 會變回 PENDING、TRY_CNT 歸零（ERROR_TEXT 留著，下次寄送時覆蓋）。回更新列數，0＝不是 FAILED 或不存在 */
	public int resetFailed(long mailOutboxId, String by) {
		String sql = "UPDATE " + schema.table("IM_MAIL_OUTBOX")
				+ " SET MAIL_STATUS_CODE = '" + STATUS_PENDING + "', TRY_CNT = 0, UPDATE_DATE = SYSDATE, UPDATE_BY = :by"
				+ " WHERE MAIL_OUTBOX_ID = :id AND STATUS = 1 AND MAIL_STATUS_CODE = '" + STATUS_FAILED + "'";
		return dbClient.update(itflowDb, sql, Map.of("id", mailOutboxId, "by", by));
	}

	/**
	 * 測試信寫入後找回主鍵：DbClient 拿不到 IDENTITY 產生的鍵值，改用 META_JSON 的一次性 testToken 比對；
	 * 只看同一人最近一小時寫的列，避免掃整張表。找不到回 null
	 */
	public Long findIdByTestToken(String token, String createdBy) {
		String sql = "SELECT MAIL_OUTBOX_ID FROM " + schema.table("IM_MAIL_OUTBOX")
				+ " WHERE CREATE_BY = :by AND CREATE_DATE >= SYSDATE - 1/24"
				+ " AND JSON_VALUE(META_JSON, '$.testToken') = :token";
		List<MailIdRow> rows = dbClient.query(itflowDb, sql, Map.of("by", createdBy, "token", token), MailIdRow.class);
		return rows.isEmpty() ? null : rows.get(0).getMailOutboxId();
	}

	private String listFrom(MailListQuery q) {
		StringBuilder sb = new StringBuilder(" FROM ").append(schema.table("IM_MAIL_OUTBOX")).append(" WHERE STATUS = 1");
		if (q.status() != null) {
			sb.append(" AND MAIL_STATUS_CODE = :status");
		}
		if (q.from() != null) {
			sb.append(" AND CREATE_DATE >= :fromDate");
		}
		if (q.to() != null) {
			sb.append(" AND CREATE_DATE < :toDateExclusive");
		}
		if (q.q() != null) {
			sb.append(" AND UPPER(MAIL_SUBJ) LIKE :q ESCAPE '\\'");
		}
		return sb.toString();
	}

	private static Map<String, Object> listParams(MailListQuery q) {
		Map<String, Object> p = new HashMap<>();
		if (q.status() != null) {
			p.put("status", q.status());
		}
		if (q.from() != null) {
			p.put("fromDate", TaiwanTime.startOf(q.from()));
		}
		if (q.to() != null) {
			p.put("toDateExclusive", TaiwanTime.startOf(q.to().plusDays(1)));
		}
		if (q.q() != null) {
			p.put("q", likePattern(q.q()));
		}
		return p;
	}

	/** UPPER 後前後加 %，並把 LIKE 的萬用字元與跳脫字元本身跳脫（ESCAPE '\'；同 AppDao.likePattern） */
	static String likePattern(String text) {
		StringBuilder sb = new StringBuilder("%");
		for (char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
			if (c == '%' || c == '_' || c == '\\') {
				sb.append('\\');
			}
			sb.append(c);
		}
		return sb.append('%').toString();
	}

	private static SqlParameterValue clob(String value) {
		return new SqlParameterValue(Types.CLOB, value);
	}

	private static <T> T first(List<T> rows) {
		return rows.isEmpty() ? null : rows.get(0);
	}
}
