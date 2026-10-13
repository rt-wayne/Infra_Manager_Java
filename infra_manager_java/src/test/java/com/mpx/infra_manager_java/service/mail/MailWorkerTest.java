package com.mpx.infra_manager_java.service.mail;

// ============================================================
// AI版本  : Claude Fable 5.1 (claude-fable-5-1)
// 修改日期: 2026-10-08
// 變更說明: 新增：MailWorker 的單元測試（S8 R1；DAO／dispatcher／交易管理器皆 mock）。鎖定：
//           SMTP 未設定時不查 DB、回 0；候選每封各開一個交易；鎖不到跳過；寄成功 markSent 帶 Message-ID 與備註；
//           寄失敗 markFailedTry 帶「例外類別: 訊息」且不中斷下一封；markSent 丟 DB 例外時交易 rollback 並停止本輪；
//           findDueIds 丟 DB 例外時回 0；errorText 截 1000 字、換行變空白、附一層 cause
//           2026-10-08 S8a 階段末 review 第 2、3 項（Claude Opus 5.5）：SMTP 連不上不計重試、交易 rollback、本輪停止，
//           恢復後同幾封照常寄出；連線失敗判斷認得 ConnectException／UnknownHost／NoRouteToHost（含 failedMessages 內），
//           讀寫逾時與 550 拒收不算；errorText 把 email 遮成 ***
// ============================================================

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.mpx.infra_manager_java.config.MailProperties;
import com.mpx.infra_manager_java.dao.mail.MailOutboxDao;
import com.mpx.infra_manager_java.model.mail.MailOutboxRow;
import com.mpx.infra_manager_java.service.mail.MailDispatcher.SendResult;

import jakarta.mail.MessagingException;

class MailWorkerTest {

	private final MailOutboxDao dao = mock(MailOutboxDao.class);
	private final MailDispatcher dispatcher = mock(MailDispatcher.class);
	private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
	private final MailProperties properties = new MailProperties();
	private final MailWorker worker = new MailWorker(dao, dispatcher, properties, txManager);

	@BeforeEach
	void setUp() {
		when(dispatcher.isConfigured()).thenReturn(true);
		when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
		properties.setMaxTry(5);
	}

	private static MailOutboxRow pending(long id, int tryCnt) {
		MailOutboxRow r = new MailOutboxRow();
		r.setMailOutboxId(id);
		r.setTryCnt(tryCnt);
		r.setMailStatusCode(MailOutboxDao.STATUS_PENDING);
		return r;
	}

	@Test
	void 立即寄送_成功失敗連不上鎖不到未設定各自回對應結果() {
		when(dao.lockPending(1L)).thenReturn(pending(1L, 0));
		when(dao.lockPending(2L)).thenReturn(pending(2L, 0));
		when(dao.lockPending(3L)).thenReturn(pending(3L, 0));
		when(dao.lockPending(4L)).thenReturn(null);
		when(dispatcher.send(any())).thenReturn(new SendResult("<id-1@px>", null))
				.thenThrow(new MailSendException("550 rejected"))
				.thenThrow(new MailSendException("connect", new ConnectException("refused")));

		assertThat(worker.sendNow(1L)).isEqualTo(MailWorker.SendOutcome.SENT);
		assertThat(worker.sendNow(2L)).isEqualTo(MailWorker.SendOutcome.FAILED_TRY);
		assertThat(worker.sendNow(3L)).isEqualTo(MailWorker.SendOutcome.UNREACHABLE);
		assertThat(worker.sendNow(4L)).isEqualTo(MailWorker.SendOutcome.SKIPPED);
		verify(dao).markSent(1L, "<id-1@px>", null);
		verify(dao).markFailedTry(eq(2L), anyString(), eq(5));
		verify(dao, never()).markFailedTry(eq(3L), anyString(), anyInt());

		when(dispatcher.isConfigured()).thenReturn(false);
		assertThat(worker.sendNow(5L)).isEqualTo(MailWorker.SendOutcome.NOT_CONFIGURED);
		verify(dao, never()).lockPending(5L);
	}

	@Test
	void SMTP未設定時不碰DB() {
		when(dispatcher.isConfigured()).thenReturn(false);

		assertThat(worker.pollOnce()).isZero();
		assertThat(worker.pollOnce()).isZero();
		verify(dao, never()).findDueIds(anyInt(), anyInt(), anyInt());
	}

	@Test
	void 每封各開交易且成功時markSent() {
		when(dao.findDueIds(20, 5, 1)).thenReturn(List.of(1L, 2L));
		when(dao.lockPending(1L)).thenReturn(pending(1L, 0));
		when(dao.lockPending(2L)).thenReturn(pending(2L, 2));
		when(dispatcher.send(any())).thenReturn(new SendResult("<id-1@px>", null),
				new SendResult("<id-2@px>", "部分收件人未寄達：無效 1 人、未送出 0 人"));

		assertThat(worker.pollOnce()).isEqualTo(2);

		verify(txManager, times(2)).getTransaction(any());
		verify(txManager, times(2)).commit(any(TransactionStatus.class));
		verify(txManager, never()).rollback(any());
		verify(dao).markSent(1L, "<id-1@px>", null);
		verify(dao).markSent(eq(2L), eq("<id-2@px>"), anyString());
		verify(dao, never()).markFailedTry(anyLong(), anyString(), anyInt());
	}

	@Test
	void 鎖不到時跳過() {
		when(dao.findDueIds(anyInt(), anyInt(), anyInt())).thenReturn(List.of(3L));
		when(dao.lockPending(3L)).thenReturn(null);

		assertThat(worker.pollOnce()).isZero();

		verify(dispatcher, never()).send(any());
		verify(dao, never()).markSent(anyLong(), any(), any());
		verify(txManager).commit(any(TransactionStatus.class));
	}

	@Test
	void 寄失敗時markFailedTry且繼續下一封() {
		when(dao.findDueIds(anyInt(), anyInt(), anyInt())).thenReturn(List.of(4L, 5L));
		when(dao.lockPending(4L)).thenReturn(pending(4L, 4));
		when(dao.lockPending(5L)).thenReturn(pending(5L, 0));
		when(dispatcher.send(any())).thenThrow(new MailSendException("550 5.1.1 User unknown"))
				.thenReturn(new SendResult("<id-5@px>", null));

		assertThat(worker.pollOnce()).isEqualTo(1);

		ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
		verify(dao).markFailedTry(eq(4L), err.capture(), eq(5));
		assertThat(err.getValue()).startsWith("MailSendException: ").contains("550 5.1.1 User unknown");
		verify(dao).markSent(5L, "<id-5@px>", null);
		verify(txManager, times(2)).commit(any(TransactionStatus.class));
	}

	@Test
	void markSent丟DB例外時rollback並停止本輪() {
		when(dao.findDueIds(anyInt(), anyInt(), anyInt())).thenReturn(List.of(6L, 7L));
		when(dao.lockPending(6L)).thenReturn(pending(6L, 0));
		when(dispatcher.send(any())).thenReturn(new SendResult("<id-6@px>", null));
		doThrow(new DataAccessResourceFailureException("ORA-03113")).when(dao).markSent(eq(6L), any(), any());

		assertThat(worker.pollOnce()).isZero();

		verify(txManager).rollback(any(TransactionStatus.class));
		verify(dao, never()).lockPending(7L);
		verify(dao, never()).markFailedTry(anyLong(), anyString(), anyInt());
	}

	@Test
	void findDueIds丟DB例外時回零且下一輪照常() {
		when(dao.findDueIds(anyInt(), anyInt(), anyInt()))
				.thenThrow(new DataAccessResourceFailureException("ORA-12541")).thenReturn(List.of());

		assertThat(worker.pollOnce()).isZero();
		assertThat(worker.pollOnce()).isZero();
		verify(dao, times(2)).findDueIds(anyInt(), anyInt(), anyInt());
	}

	@Test
	void SMTP連不上時不計重試並結束本輪_恢復後照常寄() {
		when(dao.findDueIds(anyInt(), anyInt(), anyInt())).thenReturn(List.of(8L, 9L));
		when(dao.lockPending(8L)).thenReturn(pending(8L, 0));
		when(dao.lockPending(9L)).thenReturn(pending(9L, 0));
		// JavaMailSenderImpl 連線失敗的形狀：MailSendException 包 MessagingException、再包 ConnectException
		MailSendException refused = new MailSendException("Mail server connection failed",
				new MessagingException("Couldn't connect to host", new ConnectException("Connection refused")));
		when(dispatcher.send(any())).thenThrow(refused).thenThrow(refused)
				.thenReturn(new SendResult("<id-8@px>", null), new SendResult("<id-9@px>", null));

		assertThat(worker.pollOnce()).isZero();
		assertThat(worker.pollOnce()).isZero();

		verify(dao, never()).markFailedTry(anyLong(), anyString(), anyInt());
		verify(dao, never()).lockPending(9L);
		verify(txManager, times(2)).rollback(any(TransactionStatus.class));

		assertThat(worker.pollOnce()).as("恢復後同兩封照常寄出").isEqualTo(2);
		verify(dao).markSent(8L, "<id-8@px>", null);
		verify(dao).markSent(9L, "<id-9@px>", null);
	}

	@Test
	void 連線失敗判斷認得各種形狀_讀寫逾時與拒收不算() {
		assertThat(MailWorker.isConnectFailure(new MailSendException("x", new UnknownHostException("mail.invalid"))))
				.isTrue();
		assertThat(MailWorker.isConnectFailure(new MailSendException(
				Map.of(new Object(), new MessagingException("y", new NoRouteToHostException("no route")))))).isTrue();
		assertThat(MailWorker.isConnectFailure(new MailSendException("z", new SocketTimeoutException("Read timed out"))))
				.isFalse();
		assertThat(MailWorker.isConnectFailure(new MailSendException("550 5.1.1 User unknown"))).isFalse();
	}

	@Test
	void errorText把email遮掉() {
		RuntimeException e = new MailSendException("Invalid Addresses",
				new MessagingException("550 5.1.1 <someone@pxmart.com.tw>: Recipient address rejected; a.b@x.y"));
		String text = MailWorker.errorText(e);
		assertThat(text).doesNotContain("someone").doesNotContain("pxmart.com.tw").doesNotContain("a.b@x.y")
				.contains("550 5.1.1 <***>: Recipient address rejected; ***");
	}

	@Test
	void errorText截斷換行並附cause() {
		RuntimeException e = new MailSendException("line1\r\nline2", new IllegalStateException("inner"));
		assertThat(MailWorker.errorText(e)).isEqualTo("MailSendException: line1 line2 <- IllegalStateException: inner");

		String longText = MailWorker.errorText(new RuntimeException("y".repeat(2000)));
		assertThat(longText).hasSize(MailWorker.ERROR_MAX);
	}
}
