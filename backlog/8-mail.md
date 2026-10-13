# 第 8 項：S8 信件 outbox（施工計畫）

> 衝刺期規則見 `CLAUDE.md`「基本功能衝刺期」：回合內只跑單元測試＋commit＋push；整合測試（`./mvnw verify`）、
> 手動驗證、`code-reviewer` 都在階段結束一次做。
>
> outbox＝信先寫進 DB 的待寄表 `IM_MAIL_OUTBOX`，再由背景排程（worker）寄出；業務交易不等 SMTP。

## 已拍板
- **2026-10-07**：① 第 56 項＝維持舊系統做法、SMTP 憑證驗證關閉（`mail.smtp.ssl.trust=*`；CHANGELOG 於 S8 結案列出）；② 同意引入 `spring-boot-starter-mail` 與 `spring-boot-starter-thymeleaf`；③ SMTP 連線沿用舊系統 `config/smtp.json`：`mail.pxmart.com.tw`、port 25、不加密、無帳號認證、寄件人 `Infra-Manager@pxmart.com.tw`（值不進版控）
- **2026-10-08 使用者「全部依建議」（architect 開工分析）**：
  - **① SMTP 與信件設定＝B**：連線值放後端 `application.properties` 真檔（`spring.mail.*`＋`im.mail.*`），將來若需認證才把密碼放環境變數 `IM_SMTP_PASSWORD`；PRD「環境設定」已於 S8a 階段末改寫
  - **② 申請人收件 email＝A**：以帳號主檔 `IM_USER.EMAIL` 為準、空的才用填單值 `IM_APP.APPLY_EMAIL`（填單值只當聯絡資訊，不當收件依據）
  - 測試信箱 `ciliao@pxmart.com.tw`；驗收時 `im.mail.override-to` 填它，所有收件人改寄到此
  - S8 切成 **S8a（後端寄信，R1～R3）** 與 **S8b（admin 信件頁與重寄，R4～R5）** 兩個階段，各自跑 verify／review／PRD／CHANGELOG

## 範圍與做法（architect 建議，Claude 照做；使用者可推翻）
- **寄信事件 10 個、樣板 6 份**（不是任務摘要寫的 8 個：「退回」拆執行端與治理、補上「補件重送」）：

  | # | 事件 | 掛點 | 收件人 | 主旨 | 樣板 |
  |---|---|---|---|---|---|
  | 1 | 送審 | `AppFlowService.submit`，`startApproval` 之後 | 第一個待簽關卡候選人 | `[待簽核] {單號} {標題} - {關卡}` | step-pending |
  | 2 | 補件重送 | `resubmit`，`startApproval` 之後 | 新版第一個待簽關卡候選人 | 同上 | step-pending |
  | 3 | 進下一關 | `decide` 同意且 `activateNext>0` | 新關卡候選人 | 同上 | step-pending |
  | 4 | 末關同意 | `decide` 同意且 `activateNext==0` | 只有申請人 | `[核准完成] {單號} {標題}` | approved（附簽核紀錄） |
  | 5 | 簽核退件 | `decide` 退件 | 退件群組 | `[退件] {單號} {標題} - {關卡名}` | rejected |
  | 6 | 撤回 | `recall`，**closeOpenSteps 之前先查收件人** | 撤回當下待簽關卡候選人 | `[撤回通知] {單號} {標題} - 申請人撤回修改` | recalled |
  | 7 | 送治理審查 | `AppExecutionService.save`，`submit` 為真 | 所有啟用中 governance | `[待審核執行結果] {單號} {標題}` | gov-review-request |
  | 8 | 執行端退回 | `reject` | 退件群組 | `[退件] … - 執行階段` | rejected |
  | 9 | 治理退回 | `review` RETURN | 退件群組 | `[退件] … - 資訊治理審核` | rejected |
  | 10 | 結案 | `review` PASS | 只有申請人 | `[執行結果已通過] {單號} {標題}`（舊主旨的 emoji 拿掉） | gov-passed |

  - 退件群組（舊 `notifyRejectAll`）＝申請人＋該版所有關卡候選人＋實際簽核人＋執行人＋啟用中 governance；email 不分大小寫去重
  - 刪除單不寄信（舊系統也不寄）；不 CC `ADMIN_EMAIL`；末關同意不加寄 idc_admin
  - 收件人來源：候選人 `IM_APPR_CAND_MAP` JOIN `IM_USER`；簽核人 `IM_APPR_STEP.USER_ID`；執行人 `IM_APP_EXEC.USER_ID`；governance 比照 `ApprovalWriteDao` 角色 JOIN（帳號與角色都 `STATUS=1`）；一律只取 `IM_USER.STATUS=1` 的 email（已停用不寄；撤權策略第 104 項 N6 不碰）
  - 沒 email 的人略過；整封 0 個收件人就不寫 outbox，只記 info（單號＋事件類型）
  - 只通知關卡（`IS_NOTIFY_ONLY`）：R2 查證結果——舊系統 `notifyOnly` 旗標沒有任何寄信效果、新系統在 `insertSteps` 就標 SKIPPED，**不寄、不做知會樣板**（BACKLOG 第 110 項 ④）
- **交易邊界＝方案 B**：enqueue（寫 outbox）在業務 `@Transactional` 內同 commit；收件人查詢與樣板渲染的例外在 enqueue 內 catch、記 warn（事件類型＋單號＋例外類別）後跳過；INSERT 失敗照樣往外拋跟業務一起 rollback。**enqueue 方法不掛 `@Transactional`**（例外穿過交易代理會標成 rollback-only）
- **worker**：`config/MailSchedulingConfig`（`@EnableScheduling`＋`@ConditionalOnProperty(im.mail.enabled=true)`，預設關、verify 與開發機不啟動）；`@Scheduled(fixedDelay=30s)` 每輪最多 20 封；兩步鎖——先不鎖查候選 ID（PENDING、`TRY_CNT<上限`、已過退避 `UPDATE_DATE + TRY_CNT×1 分鐘`），再逐封開交易 `SELECT … FOR UPDATE SKIP LOCKED`，寄完同交易更新 SENT 或 TRY_CNT+1（Oracle 不能 `FOR UPDATE`＋限筆數同句，ORA-02014）；重試上限 5 次後 FAILED；`mail.smtp.sendpartial=true`（部分無效收件人仍記 SENT、`ERROR_TEXT` 註明無效人數）；connection／read／write timeout 15 秒；DB 或 SMTP 連不上只在第一次失敗與恢復時各記一次 log；**SMTP 連線階段失敗（連不上主機）不算重試、交易 rollback、結束本輪**，斷線多久信都留在 PENDING、恢復後自動補寄（2026-10-08 階段末 review，原設計是連不上也 TRY_CNT+1）；讀寫逾時與 SMTP 拒收照常計重試；寄送當下若 `override-to` 有值，收件人一律換成改寄地址、清 cc／bcc（寫入時也會改寄，兩道防線）；`ERROR_TEXT` 只存例外類別＋SMTP 回應、email 形狀字串遮成 `***`、截 1000 字；`CREATE_BY` 操作人工號、worker 更新 `UPDATE_BY='SYSTEM'`；語意「至少寄一次」（不改 DDL 的已知取捨）
- **設定鍵**：`spring.mail.host`／`port`／`properties.mail.smtp.*`（Boot 內建 JavaMailSender）；`im.mail.enabled`（預設 false）、`from`、`site-url`（信內連結 `{site-url}/#/apps/{單號}`，取代舊系統用 Host header 組網址）、`override-to`、`batch-size`、`max-try`。R1 加完套件先確認 Boot 4 的屬性前綴仍是 `spring.mail.*`
- **樣板**：`src/main/resources/templates/mail/`：`step-pending.html`、`rejected.html`（三種退回共用，關卡名用參數）、`approved.html`、`recalled.html`、`gov-review-request.html`、`gov-passed.html`＋`fragments/detail.html`（照舊 `mailer.js` `renderDetailSection`）；只用 `th:text`、禁 `th:utext`；頁尾「本信件由「{SITE_NAME}」自動發送」；主旨 CR/LF 換空白、截 500 字。AI 兩份樣板併第 17 項
- **admin API**（S8b）：`GET /api/admin/mail?status=&from=&to=&q=&page=`（每頁 20、預設近 7 天、`q` 比對主旨、不回 HTML、LIKE 跳脫）；`GET /api/admin/mail/{id}`（含 HTML、收件人、錯誤、meta）；`POST /api/admin/mail/{id}/resend`（只限 FAILED → PENDING、TRY_CNT=0，否則 409）；`POST /api/admin/mail/test` `{to, template, appId?}`（寫 outbox 並同步寄一次，回 `{mailId,status,error}`）。權限 controller 類別 `@PreAuthorize("hasRole('admin')")`，**不在 `SecurityConfig` 另加 `/api/admin/**` URL 規則**（會繞過 `AUTHORITY_PWD_OK`）
- **前端**（S8b）：`AdminMailView`（列表＋篩選＋`AppPager`）、`MailDetailView`（`<iframe sandbox="" :srcdoc>`）、`MailTestView`（顯示 host／port／from／開關，不顯示密碼）；路由 `/admin/mail`、`/admin/mail/:id`、`/admin/mail/test`；首頁只對 admin 顯示入口
- log 規則：email 地址視同個資，log 只記 outbox ID、單號、收件人數，不記地址
- 風險：CLOB 用 `Types.CLOB` 綁定（`TO_JSON` 有 IS JSON 檢查）；一封信可能數百 KB，列表 API 不回 HTML；`AppFlowService`／`AppExecutionService` 建構子多一個相依，既有手動 new 的測試要改；`DbClient` 是否支援集合參數未查證，收件人查詢寫 join 型避開 IN 清單；iframe srcdoc 繼承父頁 CSP（第 83 項已登記）

## 回合
### S8a 後端寄信
| 回合 | 內容 | 狀態 |
|------|------|------|
| R1 | 基礎設施：`pom.xml` 加兩套件；`config/MailProperties`、`MailSchedulingConfig`；`dao/mail/MailOutboxDao`（寫入、查候選、逐列鎖、標 SENT／重試／FAILED）；`model/mail/*`；`service/mail/MailOutboxService`（enqueue）、`MailDispatcher`（寄送包裝）、`MailWorker`；`application.properties.example` 加鍵；單元測試（worker 狀態轉移，SMTP mock）；`MailOutboxDaoIT`（寫好不跑）。若做到 DAO＋enqueue 已過半，worker 拆 R1b | 完成（2026-10-08；worker 未拆、一回合做完；另加 `findById` 供 S8b 用、`im.mail.retry-backoff-minutes`／`initial-delay-ms` 兩鍵） |
| R2 | 6 份樣板＋明細片段；`dao/mail/MailRecipientDao`（join 型）；`service/mail/MailNotifier`（組信）；`AppFlowService` 接事件 1～6；先查證只通知關卡；單元測試（標題帶 `<script>` 被轉義、去重、0 收件人不寫入）；修既有 AppFlowService 測試 | 完成（2026-10-08；只通知關卡舊系統也沒寄 → 不做知會樣板，登記 BACKLOG 第 110 項 ④；另加 `im.mail.site-name` 鍵；事件 7～10 的 `MailNotifier` 方法已寫好、R3 只接呼叫） |
| R3 | `AppExecutionService` 接事件 7～10；IT 補「業務 rollback 時 outbox 也沒資料」 | 完成（2026-10-08；退件的簽核實例 ID 由 `MailNotifier.rejectedAtVersion` 在組信時用既有 `ApprovalDao.findCurrent` 查，查不到用 -1、失敗只略過；rollback IT 寫在 `MailOutboxDaoIT`、階段末跑） |
| 階段末 | `./mvnw verify`；`code-reviewer`；本機真檔 `im.mail.enabled=true`＋`override-to=ciliao@`，經 3201 走流程，**待簽核、退件、核准完成三種信寄到測試信箱**；PRD／CHANGELOG 補齊（CHANGELOG 列第 56 項憑證驗證維持關閉、舊系統 Host header 組連結已修）；PRD 註明「舊系統 admin 可撤回、新系統只限申請人」 | 完成（2026-10-13：verify 40 IT 全綠、`code-reviewer` 阻擋 3 項已修 `1c3ec0a`、非阻擋登記 BACKLOG 第 111 項；實寄驗收改由 Claude 經 API 觸發（裁示 ②A），9 封全部 SENT、使用者收信確認；PRD／CHANGELOG 已補，CHANGELOG 2026-10-13 S8a 列） |

### S8b admin 信件頁與重寄
| 回合 | 內容 | 狀態 |
|------|------|------|
| R4 | `controller/admin/AdminMailController`、`service/mail/AdminMailService`、DAO 補查詢；WebMvcTest（非 admin 403、預設密碼 403、重寄非 FAILED 409） | 完成（2026-10-13；另加 `GET /api/admin/mail/settings` 給測試信頁顯示設定（無密碼）；測試寄信 appId 必填（同舊系統）、樣板限待簽核／退件／核准完成、SMTP 未設定直接 409 不寫 outbox；DbClient 拿不到主鍵，改在 META_JSON 放一次性 `testToken` 找回再 `MailWorker.sendNow` 同步寄一次；列表 q 只比對主旨、只回收件人數不回地址；重寄保留 ERROR_TEXT 供對照；`MailOutboxDaoIT` 補 3 案例寫好未跑；單元 445 全綠） |
| R5 | 前端：`types/mail.ts`、`api/adminMail.ts`、三頁、路由、`HomeView` 入口；型別檢查＋build；1024 寬不橫捲 | 待開工 |
| 階段末 | verify；`code-reviewer`；**重寄驗收（2026-10-08 使用者裁示 ①A：維持「SMTP 連不上不計重試」，連不上時信停 PENDING、恢復後自動寄出）**：`im.mail.override-to` 暫改成會被 SMTP 拒收的地址 → 重試 5 次達上限 → FAILED → 改回 → 按重寄 → SENT；若公司 SMTP 先收下再退信、造不出 FAILED，改用 JDBC 把一封種子信標成 FAILED 再測重寄。另驗「連不上 → 一直 PENDING → 改回 → 自動寄出」；測試信頁實寄一封；PRD／CHANGELOG 補齊 | 待開工 |
