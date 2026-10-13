package com.mpx.infra_manager_java.service.mail;

// ============================================================
// AI版本  : Claude Fable 5.1 (claude-fable-5-1)
// 修改日期: 2026-10-08
// 變更說明: 新增：背景寄信排程（S8 R1）。只有 im.mail.enabled=true（MailSchedulingConfig 啟用 @EnableScheduling）才會跑。
//           每輪：不鎖查最多 batch-size 個到期候選 → 每封信各開一個交易：FOR UPDATE SKIP LOCKED 鎖到才寄，
//           寄完在同交易標 SENT 或 TRY_CNT+1（達 max-try 標 FAILED）後 commit。
//           鎖沒拿到（被另一實例或 admin 重寄佔住）就跳過。語意是「至少寄一次」：寄出後、commit 前程式掛掉會重寄
//           （schema 沒有 SENDING 中間狀態，不改 DDL 的已知取捨）。
//           DB 連不上或 SMTP 未設定時每輪都會碰到，只在第一次失敗與恢復時各記一次 log（避免每 30 秒洗版）；
//           單封信的成敗各記一行，含 outbox ID、第幾次、例外類別，不含收件人地址。
//           ERROR_TEXT 存例外類別＋訊息（SMTP 回應）截 1000 字
//           2026-10-08 S8a 階段末 review 第 2、3 項（Claude Opus 5.5）：
//           ② ERROR_TEXT 存進去前把 email 形狀的字串遮成 ***（SMTP 拒收回應常帶收件人地址，設計要求錯誤欄不含地址）；
//           ③ SMTP 連不上（連線階段失敗：MailConnectException／ConnectException／UnknownHostException／NoRouteToHostException）
//           不算一次重試、不寫 ERROR_TEXT，只在第一次與恢復時各記一次 log，並結束本輪不再試其餘信——斷線多久信都不會被
//           耗光重試變 FAILED，恢復後自動補寄（待確認 ① 採 A；S8b 重寄頁上線後可再評估改回算重試）
//           2026-10-13 S8b R4（Claude Opus 5.5）：加 sendNow（admin 測試寄信同步寄一次，與排程共用 processOne）
// ============================================================

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mpx.infra_manager_java.config.MailProperties;
import com.mpx.infra_manager_java.dao.mail.MailOutboxDao;
import com.mpx.infra_manager_java.model.mail.MailOutboxRow;
import com.mpx.infra_manager_java.service.mail.MailDispatcher.SendResult;

@Component
public class MailWorker {

	/** ERROR_TEXT VARCHAR2(1000 CHAR) */
	static final int ERROR_MAX = 1000;

	private static final Logger log = LoggerFactory.getLogger(MailWorker.class);

	/** email 形狀的字串（寬鬆比對，寧可多遮）；ERROR_TEXT 存進去前遮掉 */
	private static final Pattern EMAIL_LIKE = Pattern.compile("[^\\s<>\"',;:()\\[\\]]+@[^\\s<>\"',;:()\\[\\]]+");

	/** Angus Mail 連線失敗例外的類別名（org.eclipse.angus.mail.util.MailConnectException） */
	private static final String MAIL_CONNECT_EXCEPTION = "MailConnectException";

	/** SMTP 連線階段失敗：processOne 丟出讓本輪結束、不記 TRY_CNT */
	private static final class SmtpUnavailableException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		SmtpUnavailableException(Throwable cause) {
			super(cause);
		}
	}

	private final MailOutboxDao outboxDao;
	private final MailDispatcher dispatcher;
	private final MailProperties properties;
	private final TransactionTemplate tx;

	/** 目前是否處於「DB 無法存取」狀態；只在狀態翻轉時記 log */
	private final AtomicBoolean dbUnavailable = new AtomicBoolean(false);
	/** 目前是否處於「SMTP 未設定」狀態；同上 */
	private final AtomicBoolean smtpUnconfigured = new AtomicBoolean(false);
	/** 目前是否處於「SMTP 連不上」狀態；同上 */
	private final AtomicBoolean smtpUnreachable = new AtomicBoolean(false);

	public MailWorker(MailOutboxDao outboxDao, MailDispatcher dispatcher, MailProperties properties,
			PlatformTransactionManager transactionManager) {
		this.outboxDao = outboxDao;
		this.dispatcher = dispatcher;
		this.properties = properties;
		this.tx = new TransactionTemplate(transactionManager);
	}

	/** 排程進入點；間隔與首次延遲讀 properties，預設 30 秒／15 秒 */
	@Scheduled(fixedDelayString = "${im.mail.poll-delay-ms:30000}", initialDelayString = "${im.mail.initial-delay-ms:15000}")
	public void poll() {
		pollOnce();
	}

	/**
	 * 跑一輪；回「成功寄出的封數」。供排程與測試呼叫。
	 * 任何例外都在這裡吞掉並記 log，不讓排程執行緒死掉
	 */
	public int pollOnce() {
		if (!dispatcher.isConfigured()) {
			if (smtpUnconfigured.compareAndSet(false, true)) {
				log.warn("信件 worker：SMTP 未設定（spring.mail.host／im.mail.from），暫不寄信，信只累積在 outbox");
			}
			return 0;
		}
		if (smtpUnconfigured.compareAndSet(true, false)) {
			log.info("信件 worker：SMTP 設定已就緒，恢復寄信");
		}

		List<Long> ids;
		try {
			ids = outboxDao.findDueIds(properties.getBatchSize(), properties.getMaxTry(),
					properties.getRetryBackoffMinutes());
			markDbRecovered();
		} catch (DataAccessException e) {
			markDbUnavailable(e);
			return 0;
		}

		int sent = 0;
		for (Long id : ids) {
			try {
				Boolean ok = tx.execute(status -> processOne(id));
				if (Boolean.TRUE.equals(ok)) {
					sent++;
				}
				markDbRecovered();
			} catch (DataAccessException e) {
				markDbUnavailable(e);
				return sent;
			} catch (SmtpUnavailableException e) {
				if (smtpUnreachable.compareAndSet(false, true)) {
					log.warn("信件 worker：SMTP 連不上（{}），本輪停止、不計重試，恢復後自動補寄",
							e.getCause().getClass().getSimpleName());
				}
				return sent;
			} catch (RuntimeException e) {
				// 不該走到這裡（processOne 已把寄送例外轉成 markFailedTry）；記下來但不中斷其他信
				log.warn("信件 worker：outbox {} 處理時發生未預期錯誤 {}", id, e.getClass().getSimpleName());
			}
		}
		return sent;
	}

	/** 立即寄一封的結果（admin 測試寄信用） */
	public enum SendOutcome {
		/** 已寄出、標 SENT */
		SENT,
		/** SMTP 拒收或逾時，已記一次 TRY_CNT（未達上限仍是 PENDING，排程開著時稍後重試） */
		FAILED_TRY,
		/** SMTP 連不上：不計重試、信留在 PENDING */
		UNREACHABLE,
		/** 鎖不到或已不是 PENDING（例如排程剛好同時在寄） */
		SKIPPED,
		/** SMTP 未設定，沒有嘗試 */
		NOT_CONFIGURED
	}

	/**
	 * 立即寄一封指定的 PENDING 信（不等排程、不受 im.mail.enabled 影響），走與排程同一條鎖→寄→標記流程。
	 * DB 例外原樣往外拋
	 */
	public SendOutcome sendNow(long id) {
		if (!dispatcher.isConfigured()) {
			return SendOutcome.NOT_CONFIGURED;
		}
		try {
			Boolean ok = tx.execute(status -> processOne(id));
			if (ok == null) {
				return SendOutcome.SKIPPED;
			}
			return ok ? SendOutcome.SENT : SendOutcome.FAILED_TRY;
		} catch (SmtpUnavailableException e) {
			log.warn("信件 worker：立即寄送 outbox {} 時 SMTP 連不上（{}），不計重試", id,
					e.getCause().getClass().getSimpleName());
			return SendOutcome.UNREACHABLE;
		}
	}

	/** 在交易內處理一封：鎖不到回 null；寄成功回 true；寄失敗（已記 TRY_CNT）回 false */
	private Boolean processOne(long id) {
		MailOutboxRow row = outboxDao.lockPending(id);
		if (row == null) {
			return null;
		}
		int tryNo = (row.getTryCnt() == null ? 0 : row.getTryCnt()) + 1;
		try {
			SendResult result = dispatcher.send(row);
			if (smtpUnreachable.compareAndSet(true, false)) {
				log.info("信件 worker：SMTP 已恢復連線");
			}
			outboxDao.markSent(id, result.messageId(), result.note());
			if (result.note() == null) {
				log.info("信件 worker：outbox {} 已寄出（第 {} 次）", id, tryNo);
			} else {
				log.warn("信件 worker：outbox {} 已寄出但部分收件人未寄達（第 {} 次）", id, tryNo);
			}
			return true;
		} catch (DataAccessException e) {
			// markSent 失敗屬 DB 問題，往外拋讓交易 rollback、本輪停止
			throw e;
		} catch (RuntimeException e) {
			if (isConnectFailure(e)) {
				// 連線階段就失敗＝信根本沒送出；不記 TRY_CNT，交易只有鎖、rollback 即釋放
				throw new SmtpUnavailableException(e);
			}
			boolean reachedLimit = tryNo >= properties.getMaxTry();
			outboxDao.markFailedTry(id, errorText(e), properties.getMaxTry());
			log.warn("信件 worker：outbox {} 寄送失敗（第 {} 次，{}）：{}", id, tryNo,
					reachedLimit ? "已達上限、標 FAILED" : "稍後重試", e.getClass().getSimpleName());
			return false;
		}
	}

	/**
	 * 是否為 SMTP 連線階段失敗（信沒有送出）。沿 cause 鏈找，MailSendException 另看各封的失敗原因；
	 * 讀寫逾時不算（可能已寄達，照一般失敗計重試，避免無限重寄）
	 */
	static boolean isConnectFailure(Throwable e) {
		Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		java.util.Deque<Throwable> todo = new java.util.ArrayDeque<>();
		todo.add(e);
		while (!todo.isEmpty()) {
			Throwable t = todo.poll();
			if (t == null || !seen.add(t)) {
				continue;
			}
			// MailConnectException 屬 Angus Mail 實作（執行期才有），用類別名比對、不在編譯期依賴實作套件
			if (MAIL_CONNECT_EXCEPTION.equals(t.getClass().getSimpleName()) || t instanceof ConnectException
					|| t instanceof UnknownHostException || t instanceof NoRouteToHostException) {
				return true;
			}
			if (t instanceof MailSendException mse) {
				for (Map.Entry<Object, Exception> entry : mse.getFailedMessages().entrySet()) {
					todo.add(entry.getValue());
				}
			}
			if (t.getCause() != null) {
				todo.add(t.getCause());
			}
		}
		return false;
	}

	/** 例外類別＋訊息（含 SMTP 回應），有 cause 時再附一層；email 形狀的字串遮成 ***；截到欄位長度 */
	static String errorText(Throwable e) {
		StringBuilder sb = new StringBuilder(e.getClass().getSimpleName());
		if (e.getMessage() != null && !e.getMessage().isBlank()) {
			sb.append(": ").append(e.getMessage().trim());
		}
		Throwable cause = e.getCause();
		if (cause != null && cause != e) {
			sb.append(" <- ").append(cause.getClass().getSimpleName());
			if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
				sb.append(": ").append(cause.getMessage().trim());
			}
		}
		String text = EMAIL_LIKE.matcher(sb.toString().replaceAll("[\\r\\n]+", " ")).replaceAll("***");
		return text.length() <= ERROR_MAX ? text : text.substring(0, ERROR_MAX);
	}

	private void markDbUnavailable(DataAccessException e) {
		if (dbUnavailable.compareAndSet(false, true)) {
			log.warn("信件 worker：無法存取 DB（{}），暫停到恢復為止", e.getClass().getSimpleName());
		}
	}

	private void markDbRecovered() {
		if (dbUnavailable.compareAndSet(true, false)) {
			log.info("信件 worker：DB 存取已恢復");
		}
	}
}
