# Infra Manager（Java 版）

> 遠端倉庫：https://github.com/rt-wayne/Infra_Manager_Java.git（預設分支 `main`；已封存唯讀）
> **本專案已停止開發（2026-10-16）**：使用者裁示原 Infra_Manager 改整併進「機房與帳號權限管理系統」（repo `rt-wayne/im_acct_insp`，即將改名 `rt-wayne/infra_manager`），以該專案為底、本專案的業務層與 DDL 當搬移來源（該專案 BACKLOG 第 69 項）。本檔僅供搬移時參考。
> 本檔描述**已拍板的目標系統**。標「待定，見 BACKLOG.md 第 N 項」的部分尚未裁示，以 BACKLOG 為準。

## 專案概述

機房設備異動申請系統：把機房設備異動（上下架、搬遷、維護等）的紙本申請與簽核流程數位化。申請人線上填寫異動申請單（含設備、機櫃位置、施工計畫、風險與回退方案），系統依簽核流程逐關送審，核准後由機房端執行並填寫檢核表，最後由治理單位審查結案；全程寄信通知，並可用 AI 預先審查申請單內容。使用者為公司內 IT 各單位申請人、各級主管、機房管理員與治理審查人員。

本專案是 Node 版（`D:\ai\Infra_Manager`，Express + EJS + JSON 檔儲存）的重寫版，改為 Spring Boot REST API + Vue 3 SPA + Oracle 19c。舊 Node 專案完全不動，切換後保留唯讀。

## 背景與動機

- 舊系統以 JSON 檔儲存，已出現實際資料問題：申請單編號用「當天檔案數 + 1」產生，刪單後新單會撞號並**覆蓋既有單**；時間欄位存台灣時間卻標 `Z`（「假 UTC」，每筆多 8 小時，信件再加 8 小時）；存取紀錄高併發時遺失。改用關聯式資料庫（Oracle 19c）與 DB 計數器根治
- 公司未來還有兩個模組（帳號權限管理、機房巡檢）要做，需要可共用的簽核引擎與待辦資料模型。這兩個模組**本次不做**，但簽核引擎（`IM_FLOW`／`IM_FLOW_STEP` 流程定義與 `IM_APPR` 系列簽核實例表：`DOC_TYPE` 區分單據類型、`STEP_MODE_CODE = POST_HOC` 支援事後補核、`IM_APPR_STEP_ITEM` 支援逐項簽核）與附件表（`IM_ATTACH.OWNER_TYPE` 區分擁有者）要留得住擴充
- 資料庫依公司《DB規範_V1_20261002_JLA》16 條撰寫，技術棧依公司技術規範選定，讓 DBA 與維運單位能用公司標準審查與接手
- 帳號權限管理模組管的是「其他系統的帳號」（主機、資料庫、應用系統、服務、網路設備、廠商、憑證金鑰、門禁卡八類），不是本系統的 RBAC，所以本系統的角色只需預留「簽核角色池可擴充」
- 巢狀資料採「混合式」放法：常查欄位攤平成欄位、簽核鏈／版次／附件／AI 對話拆子表、AI 報告與範本表單留 JSON——兼顧查詢效能與遷移成本

---

## 第一區塊：功能說明（所有人適用）

### 功能清單

- **登入與帳號**：用帳號密碼登入，帳密存在本系統資料庫（2026-10-06 裁示；AD／LDAP 之後再評估，見 BACKLOG.md 第 84 項）。切換上線時全員密碼重設為「帳號小寫」並標記為預設密碼，第一次登入會被強制改密碼才能使用其他功能；新密碼規則為至少 6 字、不得全為空白、不得等於帳號（不分大小寫）或舊密碼（預設密碼本身不受 6 字限制，帳號 5 個字的人預設密碼就是 5 個字）。沒有「記住我」，關閉瀏覽器或閒置 8 小時後要重新登入（日後要做見 BACKLOG.md 第 85 項）
- **首頁**：看申請單統計與「我的待辦」（等我簽核的單）；並顯示系統狀態（後端服務與資料庫是否正常、後端時間），可按「重新檢查」
- **申請單列表**：預設顯示近 90 天（只在起訖日都沒填時套用），可依狀態、優先等級、來源、只看我的、關鍵字（單號／標題／作業主旨／申請人，上限 100 字）、建立日期區間篩選；待我簽核的單排在最上面；每頁 20 筆；須登入才能看（2026-10-06 裁示，全部頁面皆須登入）；列表是否顯示 AI 摘要與 AI 費用待 BACKLOG.md 第 17 項（AI 審查去留）定案
- **新增／編輯申請單**：填寫基本資料、異動類別、原因、影響範圍、設備清單、機櫃 U 位、施工步驟、排程、風險評估與回退方案；可套用範本；可上傳附件（拖放、貼上截圖、上傳前預檢）；先存成草稿。目前已上線：新增與編輯草稿（只有申請人能編輯）、附件逐檔上傳（上傳前預檢副檔名、大小與檔數）、新增時套用範本（表單上方選範本就地帶入，或從範本列表按「建單」）；尚未提供機櫃 U 位選擇（S13，之前設備位置只能填「不適用＋原因」或留空）、附件拖放與貼上截圖（BACKLOG.md 第 98 項），草稿附件不能刪除（與舊系統相同）
- **送審與撤回**（已上線）：申請人或 admin 送審草稿，系統先檢查必填（申請單位、聯絡電話、Email、作業主題、至少一位執行人員、遠端作業的連線方式、委外時的廠商資料，一次列出全部缺漏），再依當下的流程政策展開簽核關卡與候選人（候選人在送審當下固化，之後角色異動不影響；申請人本人不列為候選人）；還沒有任何一關簽過之前，申請人可撤回成草稿（可填原因）；只有申請人本人能撤回，admin 不行（舊系統 admin 也能撤回）。AI 審查啟用時送審前須有相符報告，待 BACKLOG.md 第 17 項定案後才接上
- **簽核**（已上線）：目前關卡的候選人可同意或退件；同意不填意見時記為「同意」，退件必填意見；同意後進下一關、末關同意即核准；退件結束這一輪簽核、尚未輪到的關卡標記略過。兩人同時簽同一關只有一人成功，另一人被提示重新載入。簽核時附檔見 BACKLOG.md 第 101 項
- **退件補件**：被退件的單由申請人修改後補件，產生新版次並直接重新進入審核；舊版次的簽核紀錄在檢視頁查得到，舊版表單內容凍結保存在 DB（畫面上檢視舊版內容尚未提供，見 BACKLOG.md 第 106 項）
- **執行**（已上線）：核准後由機房管理員（idc_admin）或申請人本人填寫執行檢核表（檢核項選項，目前 11 項）與實際執行紀錄（實際起訖、結果、異常與說明、後續追蹤與說明、備註）。每個檢核項的執行人可以「填入我自己」，或填自由文字（廠商、多人等沒有系統帳號的執行者，限 200 字），兩者至多擇一；已登記為別人的項目可以原樣保留，但不能改登記成另一位系統使用者。可以先「暫存」（單進入執行中，不存結果），也可以選好執行結果後「完成並送治理審查」（單進入待治理審查，送出後不能再改執行紀錄）；送審前必須有實際起訖時間，勾了異常／後續追蹤就要填說明，缺漏一次列出；11 項不必全勾。執行端可在待執行或執行中把單退回給申請人（必填意見），申請人補件後整張單重新簽核
- **治理審查**（已上線）：資訊治理人員（governance）在檢視頁審核待治理審核的單：通過即結案（意見可不填），退回則回到退件狀態（必填意見），申請人補件後整張單重新簽核與執行。審查人是申請人或該單執行人時不阻擋
- **信件通知**（寄信已上線；本功能分批施工中，進度見 `BACKLOG.md` 第 8 項）：流程走到下列時點，系統自動寄 Email——送審、補件重送、同意後進到下一關時，通知該關可簽核的人「待簽核」；最後一關同意時通知申請人「核准完成」；簽核退件、執行端退回、治理退回時，通知申請人、這一輪所有關卡的候選人與已簽的人、執行人、資訊治理人員「退件」；申請人撤回時通知當下那一關可簽核的人；執行結果送審時通知資訊治理人員「待審核執行結果」；治理通過時通知申請人「執行結果已通過」。信裡有申請單明細與「檢視申請單」連結。只通知關卡不寄信（舊系統也不寄）、刪除申請單不寄信；帳號已停用或沒有 email 的人收不到。信先存進系統再由背景排程寄出，不拖慢操作；郵件主機暫時連不上時信會留著、恢復後自動補寄；被郵件主機拒收會隔一段時間重試，累計 5 次（預設值）仍失敗就標記為失敗
- **刪除**：申請人（符合條件時）或 admin 可刪除申請單，需輸入單號確認；刪除為軟刪除，編號不再被重用
- **AI 審查**（是否保留待定，見 BACKLOG.md 第 17 項）：對申請單做 AI 預審，內容沒變就略過、可強制重審；AI 執行在背景進行，畫面輪詢結果；可對報告追問；admin 可寄出報告
- **範本**（已上線）：把常用的申請單內容存成範本，新增申請單時一鍵帶入。範本存標題、優先等級、作業方式與內容、類別／原因／範圍、設備、作業步驟、預估工時，不存申請人聯絡資料與預定開始／結束時間；範本名稱必填、標題可空。任何登入者都能新增、檢視、套用範本；只有建立者本人與系統管理員能修改、刪除（舊系統任何人都能改刪別人的，已修正）。刪除後不能再套用。列表顯示每份範本的套用次數與最後套用的時間和人，用範本建出的申請單第一次存檔時才算一次。套用時已停用的選項自動拿掉，表單已填過內容會先確認再覆蓋
- **統計**：依日／週／月分桶統計申請單
- **機櫃盤點**：從 Impact 系統取得機櫃與設備資料（快取，背景刷新），在新增申請單時用機櫃 U 位視覺化選擇器選位置並帶入設備
- **後台管理（admin）**：使用者管理（新增、修改、停用；使用者只能停用、不能刪除）、簽核流程管理、外寄信件紀錄與失敗重寄、測試信、系統設定（workflowPolicy、上傳限制、排除記錄的 IP）、表單設定維護（優先等級、設備類別、申請原因、影響範圍、檢核項等選項存在資料庫，admin 可在後台維護）、存取紀錄查詢
- **權限範圍**：未登入可看哪些頁面待定，見 BACKLOG.md 第 26 項；舊系統的已知權限漏洞照搬或修正待定，見 BACKLOG.md 第 30 項

### 使用者流程

1. 申請人登入 → 新增申請單（可套用範本、選機櫃位置、上傳附件）→ 存成草稿（`DRAFT`）
2. （AI 審查啟用時）執行 AI 審查，取得與目前內容相符的報告
3. 送審 → 進入審核（`IN_REVIEW`），系統依該單的簽核流程（`full`、`p2_high`、`p1_emergency`）逐關通知候選人
4. 每一關候選人同意 → 進入下一關；任一關退件 → `REJECTED`，申請人修改後補件 → 新版次重新審核
5. 全部關卡同意 → 核准（`APPROVED`，待執行）→ 機房端第一次暫存執行紀錄 → 執行中（`IN_EXECUTION`）
6. 機房端填寫檢核表與實際執行紀錄並選執行結果送出 → 待治理審核（`PENDING_REVIEW`，核准後可不經暫存直接送出）；待執行或執行中時執行端也可退回 → `REJECTED`
7. 治理人員審查：通過 → 結案（`EXECUTED`）；退回 → `REJECTED`
8. 送審、進下一關、核准、退件、撤回、送治理審查、結案時，系統寄信通知相關人員（見功能清單「信件通知」）

括號內為申請單狀態代碼（`IM_APP.APP_STATUS_CODE`，共七種）；刪除不是狀態，見「資料結構」的 `IM_APP`。

### 畫面說明

Vue 3 SPA，共 19 頁：

| 頁面 | 用途 |
|---|---|
| LoginView | 登入 |
| ChangePasswordView | 改密碼（預設密碼登入後強制導向） |
| HomeView | 首頁統計、我的待辦、系統狀態（後端服務／資料庫「正常」或「無法連線」） |
| AppListView | 申請單列表與篩選；單號是連到檢視頁的連結 |
| AppFormView | 新增／編輯草稿／補件共用表單，內含機櫃 U 位選擇器、設備可編輯表格、附件上傳。目前已上線新增（`/apps/new`）、編輯草稿（`/apps/:id/edit`）與補件（`/apps/:id/resubmit`），規格見「前端架構」；新增模式上方有範本下拉（清單載入失敗仍可直接填，`?template=<id>` 進頁即套用；套用就地帶入、不重載頁面，保留申請人聯絡資料與開始／結束時間，存檔時以 `templateId` 累計次數）；機櫃選擇器尚未做 |
| AppViewView | 檢視申請單（`/apps/:id`）；送審、撤回、簽核、刪除、AI 審查與追問、寄出報告。目前已上線：唯讀檢視（仿紙本表格呈現基本資料、作業內容、類別（「其他」補充顯示為「類別（其他）：說明」）、原因、範圍、設備、步驟、排程、位置、檢核表、執行紀錄（未填結果時異常／後續追蹤顯示「—」）、簽核關卡、附件索引、歷史版次與歷次簽核、事件）；動作鈕依 `permissions` 顯示——「編輯草稿」導到編輯頁、「補件」導到補件頁，「填寫執行紀錄」導到執行頁，「送審」「撤回到草稿」「簽核」「治理審核」「刪除申請單」已接真 API（規格見「前端架構」的「檢視頁流程動作」），其餘提示「此功能尚未開放」；附件下載鈕先以 HEAD 確認檔案存在（404 toast「找不到附件檔案」），再交給瀏覽器原生下載（不整檔讀進記憶體）；查無單號顯示「找不到申請單」 |
| ExecuteView | 填寫執行紀錄（`/apps/:id/execute`）：執行檢核表（每項執行人「填入我自己」或填自由文字）、實際執行紀錄、暫存、完成並送治理審查、退回給申請人；規格見「前端架構」的「執行頁」。治理審查不另開頁，在 AppViewView 的「治理審核」面板操作 |
| TemplateListView／TemplateEditView | 範本列表（`/templates`，每頁 20 列；每列「建單」導到 `/apps/new?template=<id>`，可編輯者才有修改／刪除，刪除先確認）與新增／修改（`/templates/new`、`/templates/:id/edit`；表單一～四區塊與 AppFormView 共用 `components/DraftFields.vue`，範本模式不顯示申請人聯絡資料與開始／結束時間；無權修改時不給表單） |
| StatsView | 統計 |
| AccessLogView | 存取紀錄（admin） |
| AdminUsersView | 使用者管理 |
| AdminWorkflowsView | 簽核流程管理（關卡可拖曳排序） |
| AdminMailView／MailDetailView／MailTestView | 外寄信件紀錄、信件內容、測試信 |
| AdminSettingsView | 系統設定 |
| AdminFormSchemaView | 表單設定維護（`IM_FORM_OPTION` 的選項） |
| 403／錯誤頁 | 無權限與錯誤提示 |

畫面上的按鈕是否顯示，依後端在申請單 DTO 內回傳的 9 個 `canX` 權限旗標決定，前端不自行判斷權限。

網頁之外，使用者還會收到系統寄的 Email（HTML 信）：主旨以「[待簽核]」「[核准完成]」「[退件]」「[撤回通知]」「[待審核執行結果]」「[執行結果已通過]」開頭，內文是申請單明細表與檢視連結（連到 AppViewView），頁尾註明「本信件由「系統名稱」自動發送，請勿直接回覆」與發送時間。信件不能回覆操作，所有動作都要回到網頁做。

---

## 第二區塊：技術規格（工程師適用）

### 技術架構

#### 技術選型
以公司兩個開發範本為底：後端用 dev_template_jdk25、前端用 web_template_3.5。兩個範本各自的開發規範在 `infra_manager_java/README.md` 與 `infra_manager_web/README.md`（三條鐵律、標準分層、properties 分工、建置與執行、檢查清單），本檔不重複，只記本系統在範本之上的選擇。

| 層 | 技術 |
|---|---|
| 後端 API | `infra_manager_java/`：Java 25 LTS（Temurin）+ Spring Boot 4.1.1，port 3202 |
| 前端殼 jar | `infra_manager_web/infra_manager_web/`：Spring Boot 4.1 / Java 25，port 3201，context path `/infra_manager_web`；服務前端靜態檔並轉發 API |
| 前端 | `infra_manager_web/infra_manager_web_frontend/`：Vue 3.5 + TypeScript + Vite 8 + vue-router（hash 模式）+ axios；Node 24；不使用 UI 元件庫，沿用範本 `src/assets/main.css` 的色票與基礎字級 |
| 建置工具 | Maven，一律透過各目錄的 Maven Wrapper（`mvnw`）執行；前端用 npm |
| 資料存取 | 範本 `com.mpx.common.db` 的 `DbClient`（`query`／`update`，`:name` 具名參數）；**無 JPA、無 Flyway** |
| 認證 | Spring Security 7.1.1（`spring-boot-starter-security`，版本由 Boot BOM 管理；2026-10-06 裁示 ⑥A）：server-side session＋CSRF＋bcrypt，不另引入其他密碼學套件；測試用 `spring-boot-starter-webmvc-test`／`spring-boot-starter-security-test` |
| 資料庫 | Oracle 19c；建表與改表一律由開發方提供 SQL 檔（放 `db/oracle/`），由使用者以 `rd_user` 手動執行 |
| JDBC 驅動 | ojdbc17 + orai18n（中文字元集轉換用），隨範本 pom 引入。範本 pom 另附帶 `mssql-jdbc`，本系統不使用、不移除（見「給範本維護者的註記」第 3 點） |
| DB 連線資訊 | 執行期由範本向公司「DB 連線資訊 API」依別名取得（jdbcUrl／帳密不在程式、設定檔或環境變數），並建 Hikari 連線池 |
| 測試 | 後端 JUnit 5（單元測試由 surefire 跑、`*IT` 整合測試由 failsafe 跑）；前端 Vitest（jsdom）；端對端 Playwright |
| 測試 DB | 直接連使用者提供的那台公司 Oracle 19c 測試環境（不用 H2、不用 Testcontainers）；連線資訊同樣由連線資訊 API 提供，不進 repo |
| 部署 | Docker 容器，主機作業系統 Rocky Linux 9.7 |

舊 Node 系統仍在 port 3200 執行，供對照。

#### 資料庫帳號
只列帳號名稱與用途；密碼與連線字串不寫進 repo 內任何檔案。

| 帳號 | 用途 |
|---|---|
| `rd_user` | 具建表權限，用來執行 DDL；表建在此帳號的 schema 下 |
| `ap_user` | 應用程式連線用；對 V1 的 31 張表與交易測試表 `IM_TX_TEST` 只有 SELECT／INSERT／UPDATE／DELETE 權限，由 `rd_user` 授權；應用程式以 schema 前綴（`rd_user.表名`）存取（授權 SQL 為 `db/oracle/grant_ap_user.sql`，不建同義詞）。正式 DAO 的 SQL 怎麼帶 schema 前綴待定，見 BACKLOG.md 第 77 項 |

連線用哪個帳號，由 `config/database.properties` 的 `db.connect.itflow` 所設的別名在連線資訊 API 端決定。

#### Oracle 19c 連動規則（全系統適用）
- 空字串 `''` 在 Oracle 等於 NULL → 規則為「DB 存 NULL、API 回 `""`」，在資料存取層（DAO／model）統一處理
- JSON 欄位用 `CLOB` + `CHECK (col IS JSON)`（19c 沒有原生 JSON 型別），欄名後綴 `_JSON`
- 字元集 AL32UTF8、字串長度模式 STANDARD：一個中文字佔 3 位元組，`VARCHAR2` 上限 4000 位元組（約 1333 個中文字），所以使用者自由輸入的長文欄位用 `CLOB`（簽核意見 `MEMO`、`IMPACT_DESC`、`RESUB_MEMO`、`EXCPT_DESC`、`FOLLOW_UP_DESC`、`VER_REASON`、事件 `MEMO` 等），系統產生的錯誤訊息／query string 限 `VARCHAR2(1000 CHAR)`，寫入前由程式端按字元（code point）截斷。由 API 驗證長度，超過回 400：本次由 `VARCHAR2` 改成 `CLOB` 的 8 欄每欄上限 2000 字（舊資料實測最長 1665 字，為簽核意見，放得下），原本就是 `CLOB` 的 4 欄（`WORK_DETAIL`、`RISK_DESC`、`ROLL_BACK_PLAN`、`EXEC_MEMO`）每欄上限 20000 字（舊資料最長為作業內容 10749 字，另有 21 張單的作業內容超過 2000 字）。字數以 code point 計、換行先統一成 LF 再計算（不直接用 `@Size`，它以 UTF-16 單位計，emoji 會算 2 字），由 `util.TextLength.check` 檢核並回傳正規化後的字串（呼叫端以回傳值寫入 DB）；請求本文上限見「後端分層」的「請求本文上限」。CLOB 欄位不得用在 `DISTINCT`／`ORDER BY`／`GROUP BY`／`=` 比較（Oracle 不允許）
- 布林用 `NUMBER(1)` + `CHECK (col IN (0, 1))`（23ai 以前沒有 boolean），欄名前綴 `IS_`
- Oracle 字串比對分大小寫：`IM_USER.LOGIN_ID` 一律存小寫，由 CHECK 約束強制
- 時間欄位一律 `DATE`，存台灣當地時間（不帶時區）；畫面一律以台灣時區顯示（`SYS_PARAM` 的 `TIME_ZONE` = `Asia/Taipei`）
- 可能含中文的字串欄位用字元語意 `VARCHAR2(n CHAR)`；代碼、鍵值類用位元組語意

#### Repo 結構
單一 repo（`D:\ai\Infra_Manager_Java`）：
```
infra_manager_java/                        後端 API（dev_template_jdk25，port 3202）
infra_manager_web/infra_manager_web/       前端殼 jar（web_template_3.5，port 3201）；src/frontend/ 為前端 build 產物（不進 git，只留 .gitkeep）
infra_manager_web/infra_manager_web_frontend/  Vue 3 SPA 原始碼
db/oracle/   DDL 與授權 SQL，皆由 rd_user 手動執行（見「資料結構」）
db/tools/    gen_table_doc.js（從 V1 DDL 重產給 DBA 審的 xlsx）
docs/db/     Table_List_Schema.xlsx（給 DBA 審的 Table List／Table Schema，由 db/tools 產生）
docs/plan/   規劃文件（歷史文件，不再更新）
start-new.bat  本機一鍵建置並啟動兩個 jar
```

#### 後端分層
- 範本的 `com.mpx.common` 與 `com.mpx.Application` 不修改；業務程式只放 `com.mpx.infra_manager_java` 底下
- 分層依範本：controller（`@RestController`，路徑 `/api/<資源>`）→ service（業務邏輯，交易邊界）→ dao（`@Repository`，注入 `DbClient`，DB 別名以 `@Value("${db.connect.itflow}")` 讀取；表名一律經 `config.DbSchema.table("IM_XXX")` 加 schema 前綴——值來自 `db.schema.itflow`，啟動時以白名單 `[A-Za-z][A-Za-z0-9_$#]{0,127}` 驗證後轉大寫，未設或不合法即啟動失敗；這是後端 README §8「SQL 不拼字串」的唯一例外，因為識別字無法用 `:name` 綁定，2026-10-06 裁示 ②A）→ model（POJO，SQL 欄位別名大寫底線對應 camelCase 屬性）。另有 `config`（Spring 設定）、`web`（filter 與例外處理）、`util`（共用工具）
- 套件切法（2026-10-06 裁示 ①B）：先依層、再依功能——`controller`／`service`／`dao`／`model` 四個頂層套件底下，各自再依功能分子套件：`identity`、`approval`（共用簽核引擎）、`changerequest`、`ai`、`mail`、`inventory`、`audit`（例如 `service.approval`、`dao.changerequest`）。S1 的 `Health*` 只有一個檔，直接放頂層套件
- **交易管理**：範本 `DbClient.update` 為單句 autocommit、不提供 TransactionManager；本系統一張申請單要同時寫多張表，因此在 `config.AppTransactionConfig` 自建 `LazyAliasTransactionManager`（繼承 `DataSourceTransactionManager`）。它疊在範本已建好的 Hikari 連線池上（不另建池），第一次交易時才依 `db.connect.itflow` 別名取池並快取 DataSource，維持範本「連線資訊 API 位址未設仍可啟動」的行為；別名空白時啟動即失敗。`@Transactional` 範圍內的 `DbClient` 呼叫共用同一條連線、一起 commit／rollback
- **請求本文上限**（`config.RequestLimitConfig`，常數寫在程式內）：JSON 等非 multipart 本文 1 MB（`web.BodyLimitFilter`，掛在 `/api/*`；`Content-Length` 已超過就不讀本文、未知長度則邊讀邊計數）、multipart 單檔 50 MB、整個 multipart 請求 500 MB，超過一律回 413。multipart 延後解析（`spring.servlet.multipart.resolve-lazily=true`），只有真的取 `MultipartFile` 參數的端點才會落暫存檔
- **錯誤處理**：`web.ApiExceptionHandler` 統一把例外轉成 `{"message": …}` 回應（格式見「API 規格」），回應一律不帶內部細節；log 只記例外類別名、ORA 錯誤碼、method／path 與未預期例外的堆疊前 10 個 frame，不記例外訊息
- **log 紀律**：log 不得含密碼、jdbcUrl、帳號、SQL 參數值。log 落點依範本（`/home/tomcat/log/infra_manager_java/`，Windows 對應啟動時工作目錄所在磁碟機）
- **簽核引擎**（S7 完成；`service.changerequest.AppFlowService`／`AppSubmitValidator`／`DecisionPolicy`、`dao.changerequest.ApprovalWriteDao`、`controller.changerequest.AppFlowController`；給未來模組重用的共用資料模型見「資料結構」）：
  - **交易與鎖**：送審、撤回、簽核三個動作各是一個 transaction，第一句一律是帶條件的 `UPDATE IM_APP … SET ROW_VER_NO = ROW_VER_NO + 1 WHERE APP_ID 且 ROW_VER_NO = :rowVerNo 且 APP_STATUS_CODE = 來源狀態（且申請人條件）且 STATUS = 1`（`AppWriteDao.transition`），同時當整張單的列鎖；0 列時查現況分流：查無（含已刪）404「找不到申請單」、非當事人 403、狀態不符 409（訊息依動作）、版本不符 409「申請單已在其他地方修改過，請重新載入頁面」。權限一律在鎖內由伺服器端 session 的身分重判，不信任前端 `permissions`；403／400 時版本加一連同其他寫入一起 rollback。第二人等到鎖後 WHERE 以新值重判、必為 0 列 → 409，所以兩人搶簽只有一人成功。成功一律回 200 `{appId, rowVerNo}`（新版本號），前端以此接續操作
  - **送審** `submit`（申請人或 admin；`DRAFT → IN_REVIEW`）：鎖內讀單做必填檢核（`AppSubmitValidator`，照舊表單必填欄：申請單位、聯絡電話、Email、作業主題；至少一位執行人員；處理方式 REMOTE 時連線方式必填；勾委外廠商時執行廠商／廠商聯絡人／廠商電話必填；設備位置不檢核，S13 前不得要求），一次列出全部缺漏 400「送審前請先補齊：申請單位、聯絡電話」→ 依當下 `FLOW_POLICY` 重算並寫回 `IM_APP.FLOW_ID` → INSERT `IM_APPR`（`DOC_VER_NO` 取 `CURR_VER_NO`）→ `INSERT…SELECT` 展開 `IM_APPR_STEP`（序號最小且非只通知的關卡 PENDING、只通知關卡直接 SKIPPED、其餘 WAITING）→ `INSERT…SELECT` 展開 `IM_APPR_CAND_MAP`（ROLE 型取角色、對應、帳號皆啟用者，USER 型取啟用中的指定人；**一律排除申請人 `APPLY_USER_ID`**，admin 代送也排除）→ 任一未結束關卡沒有候選人即 400「第 N 關（關卡名）沒有可簽核的人，請聯絡管理員」並整筆 rollback → 寫 `IM_APP_EVENT` SUBMIT。狀態不是草稿 409「申請單已不是草稿，無法送審，請重新載入頁面」；非申請人且非 admin 403。候選人**只在送審時固化**，之後停用帳號或移除角色不影響既有單（2026-10-07 裁示 ①A 維持；撤權策略留到 S8 或後台使用者管理一起決定，見 BACKLOG.md 第 104 項 N6）。AI 閘門待第 17 項拍板，目前不擋
  - **撤回** `recall`（只限申請人，admin 不可——與舊系統不同，舊系統 admin 也能撤回；`IN_REVIEW → DRAFT`）：已有任一關卡簽核完成（APPROVED／REJECTED）即 409「已有關卡簽核完成，無法撤回」；否則未結束關卡設 CANCELLED、`IM_APPR` 設 RECALLED、寫事件 RECALL（原因選填、放 `MEMO`，全空白存 null）。不在審核中 409「申請單不在審核中，無法撤回，請重新載入頁面」；非申請人 403。撤回後可再送審（`IM_APPR` 唯一索引排除 RECALLED／CANCELLED）
  - **簽核** `decide`（只限目前關卡候選人；`IN_REVIEW → IN_REVIEW`／`APPROVED`／`REJECTED`）：本文 `{rowVerNo, decision: APPROVE|REJECT, memo}`；`decision` 其他值 400「簽核決定只能是同意或退件」；退件未填意見 400「退件請填寫意見」；同意意見空白存「同意」；意見去頭尾空白、上限 2000 字。找序號最小的 PENDING 關卡，候選人以 `IM_APPR_CAND_MAP` 為準（無候選人列時退回流程定義的指定人），不是候選人 403（`DecisionPolicy` 與檢視 API 的 `canDecide` 同一套判斷）。條件式 `UPDATE IM_APPR_STEP … WHERE STEP_STATUS_CODE = 'PENDING'` 寫入決定、簽核人與時間（0 列 409，第二道防線）；同意：下一個 WAITING 關卡改 PENDING，沒有下一關則 `IM_APPR` APPROVED、主檔 `APPROVED`；退件：剩餘 WAITING 改 SKIPPED、`IM_APPR` REJECTED、主檔 `REJECTED`。同意與退件不寫 `IM_APP_EVENT`（事件碼沒有對應代碼，關卡列本身即紀錄）。主檔不在審核中 409「申請單不在審核中，無法簽核，請重新載入頁面」；版本或關卡被搶 409「此關卡已被其他人簽核或申請單已變更，請重新載入頁面」
  - **補件** `resubmit`（S9；只限申請人，admin 也不能代補；`REJECTED → IN_REVIEW`）：本文 `{rowVerNo, resubMemo, form}`，`form` 與草稿編輯本文相同（不含 `rowVerNo`），欄位錯誤的 `field` 路徑同草稿（不加 `form.` 前綴），補件說明超長時 `field` 為 `resubMemo`（上限 2000 字，全空白存 null）。鎖外先驗表單格式並依新優先等級算 `FLOW_ID`；鎖同其他動作（`transition`，申請人條件）；0 列分流：查無 404、非申請人 403、不是退件 409「申請單不是退件狀態，無法補件，請重新載入頁面」、版本不符 409。鎖內依序：把舊版內容組成快照寫進 `IM_APP_VER`（`APP_VER_NO`＝舊版次；`CLOSE_STATUS_CODE` 依舊版事件推算——有執行退回 EXEC_REJECTED、有治理退回 GOV_RETURNED、否則 REJECTED；`VER_REASON` 放退回事件的 MEMO 或退件那一關的意見；附件只列舊版簽核結束前上傳的 APP 附件）→ 以新表單覆寫主檔、`CURR_VER_NO + 1`、`RESUB_MEMO`、`FLOW_ID` → 子表整批重建 → 重讀主檔跑送審必填檢核（缺漏 400 整筆 rollback）→ 用與送審共用的建實例流程開新版 `IM_APPR`（候選人同樣排除申請人、某關 0 人 400）→ 事件 RESUBMIT（補件說明放 `MEMO`）。舊版 `IM_APPR` 在退件時已結為 REJECTED，補件不再動它。成功回 200 `{appId, rowVerNo}`
  - **刪除** `delete`（S9；`AppDeleteService`）：本文 `{rowVerNo, confirmId, reason}`。鎖外依序：單號格式不符 404、缺版本號 400「缺少版本號，請重新載入頁面」、`confirmId`（去頭尾空白）不等於單號 400「確認編號不符，已取消刪除」、原因空白 400「請填寫刪除原因」、原因超過 500 字 400（`field: reason`）。取鎖只比版本（`lockForUpdate`，`ROW_VER_NO + 1`、`STATUS = 1`），0 列時查無或已刪 404、其餘 409「申請單已在其他地方修改過，請重新載入頁面」；鎖內用與檢視 API 相同的 `AppPermissionService` 重算 `deleteMode`——admin 任何狀態 ADMIN；申請人只在「目前版次沒有任何關卡簽過、未補件過（`CURR_VER_NO` 為 1）、狀態不是 APPROVED／IN_EXECUTION／PENDING_REVIEW／EXECUTED／REJECTED」時 APPLICANT_PRE_REVIEW（即草稿，或送審後還沒人簽）；都不符 403 並 rollback。通過後主檔 `STATUS=0` 並寫 `DELETE_DATE`／`DELETE_USER_ID`／`DELETE_REASON`／`DELETE_MODE_CODE` → 進行中的簽核實例與未結關卡改 CANCELLED → 事件 DELETE（原因放 `MEMO`）。附件實體檔保留、單號不回收。成功回 200 `{appId}`。刪除後所有讀寫 API 都以 `STATUS = 1` 過濾，對該單一律 404 或不列出
  - 送審、補件、同意、退件、撤回在同一個 transaction 內呼叫 `MailNotifier` 寫 `IM_MAIL_OUTBOX`（撤回在關閉關卡之前先查收件人；時點、收件人與失敗處理見下方「信件」）；簽核附檔、代簽、事後補核（POST_HOC）關卡、管理員改派見 BACKLOG.md 第 101、104 項；補件與刪除的已知待補細節（刪除先取鎖後判權限、補件快照附件的時間邊界等）見 BACKLOG.md 第 105 項
  - 程式一律用 `ROLE_ID` 判斷角色，不得用 `STEP_CODE`（BACKLOG.md 第 54 項 key 錯位）
- **執行與治理審查**（S10 完成；`service.changerequest.AppExecutionService`／`ExecutionValidator`、`dao.changerequest.ExecWriteDao`、`controller.changerequest.AppExecutionController`）：三個動作各是一個 transaction，本文都必須帶 `rowVerNo`（單號格式不符 404、缺版本號 400「缺少版本號，請重新載入頁面」）；成功時 `ROW_VER_NO + 1`。送治理審查、執行端退回、治理退回與通過會在同一個 transaction 內寫 `IM_MAIL_OUTBOX`（見下方「信件」；暫存與所有失敗路徑不寄）；三個動作都不收附件（見 BACKLOG.md 第 108 項）
  - **誰能執行**：機房管理員（`idc_admin`）或該單申請人（`AppExecutionService.mayExecute`，與檢視 API 的 `canExecute` 用相同的身分條件）；單子須為 `APPROVED`（待執行）或 `IN_EXECUTION`（執行中）。寫入前先不鎖查一次（`findLockState`；查無 404、無權 403），通過後才取鎖（`lockForUpdate`，只比版本；0 列時查無 404、其餘 409「申請單已在其他地方修改過，請重新載入頁面」），鎖內重讀主檔再判一次狀態（不符 409）與權限（不符 403）
  - **填寫執行紀錄** `PUT /api/apps/{id}/execution`：本文 `{rowVerNo, checklist:[{seqNo, done, doneAt, userId, executorDesc}], actualStart, actualEnd, resultCode, exception, exceptionDesc, followUp, followUpDesc, memo}`。`resultCode` 有值即「送治理審查」（→ `PENDING_REVIEW`），空白即「暫存」（→ `IN_EXECUTION`，已是執行中則狀態不變）。格式檢核（`ExecutionValidator`，鎖外、暫存與送審都做，400）：日期時間 `yyyy-MM-dd HH:mm`（也接受中間是 `T`）、實際結束不得早於開始「實際結束時間不得早於實際開始時間」、檢核項序號不得空白或重複「檢核項序號不正確」、執行人 `userId` 與文字描述 `executorDesc` 至多擇一「檢核項執行人請擇一填寫系統使用者或文字描述」、說明與備註上限 2000 字、執行人文字上限 200 字；未勾異常／後續追蹤時對應說明存 null。送審另檢核（一次列出全部缺漏）：結果必須是啟用中的 `EXEC_RESULT` 選項（否則「執行結果不正確」）、「送治理審查前請先補齊：實際開始時間、實際結束時間、異常說明、後續追蹤說明」（後兩項只在勾選時要求）；檢核項不必全勾。鎖內依序：該版次還沒有檢核表時，依啟用中的 `CHECK_LIST` 選項（`SORT_NO` 順序）展開 `IM_APP_CHECK_LIST`（第一次存檔才展開）→ 逐項比對本文：序號不在該版檢核表 400「檢核項序號不正確」；`userId` 只接受**登入者本人或該列已存的原值**，其餘一律 400「檢核項執行人只能填自己，或保留該項原本登記的人」（全部檢查完才開始寫）；本文沒列到的項目保留原值；`done=false` 清掉完成時間，`done=true` 沒給時間用伺服器當下時間 → `IM_APP_EXEC` 依（`APP_ID`、`APP_VER_NO`）有則 UPDATE、無則 INSERT，結案人 `USER_ID` 與 `CLOSE_DATE` 只在送審時寫 → 狀態有變才更新主檔狀態。存檔不寫 `IM_APP_EVENT`。成功回 200 `{appId, rowVerNo, statusCode}`。非 idc_admin 也非申請人時 403、狀態不符 409「申請單不在待執行或執行中，無法填寫執行紀錄，請重新載入頁面」
  - **執行端退回** `POST /api/apps/{id}/execution/reject`：本文 `{rowVerNo, memo}`，意見必填「退回請填寫意見」、上限 2000 字；權限、狀態與取鎖同上（狀態不符 409「申請單不在待執行或執行中，無法退回，請重新載入頁面」）；主檔 → `REJECTED`、寫事件 `EXEC_REJECT`（意見放 `MEMO`），不清已填的執行資料。成功回 200 `{appId, rowVerNo}`。申請人之後走補件（`resubmit`），舊版快照的 `CLOSE_STATUS_CODE` 記 EXEC_REJECTED
  - **治理審查** `POST /api/apps/{id}/governance-review`：只限 `governance` 角色（鎖外先判，否則 403）；本文 `{rowVerNo, decision: PASS|RETURN, memo}`，`decision` 其他值 400「審核決定只能是通過或退回」；退回必填意見「退回請填寫意見」，通過意見選填（空白存 null），上限 2000 字。取鎖用 `transition(PENDING_REVIEW → EXECUTED／REJECTED)`，0 列時查無 404、不是待治理審核 409「申請單不是待治理審核狀態，無法審核，請重新載入頁面」、版本不符 409。通過 → `EXECUTED`、事件 `GOV_PASS`；退回 → `REJECTED`、事件 `GOV_RETURN`（補件快照記 GOV_RETURNED）。審查人是申請人或該版執行人時不阻擋。審查結果不另存欄位，檢視頁依最後一筆 `GOV_PASS`／`GOV_RETURN` 事件顯示。成功回 200 `{appId, rowVerNo, statusCode}`
  - 服務層丟的 403 自訂訊息目前會被換成「無權限執行此操作」且不寫 log，見 BACKLOG.md 第 100 項 N1；已知待補細節見 BACKLOG.md 第 107 項
- **認證**（S2 三回合皆已上線並通過階段 code review，2026-10-06；細節調整見 BACKLOG.md 第 90、92 項）：本系統有自己的登入頁，不接公司統一入口或 SSO（使用者 2026-10-06 確認）；登入方式為 DB 帳密（裁示 ①A），驗證在 `service.auth.AuthService`：帳號去空白轉小寫後查 `IM_USER.LOGIN_ID`，`STATUS=1` 且 `PWD_HASH` 非空才比對密碼，查無帳號或停用時也做一次假比對（避免靠回應時間猜帳號）。後端（3202）用 Spring Security server-side session（`config.SecurityConfig`；Tomcat 記憶體 session，不用 formLogin／httpBasic；多機部署時才接 Spring Session JDBC，見 BACKLOG.md 第 86 項）：session cookie `IM_SESSION`（HttpOnly、Secure 明設、SameSite=Lax、Path=/、不設 Domain；正式環境 3201 為 https，使用者已確認）、閒置 8 小時逾時、登入成功時由 `AuthService` 自己換 session id（手動登入不經 Spring 的登入後策略，所以不設 `sessionFixation`）；CSRF 採 cookie `IM_XSRF`（非 HttpOnly、Secure、SameSite=Lax、Path=/）＋ header `X-IM-XSRF`，所有 POST／PUT／PATCH／DELETE（含 login、logout）都要帶，前端先呼叫 `GET /api/auth/me` 取得 cookie、axios 自動帶 header；登入成功時換發新的 `IM_XSRF`、登出時清掉（前端每次請求都從 cookie 讀當下的值即可）；cookie 屬性全部寫在程式常數，不靠 `request.isSecure()` 自動判斷，真 Tomcat 送出的 `Set-Cookie` 屬性有測試鎖住（`SessionCookieTomcatTest`）。另宣告一個一律拒絕的 `AuthenticationManager` bean，只為了讓 Spring Boot 不建預設帳號 `user`（否則會把隨機密碼印進 log）。不用 JWT、沒有 remember-me。`GET /api/auth/me` 永遠回 200（未登入時 `loggedIn:false`），前端呼叫失敗時再查一次此端點判斷是否登入過期、是則導回登入頁。`/api/health`、`/api/auth/login`／`logout`／`me` 免登入；其他 `/api/auth/**` 須登入；其他 `/api/**` 未登入回 401 `{"message":"尚未登入"}`、絕不導頁（302），CSRF 不合回 403 `{"message":"安全驗證失敗，請重新整理頁面後再試"}`，`/api` 以外的路徑一律拒絕。cookie 與 header 由殼 jar 自建的轉發器透傳（見「前端架構」）；後端一律自行驗 session，不信任殼 jar 轉來的身分（正式環境 3202 是否只開給殼 jar 待確認，見 BACKLOG.md 第 80 項）。認證 log（`security.md` A09）：登入成功、登入失敗（原因代碼 `no_user`／`inactive_or_no_hash`／`bad_password`）、登出、403（類別 `csrf`／`default_password`／`forbidden`）各記一行，帶工號（帳號存在時）與來源 IP `srcIp`，不記輸入的帳號、不記密碼；`srcIp` 取殼 jar 附的 `X-Forwarded-For` 第一段（`web.ClientIp`，只留英數與 `.:%-_`、最長 64），在 BACKLOG.md 第 80 項定案前視為「來源未驗證」，只供追查、不得拿來做授權或限流。沒有登入次數限制，見 BACKLOG.md 第 88 項；停用帳號或移除角色不會讓已登入的 session 立即失效，見 BACKLOG.md 第 89 項
- **授權**：`IM_USER_ROLE_MAP`（只取啟用的對應與啟用的角色）轉成 `ROLE_<角色代碼>` authority 管 URL 層；另有 `PWD_OK` authority，只在非預設密碼（`IS_DFLT_PWD=0`）時給，`/api/**`（`/api/auth/**` 與 `/api/health` 除外）一律要求 `PWD_OK`，所以用預設密碼登入的人只能改密碼與登出，其他 API 回 403 `{"message":"請先修改預設密碼"}`；與資料相關的判斷用 `@PreAuthorize("@crAuthz.canDecide(#id)")`（`@EnableMethodSecurity` 已開；方法層丟出的 401／403 由 `web.ApiExceptionHandler` 轉成與 filter 層相同格式的 JSON）；9 個 `canX` 旗標由後端算進 DTO
- **密碼**（2026-10-06 裁示 ①A 修訂、⑦A）：**不相容舊系統 scrypt 格式**。匯入舊帳號時不沿用舊雜湊，全員 `PWD_HASH` 重設為「帳號小寫」的 bcrypt 雜湊並設 `IS_DFLT_PWD=1`（已改過密碼的人也一律重設），首次登入強制改密碼。密碼以 Spring Security `PasswordEncoderFactories.createDelegatingPasswordEncoder()` 儲存（`{bcrypt}` 前綴，日後換演算法可平滑升級），不另引入 BouncyCastle、不自寫 encoder。**改密碼** `POST /api/auth/password`（S2 回合二，2026-10-06 上線）：須已登入（含預設密碼者）並帶 CSRF header，本文 `{oldPassword, newPassword}`。新密碼規則依序檢查、一次只回一個問題，全部是 400 帶訊息：不得全為空白「新密碼不得全為空白」→ 至少 6 個字（以 Unicode code point 計，emoji 算 1）「新密碼至少 6 個字」→ UTF-8 不超過 72 bytes「新密碼過長（上限英數 72 字，中文約 24 字）」（bcrypt `encode` 超過會丟例外，`matches` 不會，所以登入不受影響；新密碼超過 128 字也是這句，不是「請求格式錯誤」）→ 不得等於帳號、不分大小寫「新密碼不得與預設密碼（帳號）相同」→ 不得等於舊密碼「新密碼不得與舊密碼相同」→ 帳號仍存在、有雜湊、啟用中且工號與 session 相同，否則「帳號狀態已變更，請重新登入」→ 舊密碼 bcrypt 比對「舊密碼錯誤」（刻意回 400 不回 401，免得前端誤判成登入過期）。**規則只套在新密碼，預設密碼（帳號小寫）不受 6 字限制**。寫入時 `UPDATE IM_USER SET PWD_HASH、IS_DFLT_PWD=0、PWD_CHANGE_DATE=SYSDATE WHERE USER_ID 且 STATUS=1 且 PWD_HASH=剛比對過的舊雜湊`（樂觀鎖），期間被停用或密碼已被另一個請求改掉會是 0 列，一樣回「帳號狀態已變更，請重新登入」，兩個同時送出的改密碼不會互相覆蓋；角色在寫入前查好，DB 寫入後只剩純記憶體動作。成功後重建 session（換 session id、重查角色、補 `PWD_OK`、換發 `IM_XSRF`），回 200、本文同 `/me` 且 `mustChangePassword:false`，不用重新登入；只重建本次請求的 session，同帳號在其他瀏覽器的 session 不受影響（見 BACKLOG.md 第 89 項）。沒有舊密碼錯誤次數限制，見 BACKLOG.md 第 88 項。log 記「改密碼成功」與「改密碼失敗 reason=」（代碼 `blank`／`too_short`／`too_long`／`equals_default`／`same_as_old`／`account_unavailable`／`bad_old_password`），帶工號與 `srcIp`，不記密碼。**帳號匯入器**（S2 回合二，2026-10-06 上線；`service.imports`／`dao.imports`／`model.imports`）：同一個後端 jar 以 `--spring.main.web-application-type=none --im.import.users=<users.json> --im.import.mapping=<csv>` 啟動即為匯入模式（`UserImportRunner`，只在非 web 且有 `im.import.users` 時成為 bean；不起 Tomcat、跑完結束碼 0／1；`SecurityConfig` 的三個 servlet 專屬 bean 與 `RequestLimitConfig` 加了 `@ConditionalOnWebApplication(type=SERVLET)` 才能非 web 啟動）。讀舊系統 `users.json`（只取 `id`、`name`、`email`、`title`、`department`、`phone`、`roles`、`active`）與對照表 CSV（UTF-8、表頭 `login_id,user_id`，容許 BOM／CRLF／空行）；對照表沒有的帳號略過並列在摘要；檢核全部先做完再寫入，任一問題（id 空白、帳號／工號重複、超過欄位長度、工號為保留帳號 `HIST_PAPER`、角色不在 `IM_ROLE`、帳號已屬於另一個工號）就整批失敗、一筆不寫，問題全列。寫入在一個交易（`@Transactional`，`LazyAliasTransactionManager`）：工號已存在則 UPDATE、否則 INSERT，`LOGIN_ID` 轉小寫保留中間空白，`active:false` 存 `STATUS=0`，`CREATE_BY`／`UPDATE_BY='SYSTEM'`；**每次匯入檔內全員 `PWD_HASH` 一律重設為帳號小寫的 bcrypt、`IS_DFLT_PWD=1`、`PWD_CHANGE_DATE=SYSDATE`**；角色：不在 `IM_USER_ROLE_MAP` 的新增、停用的重新啟用、不再出現的設 `STATUS=0`。DB 已有但檔內沒列的帳號不動。操作步驟見 `SETUP.md`「匯入使用者」。已知待補細節（工號格式檢核、非 UTF-8 提示、web 模式誤帶參數的防呆、寫入 0 筆的結束碼、停機匯入流程等）見 BACKLOG.md 第 90 項
- **AI 審查**（是否保留待定，見 BACKLOG.md 第 17 項；金鑰存放待定，見 BACKLOG.md 第 18 項）：Anthropic Java SDK，使用 OutputConfig（structured output）／tool use／thinking；Bedrock mantle 端點拒絕 `output_config.format`，走 bedrock 通道一律落到 tool 模式；aws 通道需自訂 baseUrl + `anthropic-workspace-id` header（未驗證，S11 先做概念驗證）；報告是否過期以「key 排序 JSON + SHA-256」的申請單快照 hash 判斷；retry 用 SDK 內建 + 退避 1s／3s；遇 refusal 用 fallbackModel 重送並記 `fell_back_from`；追問的輸入為快照、報告、最近 20 則對話、新問題，max_tokens 16000；審查為非同步（回 202，前端輪詢）；提示詞與 REVIEW_SCHEMA 放 `resources`
- **存取紀錄**：Servlet Filter 寫 `IM_ACCESS_LOG`（有上限佇列 + 批次寫入，定期清除；保留天數待定，見 BACKLOG.md 第 70 項）；loopback 改記 LAN IP；`SYS_PARAM` 的 `EXCLUDE_IP` 所列 IP 不記錄
- **信件**（S8a 完成；`service.mail.MailNotifier`／`MailOutboxService`／`MailWorker`／`MailDispatcher`、`dao.mail.MailOutboxDao`／`MailRecipientDao`、`config.MailProperties`／`MailSchedulingConfig`；後台信件頁、重寄與測試信見 BACKLOG.md 第 8 項）：Spring `JavaMailSender`（`spring-boot-starter-mail`）＋ Thymeleaf 樣板＋ outbox 表 `IM_MAIL_OUTBOX`，業務交易只寫表、不等 SMTP
  - **寫入**：`MailNotifier` 每個事件一個方法，在呼叫端的業務 transaction 內執行；它與 `MailOutboxService.enqueue` 刻意不掛 `@Transactional`（例外穿過交易代理會把外層標成 rollback-only）。收件人查詢、重讀申請單、樣板渲染失敗只記 warn（事件、單號、例外類別）並略過不寄，不影響業務；INSERT 失敗往外拋、與業務一起 rollback。`META_JSON` 含 `event`、`appId`；`CREATE_BY` 為操作人工號
  - **事件與樣板**（`resources/templates/mail/`，事件代碼＝樣板檔名）：`step-pending`「[待簽核] {單號} {標題} - {關卡名}」——送審、補件重送（信內加註補件後重新送審）、同意後進下一關，寄當下 PENDING 關卡的候選人；`approved`「[核准完成] {單號} {標題}」——末關同意，只寄申請人，附簽核紀錄；`rejected`「[退件] {單號} {標題} - {階段}」——簽核退件（階段＝關卡名）、執行端退回（「執行階段」）、治理退回（「資訊治理審核」），寄退件群組，含退件人與意見；`recalled`「[撤回通知] {單號} {標題} - 申請人撤回修改」——撤回，寄撤回當下 PENDING 關卡的候選人；`gov-review-request`「[待審核執行結果] {單號} {標題}」——送治理審查，寄所有啟用中 governance，帶執行結果名稱（查不到名稱用代碼）；`gov-passed`「[執行結果已通過] {單號} {標題}」——治理通過，只寄申請人。不寄信：只通知關卡（`IS_NOTIFY_ONLY`；送審時已標 SKIPPED、沒有候選人，舊系統的旗標同樣沒有寄信效果，所以不做知會樣板）、刪除申請單、執行暫存、所有檢核失敗路徑；不 CC `ADMIN_EMAIL`
  - **收件人**（`MailRecipientDao`，join 型查詢）：一律只取 `IM_USER.STATUS=1` 的人；申請人 email 以 `IM_USER.EMAIL` 為準、空白才用填單值 `IM_APP.APPLY_EMAIL`（2026-10-08 裁示 ②A）；候選人取 `IM_APPR_CAND_MAP`；退件群組＝申請人＋該簽核實例所有關卡候選人（含還沒輪到的）＋實際簽核人＋該單任一版次的執行人（`IM_APP_EXEC.USER_ID`）＋啟用中 governance（帳號、角色、對應皆啟用），全部放 To（與舊系統相同）；執行端與治理退回以單號＋版次找該版簽核實例（排除 RECALLED／CANCELLED），找不到時群組不含候選人與簽核人。`enqueue` 去空白、不分大小寫去重、格式不對的略過；一個收件人都沒有時不渲染、不寫入，只記 info
  - **樣板**：六份＋共用片段 `fragments/detail.html`（明細表、連結、頁尾）；只用 `th:text`、不用 `th:utext`，所有欄位（含標題）一律轉義。主旨 CR／LF／Tab 換空白（防標頭注入）、空白時用「(無主旨)」、截 500 字。信內連結＝`im.mail.site-url` + `/#/apps/{單號}`，`site-url` 空白時不放連結；不用請求的 Host header 組網址（舊系統用 Host header 組，偽造 Host 可讓連結指向外部網址）。頁尾「本信件由「{`im.mail.site-name`}」自動發送，請勿直接回覆。發送時間：…」
  - **寄送**：只有 `im.mail.enabled=true` 時 `MailSchedulingConfig` 才開 `@EnableScheduling`（預設關，信只寫進 outbox）。`MailWorker` 以 `im.mail.poll-delay-ms` 固定間隔輪詢（首次延遲 `im.mail.initial-delay-ms`），每輪最多 `im.mail.batch-size` 封：先不鎖查候選 ID（PENDING、`TRY_CNT` 未達 `im.mail.max-try`、`TRY_CNT=0` 或已過退避「上次更新時間 + 已試次數 × `im.mail.retry-backoff-minutes` 分鐘」），再每封各開一個 transaction `SELECT … FOR UPDATE SKIP LOCKED`（Oracle 不允許 FOR UPDATE 與限筆數同句，ORA-02014），鎖不到就跳過；寄完在同一 transaction 標 SENT（記 `SMTP_MSG_ID`、`SEND_DATE`）或 `TRY_CNT+1` 並存 `ERROR_TEXT`，達上限標 FAILED。語意是「至少寄一次」：寄出後、commit 前程式中斷會重寄（schema 沒有寄送中狀態，不改 DDL 的取捨）。`mail.smtp.sendpartial=true`：部分收件人無效仍寄給其他人、記 SENT，`ERROR_TEXT` 備註無效與未送出人數
  - **失敗分流**：SMTP 未設定（`spring.mail.host` 或 `im.mail.from` 空白）→ 只記一次警告、不寄，信累積在 outbox。SMTP 連線階段失敗（連不上主機：`MailConnectException`／`ConnectException`／`UnknownHostException`／`NoRouteToHostException`，沿 cause 鏈與 `MailSendException` 各封失敗原因找）→ 不計重試、不寫 `ERROR_TEXT`、transaction rollback、結束本輪，信停 PENDING，恢復後自動寄出（2026-10-08 裁示 ①A）。讀寫逾時與 SMTP 拒收照一般失敗計重試（逾時可能已寄達，不當成連不上）。DB 無法存取 → 本輪停止。DB 與 SMTP 的異常只在第一次發生與恢復時各記一次 log
  - **改寄**（驗收用；測試 DB 有同事的真實 email）：`im.mail.override-to` 有值時，寫入時收件人一律換成改寄地址、清掉 cc／bcc、主旨加「[測試改寄] 」、原收件人記進 `META_JSON`（`originalTo`／`originalCc`／`originalBcc`）；寄送當下再套一次（寫入時沒改寄的舊信也只寄到改寄地址）。正式環境留空
  - **SMTP 連線**：沿用舊系統的主機、port 25、不加密、無帳號認證；憑證驗證維持關閉（`mail.smtp.ssl.trust=*`，2026-10-07 裁示沿用舊系統；port 25 明文連線用不到，日後改 STARTTLS 才生效）；連線／讀取／寫入逾時各 15 秒
  - **log 與個資**：email 地址視同個資，log 只記 outbox ID、單號、事件、收件人數、例外類別，不記地址；`ERROR_TEXT` 存例外類別＋訊息（含 SMTP 回應），email 形狀的字串遮成 `***`、截 1000 字；worker 更新列時 `UPDATE_BY='SYSTEM'`
  - 已知待補細節（鎖內未重查退避與上限、地址嚴格驗證、`poll()` 開頭的開關防護、測試缺口等）見 BACKLOG.md 第 111 項；改走公司 email API 見 BACKLOG.md 第 112 項
- **附件**：檔案本體存檔案系統，DB（`IM_ATTACH.FILE_PATH`）只存相對於附件根目錄的路徑；下載一律經 controller 檢查權限，不提供公開 static 路徑；檔名 UTF-8；上傳限制（`SYS_PARAM` 的 `UPLOAD_MAX_FILES`、`UPLOAD_MAX_MB`）每次請求讀取。附件根目錄由後端設定鍵 `im.attach.root` 指定（絕對路徑；未設定時下載端點回 500）。後端下載端點 `GET /api/apps/{id}/attachments/{attachId}`（S4 完成）：須登入、附件必須屬於該申請單（`IM_ATTACH.OWNER_ID` 為該單號，或為該單簽核關卡／事件的附件），`FILE_PATH` 解析後必須仍在根目錄之下（逸出一律 404「找不到附件」，不洩漏檔案是否存在）、索引有但檔案不在回 404「附件檔案不存在」；回應帶原始檔名（RFC 5987 UTF-8）、`Content-Type`、`Content-Length`（取實體檔大小，與 `IM_ATTACH.FILE_BYTE_QTY` 不同時寫 warn）、`Content-Disposition: attachment` 與 `X-Content-Type-Options: nosniff`；`Content-Type` 不照 DB 的 `MIME_TYPE`，而是由 `FILE_PATH` 的副檔名查白名單（`AttachmentTypes`），不在白名單回 `application/octet-stream`。支援 HEAD（前端下載前先確認）。**副檔名白名單**（17 種，上傳與下載共用）：jpg／jpeg／png／gif／bmp／webp、pdf、doc／docx、xls／xlsx、ppt／pptx、txt、csv、zip、msg；取最後一個點之後、不分大小寫，隱藏檔、點在結尾、點只在資料夾名都算沒有副檔名。後端上傳端點 `POST /api/apps/{id}/attachments`（S6 完成）：multipart、一次一檔、part 名 `file`，須登入且為申請人、單子須為 `DRAFT` 或 `REJECTED`（退件後補件前可先補附件，S9 裁示 ⑤A），檢查依序為單號格式（404）→ 檔名清理與白名單（400「不支援的檔案類型」）→ 大小上限（400「檔案超過 N MB 上限」，N 為 `UPLOAD_MAX_MB`）與空檔（400「檔案是空的，請確認後再上傳」）→ 不鎖預檢申請單（404／403／409「申請單不是草稿或退件狀態，無法上傳附件，請重新載入頁面」）與檔數（400「附件已達 N 個上限」，只算 `OWNER_TYPE=APP` 的有效附件）；通過後在交易外寫暫存檔 `<root>/.tmp/<uuid>.part`、邊寫邊算 SHA-256 並再檢查一次實際長度，然後在交易內以 `FOR UPDATE` 鎖住主檔重新檢查、寫入 `IM_ATTACH`、把檔案搬到 `<root>/<APP_ID>/<uuid>.<ext>`；暫存檔一律刪除，交易沒 commit 時目標檔也刪除。原始檔名去掉路徑、控制字元、格式字元（含 U+202E）與孤立代理字元，截為 255 字；`MIME_TYPE` 由伺服器依副檔名決定，不信瀏覽器送的 `Content-Type`。成功回 201，本文與檢視 API 的附件元素相同。CSRF token 只從 `X-IM-XSRF` header 讀（`config.HeaderOnlyCsrfTokenRequestHandler`），不退回讀 `_csrf` 表單參數，所以沒帶 header 的 multipart 不會在 CSRF 檢查時被提早解析、落暫存檔。草稿附件不提供刪除（同舊系統）
- **草稿新增與編輯**（S6 完成；`service.changerequest.AppDraftService`／`AppDraftValidator`、`dao.changerequest.AppWriteDao`／`AppSeqDao`）：
  - **檢核**（新增與編輯共用，失敗一律 400 帶訊息）：標題必填「請填寫標題」、優先等級必填「請選擇優先等級」（P1～P4；`PRIO_CODE` 為 NOT NULL 且決定流程）；其餘欄位存草稿時都可空，送審時才檢核（見「簽核引擎」的送審）。另檢查作業方式、廠商進場人數、預定結束不得早於開始、預估工時（上限 9999.99）、日期時間格式 `yyyy-MM-dd'T'HH:mm`；類別子項、原因、範圍的選項 ID 必須是啟用中的選項，否則「選項不正確，請重新載入頁面」；設備與作業步驟各最多 100 筆，全空的設備列略過、有填其他欄卻沒填名稱回「第 N 筆設備請填寫設備名稱」。文字長度依 DDL：CHAR 語意欄以 code point 計，`APPLY_EMAIL`／`SITE_ID`／`RACK_ID` 以 UTF-8 bytes 計，CLOB 欄依 `TextLength`（2000／20000 字），超過回 `{message, field, max, actual}`
  - **新增** `POST /api/apps`：申請人一律是登入者、`APPLY_DATE` 由伺服器設；流程依 `FLOW_POLICY`：`full_only` 一律 `full`，`by_priority` 取該優先等級選項的 `FLOW_ID`（沒設定退回 `full`）。單號在同一交易取號：`IM_APP_SEQ` 當日列 `LAST_NO+1`，沒有當日列就 INSERT 1，兩人同時插入撞鍵時重做 UPDATE；號碼只增不減，刪單後再建不會撞號，當日超過 999 張自然變成 4 位數。主檔與子表同一交易寫入，成功回 201 `{appId, rowVerNo: 0}`
  - **編輯** `PUT /api/apps/{id}`：只有申請人能編輯（admin 也不行）。本文同新增並多帶 `rowVerNo`，缺少回 400「缺少版本號，請重新載入頁面」。更新條件含版本號、`DRAFT`、申請人與 `STATUS=1`，成功時 `ROW_VER_NO+1`；0 列時依現況分辨：查無回 404「找不到申請單」、非申請人 403、不是草稿 409「申請單已不是草稿，無法編輯，請重新載入頁面」、版本不符 409「申請單已在其他地方修改過，請重新載入頁面後再編輯」。子表在同一交易刪除後重建；`FLOW_ID` 依新的優先等級重算。成功回 200 `{appId, rowVerNo}`
  - 已知待補細節（服務層 403 沒寫 log、日期寬鬆解析、選項陣列長度上限等）見 BACKLOG.md 第 100 項
- **範本**（S5；`service.template.TemplateService`、`dao.template.TemplateDao`、`controller.template.TemplateController`）：
  - **內容格式**：`IM_TMPL.FORM_JSON` 存 `model.template.TemplateForm`，欄位名同 `AppDraftRequest`、去掉 `applicant` 與 `rowVerNo`，`schedule` 只有 `estHours`。寫入前轉成草稿請求、以 `AppDraftValidator` 同一套規則檢查並正規化（只多「標題可空」），存的是正規化後的結果；序列化用本類別專屬的 `JsonMapper`（不受全域設定影響），讀取時忽略未知欄位，解析失敗回 `form=null` 並記 log（只記例外類別名）。`FORM_JSON` 以 `Types.CLOB` 綁定；列表以 `JSON_VALUE(FORM_JSON, '$.prioCode')` 取優先等級，不搬整份 CLOB
  - **檢核**：範本名稱必填「請填寫範本名稱」、上限 100 字（超過回 `{message, field, max, actual}`）；缺 `form` 回「缺少範本內容」。ID 由伺服器產生 `tpl_<毫秒>_<4 位 hex>`，路徑上的 ID 先過 `^[A-Za-z0-9_-]{1,60}$`，不符或查無（含已刪除）一律 404「找不到範本，可能已被刪除」
  - **權限**：修改、刪除先查有效範本，再比對 DB 的 `OWNER_USER_ID` 與登入者，或登入者有 `admin` 角色，否則 403「只有範本建立者或系統管理員可以修改或刪除範本」；本文改不到建立者、次數與狀態。不做樂觀鎖（`IM_TMPL` 沒有版本欄，後寫者為準）；名稱不檢查重複
  - **套用次數**：`AppDraftService.create` 在寫完子表後、同一交易呼叫 `TemplateService.recordUse`：`USE_CNT = NVL(USE_CNT,0)+1`、`LAST_USE_DATE`、`LAST_USE_USER_ID`（只限 `STATUS=1`，不動 `UPDATE_DATE`）。ID 格式不符或 0 列只記 info log、不丟例外；DB 錯誤則整張單回滾。編輯草稿（PUT）不累計
  - 已知待補細節（範本載入中可存檔、套用後工時與起訖不一致、內容壞掉仍顯示套用成功、大內容與建單整條路的 IT、上一頁不重新套用）見 BACKLOG.md 第 109 項
- **機櫃盤點快取**：Caffeine + DB 快取列（`IM_RACK_CACHE`，依快取鍵值分列，如 `SITES`、`RACKS_<站點代碼>`）；`@Scheduled` 背景刷新（stale-while-revalidate）；單一執行中旗標避免重複刷新；外部機櫃系統（Impact）斷線時回舊快取
- **我的待辦數**：一條 SQL，靠 `IM_APPR_CAND_MAP` 的 `USER_ID` 索引

#### 前端架構
- 兩包（web_template_3.5）：
  - **殼 jar** `infra_manager_web/infra_manager_web/`：port 3201、context path `/infra_manager_web`；服務前端靜態檔；由本專案自建的轉發器（`com.mpx.infra_manager_web` 下單一 controller，2026-10-06 裁示 ①B：範本 `com.mpx.common.web` 的 `ApiForwarder` 等四個類別與測試已刪除，見「給範本維護者的註記」第 2 點）把 `/api/v1/**` 的 GET／HEAD／POST／PUT／PATCH／DELETE 轉給後端 `/api/**`（OPTIONS 由殼 jar 自己回 `Allow`、不轉；其他 method 405；後端位址由殼 jar `config/host.properties` 的 `backend.api.domain.path` 指定，結尾斜線自動去掉）：原樣轉 method／路徑／查詢字串／本文與 `Content-Type`／`Accept`（瀏覽器沒送 `Content-Type` 就不補；有本文時帶 `Content-Length`、不用 chunked，後端的 1 MB 快速 413 才用得到；表單解析過濾器 `FormContentFilter` 已關閉，PUT／PATCH／DELETE 的表單本文也原樣過；殼 jar 的 multipart 解析由 `NoMultipartConfig` 在程式裡關掉（不靠 properties，真檔漏改也不會壞），multipart 本文原樣串流轉給後端），請求與回應本文**全程串流**（64 KB 緩衝，不整包讀進記憶體；在 HttpClient 的回應回呼內同步寫出），**本文上限**：非 multipart 1 MB、`multipart/` 開頭（不分大小寫）51 MB，程式常數；`Content-Length` 已超過就直接回 413 `{"message":"請求內容過大"}`、不打後端，沒有長度的 chunked 本文邊轉邊計數、超過即中斷並回 413。回應寫到一半後端斷線時直接中斷連線（不再補錯誤本文）；還沒寫出任何 byte 前失敗仍回 502。只帶 `IM_` 開頭的 cookie 與 `X-IM-XSRF` header、附 `X-Forwarded-For`（取連線來源 IP，不信任瀏覽器送來的）；回傳後端原狀態碼（含 3xx／4xx／5xx）、本文、`Content-Type`（後端有本文卻沒給時標 `application/octet-stream`）、`Content-Length`、`Content-Disposition`、`X-Content-Type-Options`、`Cache-Control` 與 `IM_` 開頭的 `Set-Cookie`；其他回應 header（含 3xx 的 `Location`）不轉，所以後端對 `/api/**` 未登入必須回 401、不得導頁。子路徑含 `.`／`..`／`;`／`\`／編碼過的斜線／中間空段（`//`）、不是 `/api/v1` 開頭、查詢字串或 `Content-Type` 格式不合法、或組出的目標不在設定位址的 scheme／host／port／路徑前綴之下時回 400 `{"message":"請求格式錯誤"}`、不打後端（防路徑跳脫與 SSRF）；結尾斜線放行；部分格式（原始 `\`、`%2F`、`%5C`、`%00`、跳出根目錄的 `..`）Tomcat 會先回它自己的 400，本文不是上述 JSON。後端位址未設定或格式不合法（只接受 `http`／`https`；主機名含底線視為不合法）、連不上、逾時回 502 `{"message":"後端服務呼叫失敗"}`；connect 5 秒／read 120 秒、固定 HTTP/1.1、不跟隨導向；read timeout 涵蓋「等回應 header＋讀完整個回應本文」、不含上傳本文的時間，所以經 3201 的下載整體上限 120 秒，上傳不受限（BACKLOG.md 第 97 項）。不解析 payload、不加 CrossOrigin。log 只記 method、路徑、來源 IP、狀態或失敗例外鏈的類別名（由外往內，例 `ConnectException <- ClosedChannelException`）。不連 DB、沒有 SQL。所有回應（靜態檔、轉發結果、錯誤）一律加 `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: same-origin`（`config/SecurityHeadersFilter`，最先執行）；CSP 尚未做，見 BACKLOG.md 第 83 項
  - **前端** `infra_manager_web/infra_manager_web_frontend/`：Vue 3.5 + TypeScript；vue-router **hash 模式**；axios 實例 `src/api/http.ts`（baseURL `/infra_manager_web/api/v1`、timeout 120 秒，與殼 jar read timeout 一致；只有附件上傳單次 600 秒）；API 呼叫集中在 `src/api/`、請求與回應型別放 `src/types/`、畫面放 `src/views/`、測試放 `tests/`；Vite `base` 為 `/infra_manager_web/`，build 輸出到殼 jar 的 `src/frontend`，殼 jar 打包時一併放進 jar
- 頁面只呼叫自己殼 jar 的 `/infra_manager_web/api/v1/...`，不直接打後端 3202。本機開發入口網址 `http://localhost:3201/infra_manager_web/#/`；正式環境為 https（使用者 2026-10-06 確認）
- 不使用 UI 元件庫，沿用範本 `main.css` 的色票（`--teal`、`--line` 等）與基礎字級（`body` 19px）；版面規範依範本 README
- 呼叫失敗時一律提示錯誤（toast），不得把失敗顯示成「查無資料」；toast 由 `components/ToastHost.vue` 統一顯示（各頁掛一個），狀態在 `composables/useToast.ts`
- **登入狀態與路由守衛**（S2 回合三，2026-10-06 上線）：登入狀態是模組層級單例 `composables/useAuth.ts`（`me`、`loggedIn`、`mustChangePassword`、`userName`、`roles`、`hasRole`），首次需要時呼叫 `GET /api/auth/me` 一次並快取（順便拿到 `IM_XSRF` cookie），登入／改密碼後直接以回應更新；登出後清掉快取，守衛導到登入頁時會重打 `/me`，因為後端登出時刪掉了 `IM_XSRF`、下一次登入要帶新的（否則第一次登入會被 CSRF 擋成 403）；`/me` 打不到時視為未登入並 toast「無法連線後端服務，請稍後再試」。路由守衛（`router/index.ts` 的 `beforeEach`）：未登入進非公開頁導 `/login`、原路徑放 `redirect` 查詢參數（目標是首頁時不帶）；已登入進 `/login` 回首頁；`mustChangePassword` 為真時除 `/change-password` 外一律導去改密碼頁。`api/http.ts` 的攔截器每次請求從 cookie 讀 `IM_XSRF` 填進 `X-IM-XSRF` header；回應 401 時先重查 `/me`，確認未登入才 toast「登入已過期，請重新登入」並導 `/login?redirect=目前路徑`（處理函式由 `main.ts` 註冊，`http.ts` 不 import router 以免循環相依）；`/auth/login` 的 401 是帳密錯誤，不走這個流程；同一時間多支 API 回 401 只重查一次、只出一次 toast、只導一次頁；頁面用 `isUnauthorized(e)` 判斷 401，不再自己出 toast；`errorMessage(e, 預設文字)` 取後端 `{message}` 當 toast 內容。`LoginView`：帳號去頭尾空白、兩欄皆填才送 `POST /api/auth/login`，失敗 toast 後端訊息（401 為「帳號或密碼錯誤」）；成功時預設密碼者導改密碼頁並提示「首次登入請先修改預設密碼」，否則回 `redirect`。`redirect` 只接受站內路徑（`safeRedirect`：單一 `/` 開頭、不含 `//`、反斜線、不得指回 `/login`，其餘一律回首頁），防開放式重導（舊系統漏洞第 50 項）。`ChangePasswordView`：三欄必填、兩次新密碼相同、至少 6 個 code point（不去頭尾空白，與後端一致）在前端先擋，其餘規則（含全空白）以後端 400 訊息為準；成功 toast「密碼已修改」回首頁；頁上有登出鈕供不想改的人離開。`HomeView` 右上角顯示登入者姓名、修改密碼連結與登出鈕（登出呼叫 `POST /api/auth/logout` 後回登入頁，失敗也回登入頁並 toast「登出失敗」；此時後端 session 仍有效，是否改為留在原頁待裁示，見 BACKLOG.md 第 93 項）。表單 `input` 都有 `maxlength`（帳號 64、密碼 128）與 `autocomplete` 屬性。`HomeView` 另有「功能」卡片，目前有「申請單列表」與「新增申請單」兩個入口
- **申請單列表頁** `AppListView`（路由 `/apps`，須登入）：進頁打 `GET /api/apps` 第 1 頁；篩選欄為狀態、優先（P1～P4）、來源（線上申請／紙本匯入）、關鍵字（`maxlength` 100，送出前去頭尾空白）、建立日期起迄、「只看待我簽核（N）」勾選（N 為 `mineCount`），下方提示「建立日期兩個都不填時，只顯示近 90 天的申請單」；空值與未勾選不送參數。按「查詢」才套用條件，換頁沿用最後一次查詢的條件；「清除」回預設條件重查；起日晚於迄日直接 toast「起日不得晚於迄日」、不打 API。表格欄位：單號（待我簽核者加「待我簽核」標記、整列淺綠底）、優先（代碼，底色用後端給的 `#rrggbb`，格式不符就用預設灰）、標題＋作業主旨、申請人＋部門、狀態（中文標籤 `StatusPill`；紙本匯入另註明）、目前關卡＋簽核人、建立時間；`table-layout: fixed`、長字自動換行，1024 寬不出現水平捲軸。下方分頁列「第 x / y 頁，共 n 筆」與上一頁／下一頁（`AppPager`）。成功但沒有資料顯示「沒有符合條件的申請單」；失敗時 toast 後端訊息並在表格處顯示錯誤文字，不顯示成沒有資料；401 只顯示「尚未登入」、由登入處理器導頁。單號為連到檢視頁 `/apps/:id` 的連結；標題列有「新增申請單」鈕
- **新增／編輯草稿頁** `AppFormView`（路由 `/apps/new`、`/apps/:id/edit`，共用一頁，須登入）：進頁打 `GET /api/form-options`（編輯時另打 `GET /api/apps/{id}`）；編輯時 `permissions.canEditDraft` 為假就顯示錯誤、不給存檔。區塊順序比照舊系統新增頁：基本資料（申請人固定為登入者、不可改）、事件分級卡片、作業類別（每個大類可勾「其他」並填說明）、異動作業內容（主題、原因、影響範圍、設備位置「不適用＋原因」、設備表格可增刪列、預定開始／結束時間、計畫步驟可增刪列、說明／風險／回復方案）、附件；開始與結束都填時自動算預計耗時，仍可手改。勾「不適用」沒填原因時存「不適用」。編輯舊草稿時已停用的選項自動拿掉（不提示）。前端只檢查標題必填，其餘以後端 400 為準：有 `field` 時標紅該欄並捲過去（`field` 先過安全字元檢查才拿去找元素）；409 時底部列出現「重新載入」鈕，按下重新取回最新內容。附件：選檔後先預檢副檔名白名單、大小（`upload.maxMb`）與檔數（`upload.maxFiles`，含已上傳的），不合格的直接列出原因不加入；按「儲存草稿」時先存表單（新增成功後網址換成編輯頁），再逐檔上傳並顯示進度，全部成功才回檢視頁；失敗的檔留在清單可移除，再按儲存只重傳失敗的。已上傳的附件只列出、不能刪除。底部固定列放錯誤訊息、取消與儲存草稿；1024 寬不出現水平捲軸。**補件模式**（S9，路由 `/apps/:id/resubmit`）：同一頁、標題「補件並重送」、送出鈕「補件並送審」；進頁 `permissions.canResubmit` 為假時依原因顯示「只有申請人可以補件」或「申請單不是退件狀態，無法補件」、不給送出；表單上方顯示退件資訊（目前簽核退件那一關的關卡、簽核人、時間、意見；沒有時取最後一筆執行退回或治理退回事件），另有「補件說明」欄（選填、`maxlength` 2000）。送出先 `confirm`，再上傳新加的附件（任一失敗就停、提示「…補件尚未送出…」），最後送 `POST /api/apps/{id}/resubmit`；成功 toast「已補件並重新送審」並回檢視頁。409 與 403 時底部出現「重新載入」鈕，**不自動重載**（保留使用者已改的內容）
- **檢視頁流程動作**（S7 完成；`api/apps.ts` 的 `submitApp`／`recallApp`／`decideApp`，型別在 `types/app.ts`）：動作鈕依 `permissions` 顯示。「送審」原生 `confirm`「確定要正式送審？送審後申請單將進入第一關簽核流程。」後直接送；「撤回到草稿」展開面板（原因選填、`maxlength` 2000）→ `confirm` → 送；「簽核」展開面板（標題帶目前 PENDING 關卡名、意見欄 `maxlength` 2000、✓ 同意／✗ 退件）——同意直接送，退件先在前端擋空白意見（toast「退件時必須填寫原因，讓申請人知道要補什麼」）再 `confirm`。三個動作都帶目前的 `rowVerNo`，期間鎖住全部動作鈕；成功 toast（「已送審，申請單進入簽核流程」「已撤回到草稿」「已同意」「已退件」）後就地重新載入（不閃「載入中」）；409／403／404 toast 後端訊息並重新載入（404 重載後顯示「找不到申請單」），重載後若展開中面板的權限已變成假就收起面板；400 只 toast，401 交登入處理器。「補件」鈕導到補件頁，「填寫執行紀錄」鈕導到執行頁。**治理審核**（S10，`reviewExecution`）：展開面板「治理審核：執行結果」（意見欄 `maxlength` 2000、✓ 通過（結案）／✗ 退回／取消）——通過直接送（意見可空），退回先在前端擋空白意見再 `confirm`「確定退回給申請人？退回後申請人需補件重送，整張單重新簽核與執行。」；成功 toast「已通過，申請單結案」／「已退回給申請人」，失敗與重新載入處理同上。審查結果顯示在簽核欄的「執行確認」列（取最後一筆 `GOV_PASS`／`GOV_RETURN` 事件）。**刪除**（S9）：「刪除申請單」展開面板，須輸入單號（去頭尾空白後與單號完全相同）與刪除原因（必填、`maxlength` 500）才能按確認，送 `DELETE /api/apps/{id}`（axios `data` 帶 JSON 本文），成功 toast「已刪除申請單 單號」並回列表；失敗處理同上。**歷史版次與歷次簽核**：依 `versions` 分組，每個舊版次列出版號、結束方式與該版的每一輪簽核（狀態中文：簽核中、核准、退件、已撤回、已取消，各附關卡表）；不屬於任何舊版次的輪次（例如目前版次被撤回的那一輪）列在「目前版次」群組。已知待補（同意沒有確認框）見 BACKLOG.md 第 104 項；補件頁上傳遇 409 沒有重新載入鈕、歷次簽核分組邊界見第 105 項
- **執行頁** `ExecuteView`（S10，路由 `/apps/:id/execute`，須登入；`api/apps.ts` 的 `saveExecution`／`rejectExecution`）：標題「單號 填寫執行紀錄」；進頁打 `GET /api/apps/{id}` 與 `GET /api/form-options`，`permissions.canExecute` 為假時顯示「這張申請單目前無法填寫執行紀錄（狀態已變更，或你不是機房管理員／申請人）。」與回檢視頁連結、不給操作。「五、作業執行檢核表」：該版次已有檢核表就照它列，還沒有時依啟用中的 `CHECK_LIST` 選項畫列，第 5～8 項名稱後接「：作業步驟一～四」；勾「完成」時填入瀏覽器當下時間；執行人可按「填入我自己」（顯示登入者姓名）或「改填文字」（`maxlength` 200），不提供挑選其他系統使用者。「六、實際執行紀錄」：實際開始／完成時間、執行結果（`EXEC_RESULT` 選項，完成時必選，畫面註明「暫存不會儲存結果」）、例外／衍生事件與後續追蹤各自的勾選與說明（`maxlength` 2000；已有說明時取消勾選會先 `confirm`「取消勾選後，已填的說明不會儲存，確定要取消？」）、執行備註（`maxlength` 2000）。底部三個鈕：「暫存」一律不帶結果，成功 toast「已暫存」並就地重新載入；「✓ 完成並送治理審查」沒選結果 toast「完成並送治理審查前請選擇執行結果」，有選則 `confirm`「確定執行完成並送治理審查？送出後無法再修改執行紀錄，要等資訊治理人員審核。」，成功 toast「已送治理審查」回檢視頁；「退回給申請人」意見必填（空白 toast「退回請填寫意見，讓申請人知道要補什麼」）並 `confirm`「確定退回給申請人？退回後申請人需補件重送，整張單重新簽核。」，成功 toast「已退回給申請人」回檢視頁。重存時把已存的完成時間與執行人原樣帶回。送出期間鎖住全部按鈕；失敗 toast 後端訊息，409／403／404（`api/http.ts` 的 `isStale`，檢視頁共用）重新載入並以最新內容取代表單，401 交登入處理器；慢回來的舊載入結果丟棄。已知待補（重新載入會靜默蓋掉未存的輸入等）見 BACKLOG.md 第 107 項
- 互動元件：
  - DateTimePicker：固定台灣時區
  - 附件上傳：拖放、貼上截圖（自寫 paste handler）、上傳前預檢
  - **機櫃 U 位視覺化選擇器**：自寫元件，是前端最大工作項
  - 設備清單：可編輯表格（動態增刪列）
  - 簽核流程關卡排序：拖曳排序（SortableJS 或 vuedraggable，引入前審查）
  - 統計圖表：做法待 S14 前確認舊系統是否純 CSS 圖，見 BACKLOG.md 第 38 項
- 正式環境的前端部署：前端 build 進殼 jar 的 static，與殼 jar 一起以單一 jar 部署（前端範本的既定架構），不另設 nginx 反向代理容器

#### 給範本維護者的註記
本系統在兩個範本之上有以下超出範本範圍的做法或對範本的需求，供範本維護者評估：
1. **自建 TransactionManager**：後端範本 README 寫明 `DbClient` 不支援交易（沒有 `@Transactional`／TransactionManager）。本系統在業務 package 自建 `LazyAliasTransactionManager`（見「後端分層」的「交易管理」），疊在範本連線池上，`com.mpx.common` 未修改
2. **殼 jar 不用範本的 `ApiForwarder`、並已刪除 `com.mpx.common.web`**（2026-10-06 使用者裁示 ①B，違反前端範本 README 鐵律第 1 條與檢查表「沒有修改 `com.mpx.common.web`」「只用 `ApiForwarder` 轉發」兩條）：範本的 `ApiForwarder` 轉發時不帶瀏覽器 cookie 與 header（登入 session 到不了後端）、只有 `get`／`post`（PUT／DELETE 轉不到）、後端任何錯誤一律轉成 500 `{"message":"後端服務呼叫失敗"}`（後端 400 的 `field`／`max`／`actual` 到不了前端）。本系統改在 `com.mpx.infra_manager_web` 自建轉發器（規格見「前端架構」），仍遵守「不解析 payload、不開 CrossOrigin、頁面只打自己殼 jar」。請範本維護者評估把「透傳指定前綴的 cookie 與 header、全部 method、4xx 原樣回、本文串流透傳（含 multipart）與本文上限」納入範本；範本的殼 jar 開著 Spring multipart 解析，上傳本文會在轉發前被讀走，到後端變成空本文
3. **範本 pom 附帶 `mssql-jdbc`**：本系統只連 Oracle，用不到 SQL Server 驅動；但它是範本 pom 的一部分、範本 `com.mpx.common.db` 的測試可能依賴，本系統不自行移除，請範本維護者評估改為可選依賴。

#### 測試策略
- **單元測試**：`./mvnw clean package`（surefire）執行，不連真 DB 或真 API；需要 DB 的類別一律 mock `DbClient`（交易管理器的測試 mock `DbConnectionManager`）。S1 骨架共 78 項（範本 `com.mpx.common.db` 42 項、業務程式 36 項）；範本的 42 項不可修改、不可刪除
- **整合測試**：檔名 `*IT`，只在 `./mvnw verify`（failsafe）執行，直接連公司測試 Oracle（連線資訊 API 提供）。`host.properties` 的連線資訊 API 位址為空時略過（`HealthIT`、`TransactionRollbackIT`）；`TransactionRollbackIT` 另在交易測試表 `IM_TX_TEST` 尚未建立時略過，寫入的資料以隨機鍵區分、結束一律刪除
- **測試時不寄信**：surefire 與 failsafe 都以系統屬性 `im.mail.enabled=false` 關掉寄信排程（測試會讀本機真實的 `application.properties`，系統屬性優先於 properties 檔；否則開了寄信的開發機跑測試會真的寄出測試流程產生的信，並與 `MailOutboxDaoIT` 搶鎖）。會觸發寄信的流程 IT 清除資料時，依單號前綴（`JSON_VALUE(META_JSON,'$.appId') LIKE`）一併刪 `IM_MAIL_OUTBOX` 殘留
- 不用 H2、不用 Testcontainers
- **前端**：`npm run type-check`（vue-tsc）、`npm test`（Vitest，jsdom）；API 模組以 `vi.mock` 替換、不打真後端；路由守衛測試用真的 `router/index.ts`（模組單例，同一路徑重複 `push` 不會觸發守衛，所以每個測試先把位置擺到別的路由）。S2 結束時共 15 項（HomeView 4、LoginView 5、routerGuard 6）

#### 資料遷移（從舊 Node 系統）
- 工具：Java 匯入器（同一個後端 jar 以非 web 模式啟動的 `ApplicationRunner`，由 `--im.import.*` 參數觸發，資料存取走 `DbClient`，可重跑、可測試；帳號匯入已依此方式完成，見「密碼」段）。遷移工具最終選擇待定，見 BACKLOG.md 第 29 項；三代 AI 報告（v1／v2／v3）的轉換方式待定，見 BACKLOG.md 第 24 項
- 資料量：applications 93 張 HIST（紙本歷史單）+ 263 張 IM（其中 162 張有 AI 報告）、已刪除 12 張、使用者約 22 人、範本 3 份
- 轉換規則：
  - 時間：一律存成 `DATE` 台灣當地時間；舊資料的假 UTC（存台灣時間卻標 `Z`）由匯入程式修正
  - `''` → NULL，依「Oracle 19c 連動規則」
  - 人員：舊系統的字串帳號依「帳號工號對照表」轉成工號（`IM_USER.USER_ID`），所有人員欄位都存工號；比對帳號一律不分大小寫。`LOGIN_ID` 匯入時轉小寫、保留原有空白（舊資料如 `Alan Kuo`、`gary` 大小寫混用）。對照表待使用者提供，見 BACKLOG.md 第 60 項。alex 即陳儀仁，陳儀仁的紙本單直接掛 alex 的工號，不另建代用帳號
  - 紙本歷史單：申請人（`IM_APP.APPLY_USER_ID`）依對照表掛工號，對不到者掛停用的代用帳號 `HIST_PAPER`（`HIST_PAPER` 只用於申請人）；簽核紀錄沿用舊資料——簽核人（`IM_APPR_STEP.USER_ID`）取 `decidedBy`（如 `gary`）依對照表轉成工號，簽核時間（`IM_APPR_STEP.DECIDE_DATE`）取 `decidedAt`（舊系統補核慣例為紙本日 +1 天）；簽核流程掛停用的 `imported` 流程（單一 `POST_HOC` 事後補核關卡）
  - 簽核候選人 `candidateIds` 轉成工號寫入 `IM_APPR_CAND_MAP`（不存姓名、email 快照；名稱只存主檔）
  - 檢核表執行人：舊資料 `checklist.executor` 是自由文字（含廠商、多人）。空字串與「(紙本匯入)」存 NULL；能唯一對應到一位使用者者（不分大小寫比對登入帳號，或比對中文姓名）填 `IM_APP_CHECK_LIST.USER_ID`、`EXEC_USER_DESC` 留 NULL；其餘原文存 `EXEC_USER_DESC`
  - 機櫃位置：舊資料的區域、機櫃、U 位自由文字存 `IM_APP.AREA_NAME`／`RACK_NAME`／`U_RANGE`；新單只寫 `SITE_ID`／`RACK_ID`（與 `U_START_NO`／`U_END_NO`），名稱由外部機櫃系統帶出
  - 勾選類（設備類別、申請原因、影響範圍、檢核項）以名稱對應 `IM_FORM_OPTION`，對不到者先建 `STATUS` 0 的選項再掛
  - 匯入時 `CREATE_DATE`／`CREATE_BY` 寫入原始值；代理鍵若指定原值匯入，匯入完成後須重設該表 identity 起點（步驟見 `SETUP.md`）
  - AI 報告重算 `SNAP_HASH`：對 `aiReview.appSnapshot` 與目前申請單都用「key 排序 JSON + SHA-256」重算（舊 `computeAppHash` 是 `sha1(JSON.stringify(...))`，依賴 JS key 插入順序，Java 算不出同值）；舊值保留在 `LEGACY_SHA1`。`IM_AI_REVIEW`、`IM_TMPL` 主鍵沿用舊 id
  - 已刪除目錄匯成軟刪除（`IM_APP.STATUS` 0 + `DELETE_*` 欄位）；舊系統因「當天檔案數 + 1」取號，有 4 張已刪除單與現存單同號（`IM20260505-002`、`IM20260505-003`、`IM20260506-002`、`IM20260918-003`，皆為不同的單），主鍵 `APP_ID` 不允許重複，這 4 張已刪除單改號匯入：原單號後加後綴 `-D`（如 `IM20260505-002-D`），不從序號表取號；它們所有子表與關聯資料（含附件、AI 審查、事件紀錄、版次、簽核實例）的單號隨之改號。舊系統附件放在 `public/uploads/<單號>/`，撞號的已刪除單與現存單共用同一資料夾，附件歸屬以各單 JSON 的 `attachments[].storedName` 判定、不以資料夾判定；匯入時把這 4 張已刪除單中有附件的 2 張（`IM20260505-003`、`IM20260918-003`）的實體檔，複製到各自的新資料夾 `IM20260505-003-D`（1 個檔）與 `IM20260918-003-D`（4 個檔），資料夾名一律等於單號；`IM20260505-002`、`IM20260506-002` 無附件。細節見 BACKLOG.md 第 75 項
  - 申請單編號：舊單沿用原編號匯入（上述 4 張已刪除單除外）；依各前綴各日最大序號回填 `IM_APP_SEQ.LAST_NO`，避免切換當日新單、以及日後補匯同日紙本單撞號；先改號、後回填，回填時排除帶 `-D` 後綴的單號
  - 舊簽核人 `Eric` 與角色代碼 `dept_manager` 不在帳號檔，對應方式待定，見 BACKLOG.md 第 60 項
  - 自由文字欄（如檢核表執行人含 `&`）一律以 JDBC 參數綁定寫入，不產生 INSERT 腳本經 SQL*Plus 執行
  - 舊版次快照沒有表單內容；`IM_APP_VER.FORM_JSON` 為 NOT NULL，舊版次匯入時填什麼待定，見 BACKLOG.md 第 69 項
  - `workflows.json` 關卡 key 與名稱錯位（如 key `dept_manager` 名稱卻是「機房管理員」），以實際角色為準——預載的 `IM_FLOW_STEP` 已依 `ROLE_ID` 對好，`STEP_CODE` 沿用舊 key
  - 附件以 `public/uploads` 為準（`Docs/` 與 `public/uploads/HIST-*` 約 93 份重複檔不重複匯入）
- 對帳：
  - 筆數依狀態分組一致（IM 263、HIST 93、deleted 12）
  - 「DB 匯出回舊 JSON」工具逐檔 diff（已知轉換列入白名單）
  - 附件數／大小／sha256 一致
  - 人工抽 5 張：IM20260826-005、一張 HIST，以及從非終結狀態 25 張中抽（rejected 18、in_review 3、in_execution 1、pending_review 3）

#### 部署與維運
- 部署：Docker 容器，主機作業系統 Rocky Linux 9.7；容器的啟動與管理方式（docker compose 或 systemd）待定，見 BACKLOG.md 第 22 項。開發期在本機以 jar + bat 啟動（`start-new.bat`：後端 3202、殼 jar 3201），本機開發不使用 Docker
- 設定分三處：不變的放各 jar 的 `*.properties`（真檔不進 git，進 git 的是同名 `.properties.example`，鍵值見「環境設定」）；secret（機櫃 API 金鑰、SMTP 認證、AI 金鑰；server-side session 不需要簽章 secret）放環境變數（AI 金鑰存放處待定，見 BACKLOG.md 第 18 項）；DB 連線資訊不放本系統任何地方，由連線資訊 API 依別名提供；畫面上可調的放 `SYS_PARAM`
- 切換：新系統以 3201 port 上 UAT，正式切換後改用 3200，舊系統保留唯讀；切換策略待定，見 BACKLOG.md 第 27 項

### 資料結構

DDL 全部放在 `db/oracle/`，由 `rd_user` 手動執行（不使用 Flyway）：

| 檔案 | 內容 |
|---|---|
| `V1__init_schema.sql` | 業務表 31 張與預載資料 |
| `V2__tx_test_table.sql` | 交易回滾整合測試專用表 `IM_TX_TEST`（只供 `TransactionRollbackIT` 使用，平時應為空；與業務表脫鉤） |
| `V2a__tx_test_alter.sql` | 給已用舊版 V2 建過 `IM_TX_TEST` 的環境補共同欄位 `UPDATE_DATE`／`UPDATE_BY`；全新環境不需要 |
| `grant_ap_user.sql` | 把上述全部表的 SELECT／INSERT／UPDATE／DELETE 授權給 `ap_user`；可重複執行，新增表時補一行 |

業務表的定義來源是 `V1__init_schema.sql`（Oracle 19c，31 張表）；完整欄位、型別、約束與欄位說明以該檔為準，本節列每張表的用途、鍵與關鍵欄位。PK 主鍵、UK 唯一鍵、IX 索引。

#### 命名與欄位慣例
依公司《DB規範_V1_20261002_JLA》16 條，加上寫在 DDL 檔頭的本系統自訂慣例：
- 業務表前綴 `IM_`；`SYS_PARAM`（規範指定的系統參數表）不加前綴，其欄名以 `PARAM_` 為實體前綴；純鍵值的多對多對照表以 `_MAP` 結尾，並在表說明註明退場條件
- 主鍵：主檔用業務鍵（`IM_USER`、`IM_ROLE`、`IM_FLOW`、`IM_APP`、`IM_TMPL`、`IM_AI_REVIEW`、`IM_RACK_CACHE`）；`_MAP` 對照表（5 張）與 `SYS_PARAM` 保留複合主鍵；其餘 18 張表一律以「表名_ID」identity 代理鍵為主鍵（`NUMBER(19) GENERATED BY DEFAULT ON NULL AS IDENTITY`，匯入時可指定原值）；有業務鍵者設唯一鍵（`UK_`）或唯一索引（`IND_`），`IM_ACCESS_LOG`、`IM_APP_EVENT`、`IM_MAIL_OUTBOX` 為流水紀錄、無業務鍵
- 人員欄位一律存工號，型別 `VARCHAR2(30)`，參照 `IM_USER.USER_ID`；同表只參照一次時欄名即 `USER_ID`，參照多次時加角色前綴（`APPLY_USER_ID`、`DELETE_USER_ID`、`OWNER_USER_ID`、`LAST_USE_USER_ID`）。登入帳號另存在 `IM_USER.LOGIN_ID`。常設例外只有 `IM_APP_CHECK_LIST.EXEC_USER_DESC`（執行人原文，廠商與多人沒有工號），為常設欄位、不設退場條件；另 `IM_ACCESS_LOG.LOGIN_ID` 為帶退場條件的帳號快照（確認 `USER_ID` 足以追查後移除）
- 表間參照為邏輯參照，DDL 不建 FOREIGN KEY 約束
- 名稱類只存主檔（`IM_USER`／`IM_ROLE`／`IM_FLOW_STEP`／`IM_FORM_OPTION`），其他表只存代碼或鍵值；例外（規範 12）見各欄說明，例如 `IM_APP.SUP_NAME`（廠商名稱）、`IM_APP_CHECK_LIST.EXEC_USER_DESC`（執行人原文）、`IM_USER.DEPT_NAME` 與 `IM_APP.APPLY_DEPT_NAME`（無部門主檔）、`IM_APP.AREA_NAME` 與 `IM_APP.RACK_NAME`（舊資料自由文字，有退場條件）。廠商部分：舊資料的廠商只有文字（368 張單中 263 張有廠商名稱，如「晉泰科技」），施工廠商是否在公司廠商主檔 `CMN_SUP` 內待 DBA 確認（BACKLOG.md 第 76 項）
- 欄名後綴：代碼 `_CODE`；整數編號（版次、U 位、序號）`_NO`；數量 `_QTY` 並在欄名帶單位（`DUR_MS_QTY`、`FILE_BYTE_QTY`、`EST_HOUR_QTY`）；計次（筆數、次數、人數）`_CNT`；JSON `_JSON`；布林前綴 `IS_`
- 每張表都有共同欄位 `STATUS NUMBER(1)`（1 啟用／0 停用）與 `CREATE_DATE`（default SYSDATE）、`CREATE_BY`、`UPDATE_DATE`、`UPDATE_BY`（存工號）。`IM_APP` 的 STATUS 0 = 已刪除；`IM_USER` 的 STATUS 0 = 停用。預載資料與排程產生的資料 `CREATE_BY` 填 `SYSTEM`；`IM_ACCESS_LOG` 未登入請求的 `CREATE_BY` 填 `ANONYMOUS`（兩者皆對不到 `IM_USER`）
- 約束與索引命名：`PK_表名`、`UK_表名_欄名`、`CK_表名_欄名`（總長 ≤ 30，過長取簡稱）、`IND_表名_NN`

#### 身分
- `IM_USER` 使用者主檔：PK `USER_ID`（工號）；`LOGIN_ID` UK、CHECK 強制小寫；`USER_NAME`、`EMAIL`、`JOB_TITLE`、`DEPT_NAME`、`TEL`；`PWD_HASH`（NULL 表示尚未設定）、`IS_DFLT_PWD`、`PWD_CHANGE_DATE`。**使用者只能停用（STATUS 0）、不能刪除**。預載一個停用的代用帳號 `HIST_PAPER`（登入帳號 `hist_paper`、無密碼，不可登入），只供對不到申請人的紙本歷史單掛申請人
- `IM_ROLE` 角色主檔：PK `ROLE_ID`；`ROLE_NAME`、`SORT_NO`。預載 6 筆：admin 系統管理員、it_manager 資訊主管、dept_manager Infra 主管、idc_admin 機房管理員、governance 資訊治理、infra Infra 同仁
- `IM_USER_ROLE_MAP`：PK (`USER_ID`, `ROLE_ID`)；一人可多角色
- `IM_LOGIN_TOKEN` 登入權杖：PK `LOGIN_TOKEN_ID`；`TOKEN_HASH` UK（SHA-256，不存明文）；`USER_ID`、`LAST_USE_DATE`、`EXPIRY_DATE`、`USER_AGENT`；STATUS 0 = 已登出或撤銷。IX (`USER_ID`, `STATUS`)。**S2 不做 remember-me，此表先保留不使用**（2026-10-06 裁示 ③A）；表說明「取代舊系統記憶體 session」與現行 Tomcat session 設計不符、待改，見 BACKLOG.md 第 85 項

#### 簽核流程定義與簽核實例（共用簽核引擎，給未來模組重用）
- `IM_FLOW` 簽核流程主檔：PK `FLOW_ID`；`FLOW_NAME`、`FLOW_DESC`；`DOC_TYPE`（CR／ACCOUNT／INSPECTION）；`IS_DFLT`（同一 `DOC_TYPE` 只能有一個預設流程，且預設流程必須啟用）。預載 4 筆：`full`（P3／P4 預設，5 關）、`p2_high`（Infra 主管單關，允許代簽）、`p1_emergency`（資訊主管事後補核）、`imported`（93 張紙本歷史單專用，停用、新單不可選）
- `IM_FLOW_STEP` 流程關卡定義：PK `FLOW_STEP_ID`；`FLOW_ID`、`SEQ_NO`、`STEP_CODE`、`STEP_NAME`；`APPR_TYPE`（USER 時填 `USER_ID`／ROLE 時填 `ROLE_ID`，由 CHECK 保證二擇一）；`IS_NOTIFY_ONLY`、`IS_ALLOW_DELEG`；`STEP_MODE_CODE`（SEQUENTIAL 送審後依序簽核／POST_HOC 事後補核）。同一流程內啟用中的關卡 `SEQ_NO` 不得重複；修改流程時須在同一交易內先把舊列 STATUS 改 0、再新增新列（順序顛倒會違反唯一索引），不得改寫已被簽核實例參照的列
- `IM_APPR` 簽核實例（一次送審一筆）：PK `APPR_ID`；`DOC_TYPE`、`DOC_ID`（CR 時為 `IM_APP.APP_ID`）、`DOC_VER_NO`、`FLOW_ID`；`APPR_STATUS_CODE`（PENDING／APPROVED／REJECTED／RECALLED／CANCELLED）；`START_DATE`、`CLOSE_DATE`。同一文件同一版次只能有一筆狀態不是 RECALLED／CANCELLED 的實例（撤回後在同版次重送會再開一筆）
- `IM_APPR_STEP` 簽核實例關卡：PK `APPR_STEP_ID`；UK (`APPR_ID`, `SEQ_NO`)；`FLOW_STEP_ID`（關卡名稱、是否僅通知等屬性從 `IM_FLOW_STEP` 取，不重複存放）；`STEP_STATUS_CODE`（WAITING／PENDING／APPROVED／REJECTED／SKIPPED／CANCELLED）；狀態為 APPROVED／REJECTED 時 `USER_ID`（實際簽核人，代簽時為代簽人）與 `DECIDE_DATE` 必填；`MEMO`（CLOB）
- `IM_APPR_CAND_MAP` 關卡候選人：PK (`APPR_STEP_ID`, `USER_ID`)；送審當下固定候選人；IX (`USER_ID`) 供「我的待辦」查詢。退場條件：改成簽核時即時查角色池則移除本表
- `IM_APPR_STEP_ITEM` 逐項裁決：PK `APPR_STEP_ITEM_ID`；UK (`APPR_STEP_ID`, `ITEM_REF`)；`DECIDE_CODE`（APPROVED／REJECTED）；`MEMO`（CLOB）；`DECIDE_DATE`

#### 申請單
- `IM_APP_SEQ` 申請單編號序號：PK `APP_SEQ_ID`；UK (`SEQ_PREFIX`, `SEQ_DATE`)；`SEQ_PREFIX`（IM／HIST）；`LAST_NO`。每日每前綴獨立計數，以 `SELECT … FOR UPDATE` 取號，刪掉的號碼不再使用
- `IM_APP` 申請單主檔：PK `APP_ID`（申請單編號，`VARCHAR2(20)`）；`APP_TITLE`；`PRIO_CODE`（P1～P4）；`FLOW_ID`；`APPLY_USER_ID`；`SOURCE_CODE`（ONLINE／IMPORTED）；`CURR_VER_NO`；`ROW_VER_NO`（樂觀鎖）
  - **申請單狀態 `APP_STATUS_CODE` 共七種**：`DRAFT` 草稿、`IN_REVIEW` 簽核中、`APPROVED` 已核准(待執行)、`IN_EXECUTION` 執行中、`PENDING_REVIEW` 待治理審核、`EXECUTED` 已結案、`REJECTED` 已退件（中文為畫面用語，與舊系統一致）
  - 刪除：軟刪除，`STATUS` 0 = 已刪除，此時 `DELETE_DATE`、`DELETE_USER_ID`、`DELETE_MODE_CODE`（ADMIN 管理員刪除／APPLICANT_PRE_REVIEW 申請人在還沒有人簽過、未補件過時自行刪除；DDL 註解仍寫「送審前」，見 BACKLOG.md 第 106 項）必填；`DELETE_REASON`
  - 基本資料：`APPLY_DATE`；`APPLY_DEPT_NAME`、`APPLY_TEL`、`APPLY_EMAIL`（填單當下的申請人資料快照）；`IS_SELF_EXEC`、`IS_SUP_EXEC`；`WORK_MODE_CODE`（ONSITE／REMOTE）、`REMOTE_METHOD`；`SUP_NAME`、`SUP_CNTCT`、`SUP_TEL`、`SUP_HEAD_CNT`
  - 作業內容：`WORK_SUBJ`、`IMPACT_DESC`（CLOB）、`WORK_DETAIL`／`RISK_DESC`／`ROLL_BACK_PLAN`（CLOB）、`OTHER_REASON`、`SCHED_START_DATE`／`SCHED_END_DATE`、`EST_HOUR_QTY`、`RESUB_MEMO`（CLOB，最近一次補件說明）
  - 位置：`LOC_SOURCE_CODE`（IMPACT／MANUAL）；**新單只寫 `SITE_ID`／`RACK_ID`** 與 `U_START_NO`／`U_END_NO`，區域與機櫃名稱由外部機櫃系統（Impact）帶出；`AREA_NAME`／`RACK_NAME`／`U_RANGE` 只承接舊資料的自由文字（退場條件：舊資料全數對應代碼並經 DBA 確認後移除）；`OMIT_REASON`
- `IM_APP_CATG_MAP`、`IM_APP_REASON_MAP`、`IM_APP_SCOPE_MAP`：PK (`APP_ID`, `FORM_OPTION_ID`)；設備類別子項、申請原因、影響範圍的多選勾選
- `IM_APP_CATG_OTHER`：PK `APP_CATG_OTHER_ID`；UK (`APP_ID`, `FORM_OPTION_ID`)；`OTHER_TEXT`（各類別的「其他」補充文字）
- `IM_APP_EQUIP` 異動設備：PK `APP_EQUIP_ID`；UK (`APP_ID`, `SEQ_NO`)；`EQUIP_NAME`、`ASSET_NO`（IX）、`MODEL_NO`、`SERIAL_NO`（IX）、`PURP_DESC`、`MGMT_IP`
- `IM_APP_PLAN_STEP` 作業計畫步驟：PK `APP_PLAN_STEP_ID`；UK (`APP_ID`, `SEQ_NO`)；`STEP_TEXT`
- `IM_APP_CHECK_LIST` 執行檢核表：PK `APP_CHECK_LIST_ID`；UK (`APP_ID`, `APP_VER_NO`, `SEQ_NO`)；`FORM_OPTION_ID`（檢核項，群組 CHECK_LIST 共 11 項）；`IS_DONE`、`DONE_DATE`；`USER_ID`（執行人工號）；`EXEC_USER_DESC`（執行人自由文字，`VARCHAR2(200 CHAR)`，供廠商、多人等非系統使用者；新舊單皆可填）。`USER_ID` 與 `EXEC_USER_DESC` 互斥、至多填一欄（CHECK 約束 `CK_IM_APP_CHECK_LIST_EXEC_USER` 保證）。線上單在該版次第一次存執行紀錄時依啟用中的 CHECK_LIST 選項整批展開，之後只更新不增刪
- `IM_APP_EXEC` 執行結果：PK `APP_EXEC_ID`；UK (`APP_ID`, `APP_VER_NO`)；`ACTUAL_START_DATE`／`ACTUAL_END_DATE`；`RESULT_CODE`（DONE／DONE_ADJ／PARTIAL／NOT_DONE／CANCEL，CHECK 約束 `CK_IM_APP_EXEC_RESULT_CODE` 寫死這五個代碼；暫存時為 null）；`IS_EXCPT`／`EXCPT_DESC`（CLOB）；`IS_FOLLOW_UP`／`FOLLOW_UP_DESC`（CLOB）；`EXEC_MEMO`（CLOB）；`USER_ID`（結案人，即送治理審查的人）、`CLOSE_DATE`（送治理審查時間），兩者只在送治理審查時寫入，暫存時維持 null（暫存人看 `UPDATE_BY`）
- `IM_APP_VER` 歷史版次快照：PK `APP_VER_ID`；UK (`APP_ID`, `APP_VER_NO`)；`CLOSE_STATUS_CODE`（REJECTED／EXEC_REJECTED／GOV_RETURNED／RECALLED）；`VER_REASON`（CLOB）；`SNAP_DATE`；`FORM_JSON`（該版表單全文，NOT NULL；線上補件寫入的格式為 `model.changerequest.AppVersionSnapshot` 的 JSON，首欄 `snapshotSchema: 1`，欄位與檢視 API 的表單部分相同、附件只列該版的）。線上流程只在補件時寫入（撤回不產生新版次，所以不會出現 RECALLED）。目前版次不在本表
- `IM_APP_EVENT` 狀態事件：PK `APP_EVENT_ID`；IX (`APP_ID`, `EVENT_DATE`)；`APP_VER_NO`；`EVENT_CODE`（SUBMIT／RECALL／EXEC_REJECT／GOV_PASS／GOV_RETURN／RESUBMIT／DELETE／RESTORE）；`USER_ID`；`EVENT_DATE`；`MEMO`（CLOB）

#### 附件（各模組共用）
- `IM_ATTACH`：PK `ATTACH_ID`；`OWNER_TYPE`（APP／STEP／EVENT／AI）＋ `OWNER_ID`（IX，以文字存所屬物件鍵值）；`ORIG_FILE_NAME`、`STORE_FILE_NAME`；`FILE_PATH`（相對於附件根目錄的路徑，UK；CHECK 擋 `..`、斜線開頭與磁碟代號開頭，只是縱深防禦，應用層仍須 normalize 後確認位於根目錄下）；`FILE_BYTE_QTY`、`MIME_TYPE`、`SHA256_HASH`。檔案本體存檔案系統，本表只存路徑；下載經後端檢查權限

#### AI 審查（去留待定，見 BACKLOG.md 第 17 項；表先建以免匯入遺失）
- `IM_AI_REVIEW`：PK `AI_REVIEW_ID`（沿用舊檔 id）；IX (`APP_ID`, `CREATE_DATE`)；`MODEL_NAME`、`EFFORT_CODE`、`REVIEW_MODE_CODE`、`FALL_BACK_FROM`；`APP_VER_NO`、`APP_STATUS_CODE`；`APP_SNAP_JSON`、`SNAP_HASH`（key 排序 JSON + SHA-256）、`LEGACY_SHA1`；`RESULT_JSON`、`RESULT_SCHEMA_CODE`（v1／v2／v3）；`INPUT_TOKEN_CNT`、`OUTPUT_TOKEN_CNT`、`CACHE_READ_CNT`、`CACHE_WRITE_CNT`、`DUR_MS_QTY`、`STOP_REASON`；`SEND_DATE`、`SEND_TO_JSON`；`REVIEW_STATUS_CODE`（PENDING／DONE／FAILED）、`ERROR_TEXT`
- `IM_AI_REVIEW_MSG` 追問對話：PK `AI_REVIEW_MSG_ID`；UK (`AI_REVIEW_ID`, `SEQ_NO`)；`MSG_ROLE_CODE`（USER／ASSISTANT）；`MSG_TEXT`（CLOB）；`ASK_APP_VER_NO`；`MODEL_NAME`、`FALL_BACK_FROM`；用量欄位同 `IM_AI_REVIEW`

#### 範本、表單選項、系統參數
- `IM_TMPL` 範本：PK `TMPL_ID`（沿用舊 id）；`TMPL_NAME`；`FORM_JSON`；`OWNER_USER_ID`（IX）；`USE_CNT`、`LAST_USE_DATE`、`LAST_USE_USER_ID`
- `IM_FORM_OPTION` 表單選項主檔：PK `FORM_OPTION_ID`；UK (`GROUP_CODE`, `OPTION_CODE`)；`GROUP_CODE` 八群組——PRIO 優先等級（附說明、時限、流程說明、範例與對應 `FLOW_ID`）、CATG 設備類別、CATG_ITEM 類別子項（`UP_FORM_OPTION_ID` 指向所屬 CATG，只有本群組可填）、REASON 申請原因、SCOPE 影響範圍、CHECK_LIST 執行檢核項、EXEC_RESULT 執行結果、SIGN_ROLE 列印表單簽名欄角色；`OPTION_NAME`、`COLOR_CODE`、`SORT_NO`。預載自舊系統 form-schema.json，之後由 admin 在後台維護；停用（`STATUS` 0）的選項不在表單顯示，既有單據仍可帶出名稱
- `SYS_PARAM` 系統參數：PK (`PARAM_NAME`, `PARAM_VALUE`)；`PARAM_DESC`、`MEMO` 必填；`NAME`／`VALUE`／`DESC` 三欄依規範 11（避免保留字或易混淆的單字欄名，`NAME` 在列，`DESC` 為 Oracle 保留字）加 `PARAM_` 前綴，前綴與表名 `SYS_PARAM` 一致，說明欄用字典縮寫 `DESC`（規範 3）；`VALUE` 是 Oracle 關鍵字、與保留字 `VALUES` 易混淆，且為求 `SYS_PARAM` 的欄名一致一併加前綴；`MEMO` 不是保留字，沿用範例與字典原名。與規範範例工作表的 `NAME`／`VALUE`／`DESCR` 不同，此說明已寫進表說明隨 xlsx 送 DBA（BACKLOG.md 第 76 項）。多值參數一值一列。參數：`SITE_NAME`、`SITE_SHORT_NAME`、`TIME_ZONE`（Asia/Taipei）、`UPLOAD_MAX_MB`（預載 50）、`UPLOAD_MAX_FILES`（預載 30）、`FLOW_POLICY`（full_only／by_priority，預載 full_only）、`EXCLUDE_IP`（預設無）、`ADMIN_EMAIL`（部署時填入，不預載）。後端讀取規則（`UPLOAD_MAX_MB`、`UPLOAD_MAX_FILES`、`FLOW_POLICY`）：每次請求查 DB、只取 `STATUS` 1，同名多列時取最後異動的一列；未設定、非正整數或不在允許值內時退回預設（50／30／full_only）並寫 warn（只記參數名）；`UPLOAD_MAX_MB` 只能調低，設超過 50 仍以 50 為準（後端與殼 jar 的本文上限固定 50 MB）

#### 郵件、存取紀錄、外部快取
- `IM_MAIL_OUTBOX` 郵件寄件匣：PK `MAIL_OUTBOX_ID`；IX (`MAIL_STATUS_CODE`, `CREATE_DATE`)；`TO_JSON`／`CC_JSON`／`BCC_JSON`（CLOB，email 字串的 JSON 陣列，CHECK IS JSON）；`MAIL_SUBJ`（500 字）；`HTML_BODY`（CLOB）；`MAIL_STATUS_CODE`（PENDING／SENT／FAILED）；`TRY_CNT`（已嘗試次數，寄成功也加 1）；`ERROR_TEXT`（1000 字，失敗原因或部分寄達備註）；`SMTP_MSG_ID`；`SEND_DATE`；`META_JSON`（CLOB，`event`、`appId`，改寄時另有原收件人）。寫入與寄送規則見「後端分層」的「信件」
- `IM_ACCESS_LOG` HTTP 存取紀錄：PK `ACCESS_LOG_ID`；IX (`CREATE_DATE`)、(`USER_ID`, `CREATE_DATE`)；`USER_ID`、`LOGIN_ID`、`IP_ADDR`、`HTTP_METHOD`、`REQ_PATH`、`QUERY_TEXT`、`ACTION_CODE`、`HTTP_STATUS_CODE`、`DUR_MS_QTY`、`USER_AGENT`
- `IM_RACK_CACHE` 外部機櫃系統回應快取：PK `RACK_CACHE_ID`（快取鍵值，由程式定義）；`RESP_JSON`；`FETCH_DATE`

#### 測試專用表（V2）
- `IM_TX_TEST` 交易回滾測試：PK `TX_TEST_ID`（identity）；`TEST_KEY`（單次測試的隨機 UUID，IX）、`MEMO`；共同欄位同業務表。只供 `TransactionRollbackIT` 寫入並自行清理，平時應為空；退場條件：改用其他方式驗證交易時刪除

#### 版次表達
每一輪送審一筆 `IM_APPR`（帶 `DOC_VER_NO`），關卡在 `IM_APPR_STEP`（待辦與統計要用）；檢核表與執行結果依 `APP_VER_NO` 分版；補件時前一版表單全文凍結到 `IM_APP_VER.FORM_JSON`（只做整份比對），目前版次的內容在 `IM_APP` 與其子表。

#### 申請單編號
沿用舊系統格式：線上申請 `IM{yyyymmdd}-{NNN}`、紙本匯入 `HIST-{yyyymmdd}-{NNN}`；新單產號一律走 `IM_APP_SEQ`（前綴限 IM／HIST）。

### API 規格

全部 REST。後端（3202）的路徑前綴是 `/api`、沒有 context path；瀏覽器一律經殼 jar（3201）呼叫 `/infra_manager_web/api/v1/...`，由殼 jar 轉發到後端同名的 `/api/...`（例：`/infra_manager_web/api/v1/health` → `/api/health`），版本號只在殼 jar 這一層。下表列的是後端路徑。寫入需 session + CSRF token（cookie 與 header 由殼 jar 轉發器透傳，見「後端分層」的「認證」）。「舊系統權限（對照用）」欄是 Node 版現況，**不是新系統的權限規則**；新系統所有 API 一律須登入（2026-10-06 裁示，原 BACKLOG.md 第 26 項結案；例外只有 `/api/health`、`/api/auth/me`、`/api/auth/login`、`/api/auth/logout`），已實作的列表／檢視／附件下載三列已改記新系統的權限，其餘列待各階段實作時連同第 30 項（舊漏洞處理）一併改寫。請求參數與回應格式各階段實作時補上。

| 新 REST API | 功能 | 舊系統權限（對照用） | Vue 頁面 |
|---|---|---|---|
| GET /api/health | 健康檢查 | — | — |
| GET /api/dashboard | 首頁統計、我的待辦 | 公開 | HomeView |
| GET /api/auth/me | 目前登入者。永遠 200：`{loggedIn, userId, loginId, userName, roles, mustChangePassword}`，未登入只有 `loggedIn:false`；回應順便發 `IM_XSRF` cookie | 公開 | 所有頁面（路由守衛） |
| POST /api/auth/login | 登入。本文 `{loginId, password}`（帳號不分大小寫、最長 64；密碼最長 128）；成功 200、本文同 `/me`，並建立 session；帳密錯／停用 401 `帳號或密碼錯誤`；缺欄位或超長 400；需 CSRF header。`mustChangePassword:true` 時前端導向改密碼頁 | 公開 | LoginView |
| POST /api/auth/password | 改密碼。本文 `{oldPassword, newPassword}`；成功 200、本文同 `/me`（`mustChangePassword:false`）並重建 session；規則不符、舊密碼錯、帳號狀態已變更一律 400 帶訊息（規則與訊息見「密碼」段）；缺欄位 400 `請求格式錯誤`；需 CSRF header | 登入（含預設密碼者） | ChangePasswordView |
| POST /api/auth/logout | 登出。204，未登入也 204；需 CSRF header | 公開 | — |
| GET /api/apps?status&priority&source&mine&q&from&to&page | 列表。`status` 七種狀態碼之一、`priority` P1～P4、`source` ONLINE／IMPORTED、`mine` 待我簽核、`q` 關鍵字（trim 後上限 100 字，比對單號／標題／作業主旨／申請人姓名，不分大小寫）、`from`／`to` 建立日期 `yyyy-MM-dd`（比對單據建立時間、不是申請日；都沒填才套近 90 天；`from` 晚於 `to` 400）、`page` 從 1 起（每頁 20，超出範圍夾回）；篩選值不合法 400 帶訊息、格式錯 400 `請求格式錯誤`。回 `{items, total, page, size, mineCount}`，`items` 待我簽核者置頂、再依建立時間新到舊；每筆含單號、標題、優先度（代碼／名稱／顏色）、作業主旨、申請部門、申請人、版次、狀態、來源、目前關卡與簽核人（候選人多於一人顯示「N 人待簽」）、`mine`、建立時間。AI 摘要與費用待第 17 項 | 登入 | AppListView |
| GET /api/form-options | 申請表單用的選項與上傳限制：`options` 為啟用中（`STATUS` 1）的 `IM_FORM_OPTION`，依群組、`SORT_NO`、ID 排序，每項 `{formOptionId, groupCode, code, name, upFormOptionId, colorCode, desc, timeLimitDesc, prioFlowDesc, sampleDesc, flowId, sortNo}`；`upload` 為 `{maxMb, maxFiles}`（讀取規則見 `SYS_PARAM`），只供前端提示，實際檢核在後端 | 登入 | AppFormView |
| POST /api/apps | 建草稿（JSON，不含附件）：取號、套 `FLOW_POLICY`，成功 201 `{appId, rowVerNo: 0}`；檢核與取號規則見「後端分層」的「草稿新增與編輯」。選填 query `templateId`：同交易累計該範本套用次數，範本不存在不擋建單 | 登入 | AppFormView |
| POST /api/apps/{id}/attachments | 草稿附件上傳（multipart，一次一檔、part 名 `file`；副檔名白名單，檔數與大小依 `SYS_PARAM`；只限 DRAFT 或 REJECTED——退件後申請人可先補附件再補件，刪除仍只限 DRAFT），成功 201 回附件元素（同檢視 API）；規則與錯誤訊息見「後端分層」的「附件」 | 申請人 | AppFormView |
| GET /api/apps/{id} | 檢視。回完整表單（基本資料、樂觀鎖版號 `rowVerNo`、申請人、廠商、分類／原因／範圍選項（各帶 `formOptionId`）含其他說明、設備、計畫步驟、排程、位置、補件說明）、檢核表（每項 `{seqNo, code, name, done, doneAt, executor, userId, executorDesc}`，`executor` 為顯示用姓名或文字；該版次還沒存過執行紀錄時為空陣列）、執行結果、簽核鏈（未送審的草稿依流程定義展開、全部 WAITING；每關候選人姓名）、`approvalHistory`（目前實例以外的歷次簽核，含被撤回、被退件、被取消的那幾輪，依建立先後，每筆 `{apprId, verNo, statusCode, startedAt, closedAt, steps}`）、附件索引、版次、事件、`permissions`（`canDecide`、`canResubmit`、`canRecall`、`canExecute`、`canReview`、`canDelete`、`canAiReview`、`canSubmit`、`canEditDraft`、`deleteMode`）。日期時間一律 `yyyy-MM-dd HH:mm` 台灣時間字串。單號格式不合或查無（含已軟刪除）404 `找不到申請單`。`canDecide` 要求狀態為 `IN_REVIEW` 且為目前關卡候選人（修正舊系統狀態不對仍可簽的漏洞）；`canSubmit` 為草稿且登入者是申請人或 admin；`canRecall` 為審核中且登入者是申請人（已有關卡簽過時後端仍回 409）；`canResubmit` 為退件且登入者是申請人；`canExecute` 為待執行或執行中、尚未填執行結果，且登入者是 idc_admin 或申請人；`canReview` 為待治理審核且登入者有 governance 角色；`canDelete`／`deleteMode` 規則同刪除端點（admin 恆 ADMIN；申請人限目前版次沒人簽過、未補件過且狀態允許）；`canAiReview` 在 S11 實作前固定 false | 登入 | AppViewView |
| GET（HEAD）/api/apps/{id}/attachments/{attachId} | 附件下載（規則見「後端分層」的「附件」）；`attachId` 非數字 400 `請求格式錯誤` | 登入 | AppViewView |
| PUT /api/apps/{id} | 草稿編輯（帶 `rowVerNo` 樂觀鎖），成功 200 `{appId, rowVerNo}`；版本不符 409「申請單已在其他地方修改過，請重新載入頁面後再編輯」、已不是草稿 409「申請單已不是草稿，無法編輯，請重新載入頁面」，其餘見「後端分層」的「草稿新增與編輯」 | 申請人 | AppFormView |
| POST /api/apps/{id}/submit | 送審。本文 `{rowVerNo}`（`reason` 忽略）；成功 200 `{appId, rowVerNo}`；必填缺漏或某關沒有候選人 400 帶訊息、不是草稿／版本不符 409，規則與訊息見「後端分層」的「簽核引擎」。AI 閘門待第 17 項 | 申請人或 admin | AppViewView |
| POST /api/apps/{id}/recall | 撤回成草稿。本文 `{rowVerNo, reason?}`（原因上限 2000 字）；成功 200 `{appId, rowVerNo}`；已有關卡簽過／不在審核中／版本不符 409 | 申請人 | AppViewView |
| POST /api/apps/{id}/decisions | 同意／退件。本文 `{rowVerNo, decision: APPROVE\|REJECT, memo}`（意見上限 2000 字，退件必填）；成功 200 `{appId, rowVerNo}`；不是候選人 403；關卡被搶／不在審核中／版本不符 409。附檔見 BACKLOG.md 第 101 項 | 目前關卡候選人 | AppViewView |
| POST /api/apps/{id}/resubmit | 退件補件 → 新版次直接進審核。本文 `{rowVerNo, resubMemo, form}`（`form` 同草稿編輯本文）；成功 200 `{appId, rowVerNo}`；非申請人 403；不是退件或版本不符 409；表單錯誤或送審必填缺漏 400 | 申請人 | AppFormView |
| PUT /api/apps/{id}/execution | 執行檢核表與實際紀錄：本文 `{rowVerNo, checklist, actualStart, actualEnd, resultCode, exception, exceptionDesc, followUp, followUpDesc, memo}`；沒填結果為暫存（→ `IN_EXECUTION`）、有填結果送治理審查（→ `PENDING_REVIEW`）；成功 200 `{appId, rowVerNo, statusCode}`；檢核與錯誤訊息見「後端分層」的「執行與治理審查」 | idc_admin 或申請人 | ExecuteView |
| POST /api/apps/{id}/execution/reject | 執行端退回（`APPROVED`／`IN_EXECUTION` → `REJECTED`）：本文 `{rowVerNo, memo}`，意見必填；成功 200 `{appId, rowVerNo}` | idc_admin 或申請人 | ExecuteView |
| POST /api/apps/{id}/governance-review | 治理審查：本文 `{rowVerNo, decision, memo}`，`decision` 為 `PASS` 或 `RETURN`，PASS → `EXECUTED`、RETURN → `REJECTED`（退回意見必填）；成功 200 `{appId, rowVerNo, statusCode}` | governance | AppViewView |
| DELETE /api/apps/{id} | 軟刪除。JSON 本文 `{rowVerNo, confirmId, reason}`（`confirmId` 須等於單號、原因必填上限 500 字）；成功 200 `{appId}`；確認編號不符或原因空白 400；沒有刪除權限 403；不存在或已刪 404；版本不符 409。經殼 jar 轉送 DELETE 本文有端到端測試（`ApiProxyStreamingTest`） | admin 或申請人（條件） | AppViewView |
| POST /api/apps/{id}/ai-reviews | AI 審查（非同步 202；hash 沒變略過，force 強制） | 申請人（draft）或 admin | AppViewView |
| POST /api/ai-reviews/{rid}/messages | AI 報告追問 | 登入 | AppViewView |
| POST /api/ai-reviews/{rid}/send | 寄出 AI 報告 | admin | AppViewView |
| GET /api/templates | 範本列表（名稱排序，一次回全部）：`tmplId`、名稱、優先等級（代碼／名稱／顏色）、建立者姓名、套用次數、最後套用時間與人、更新時間、`canEdit` | 登入 | TemplateListView、AppFormView |
| GET /api/templates/{id} | 單筆含 `form`（格式見「後端分層」的「範本」）與 `canEdit` | 登入 | TemplateEditView、AppFormView |
| POST /api/templates | `{tmplName, form}` → 201 `{tmplId}` | 登入 | TemplateEditView |
| PUT /api/templates/{id} | `{tmplName, form}` → 200 `{tmplId}`；非建立者且非 admin 403 | 建立者或 admin | TemplateEditView |
| DELETE /api/templates/{id} | 軟刪除 → 204；非建立者且非 admin 403 | 建立者或 admin | TemplateListView |
| GET /api/stats | 日／週／月分桶統計 | 公開 | StatsView |
| GET /api/access-logs | 存取紀錄（最後 500 筆） | admin | AccessLogView |
| GET /api/rack-data、GET /api/rack-data/status、POST /api/rack-data/refresh | 機櫃盤點資料、快取狀態、強制刷新（是否需登入待定，見 `BACKLOG.md` 第 26 項；refresh 限 admin） | 前兩者公開；refresh admin | 機櫃選擇器 |
| /api/admin/users | 使用者新增、修改、停用（不提供刪除） | admin | AdminUsersView |
| /api/admin/workflows | 簽核流程 CRUD | admin | AdminWorkflowsView |
| /api/admin/mail | 外寄紀錄、失敗紀錄、信件內容、測試信 | admin | AdminMailView、MailDetailView、MailTestView |
| PUT /api/admin/settings | 系統設定 | admin | AdminSettingsView |
| GET /api/form-schema | 表單設定讀取（後台維護用的寫入 API 待定，見 BACKLOG.md 第 74 項） | admin | AdminFormSchemaView |

#### GET /api/health
- 請求：無參數
- 回應：HTTP 一律 200，`{"status": "UP", "db": "UP" | "DOWN", "time": "yyyy-MM-dd HH:mm:ss"}`。`status` 固定 `UP`（程式活著）；`db` 為對 `db.connect.itflow` 別名執行 `SELECT 1 FROM DUAL` 的結果；`time` 為台灣時間。DB 失敗不回錯誤碼，讓前端分辨「後端連不上」（請求本身失敗）與「後端活著但 DB 連不上」（200 且 `db` 為 `DOWN`）
- 結果快取 5 秒；快取過期時同時只有一條執行緒去查 DB，其他請求回舊值（第一次查詢、還沒有舊值時才等它查完）
- 首頁顯示：`status` 取到 → 後端服務「正常」；`db` 為 `UP` → 資料庫「正常」，否則「無法連線」；請求失敗 → 後端服務「無法連線」、資料庫「未知」並出 toast

#### 錯誤回應格式
後端所有錯誤回應都是 JSON，至少含 `message`，一律不帶內部細節（例外訊息、SQL、主機）：

| 狀態碼 | 情況 | 回應 |
|---|---|---|
| 400 | 文字欄位超過字數上限 | `{"message", "field", "max", "actual"}`；`field` 為 camelCase 的 JSON 欄名，`message` 如「「影響說明」超過 2000 字（目前 N 字）」 |
| 400 | JSON 格式錯誤、參數型別錯誤 | `{"message": "請求格式錯誤"}` |
| 400／404／405／406／415 | Spring MVC 自身的錯誤（缺必要參數、路徑不存在、方法不允許、媒體型別不支援等） | 照原狀態碼回 `{"message": "請求無法處理"}` |
| 400 | 業務檢核不通過（必填、選項不正確、檔案類型、檔數或大小超過上限等） | `{"message"}`，訊息見各端點規則 |
| 403 | 已登入但不是允許的人（例：非申請人編輯草稿或上傳附件） | `{"message": "無權限執行此操作"}` |
| 409 | 樂觀鎖版本不符、或單子狀態已不允許此操作 | `{"message"}`，訊息見各端點規則；前端提示重新載入 |
| 413 | 請求本文超過上限（JSON 1 MB、單檔 50 MB、整個請求 500 MB） | `{"message": "請求內容過大"}` |
| 500 | DB 連線、SQL、交易例外 | `{"message": "資料庫存取失敗"}` |
| 500 | 其他未預期例外 | `{"message": "系統發生錯誤"}` |

經殼 jar 轉發時，後端的狀態碼與本文原樣回給瀏覽器（3xx 不帶 `Location`）；殼 jar 轉發器自己只產生三種錯誤：請求路徑或格式不合法回 400 `{"message":"請求格式錯誤"}`、本文超過殼 jar 上限（非 multipart 1 MB、multipart 51 MB）回 413 `{"message":"請求內容過大"}`、後端位址未設定或連不上回 502 `{"message":"後端服務呼叫失敗"}`（見「前端架構」的轉發器規格）；另有殼 jar 的 Spring／Tomcat 層自己回的錯誤：不支援的 method 405、少數畸形路徑 Tomcat 先回 400，這些本文都不是上述 JSON。前端一律以狀態碼判斷、不依賴錯誤本文結構。

### 環境設定

各 jar 的設定檔是**真檔不進 git**（被 `.gitignore` 排除），進 git 的是同名 `.properties.example`；新增鍵時同步寫進 `.example`（只寫鍵與說明，不寫真實位址或帳密）。實際怎麼建立與填值見 `SETUP.md`。DB 的 jdbcUrl 與帳密不在本系統任何檔案或環境變數中，由連線資訊 API 依別名提供。

後端 `infra_manager_java/src/main/resources/`：

| 檔案 | 鍵 | 用途 |
|---|---|---|
| `application.properties` | `spring.application.name` | 專案名（`infra_manager_java`） |
| | `server.port` | 3202 |
| | `spring.servlet.multipart.resolve-lazily` | `true`：multipart 延後到端點取 `MultipartFile` 時才解析 |
| | `im.attach.root` | 附件實體檔根目錄（絕對路徑，每台部署機各自設定）；留空時附件下載端點回 500 並記 log |
| | `spring.mail.host` | SMTP 主機；留空時 Boot 不建立寄信元件，worker 只記一次警告、不寄（信仍寫進 outbox） |
| | `spring.mail.port` | SMTP port（範例值 25） |
| | `spring.mail.properties.mail.smtp.ssl.trust` | `*`：不驗證 SMTP 憑證（沿用舊系統；port 25 明文時不起作用） |
| | `spring.mail.properties.mail.smtp.sendpartial` | `true`：部分收件人無效仍寄給其他人，outbox 記 SENT 並備註 |
| | `spring.mail.properties.mail.smtp.connectiontimeout`、`.timeout`、`.writetimeout` | 連線／讀取／寫入逾時（毫秒，範例值各 15000） |
| | `im.mail.enabled` | `true` 才啟動背景寄信排程（預設 `false`；關閉時信只寫進 outbox） |
| | `im.mail.from` | 寄件人地址；留空時視同 SMTP 未設定 |
| | `im.mail.site-url` | 信內「檢視申請單」連結的網址前綴（殼 jar 對外位址含 context path，不含結尾斜線）；留空時信內不放連結 |
| | `im.mail.site-name` | 信件頁尾顯示的系統名稱；留空用「機房設備異動申請系統」 |
| | `im.mail.override-to` | 有值時所有信改寄到此地址、主旨加「[測試改寄]」（驗收用）；正式環境留空 |
| | `im.mail.batch-size`、`im.mail.max-try`、`im.mail.retry-backoff-minutes` | 每輪最多寄幾封（預設 20）、重試上限（預設 5，達上限標 FAILED）、退避分鐘數（預設 1，乘以已試次數） |
| | `im.mail.poll-delay-ms`、`im.mail.initial-delay-ms` | 輪詢間隔與啟動後首次延遲（毫秒，預設 30000／15000） |
| `config/database.properties` | `db.connect.itflow` | 本系統資料庫在連線資訊 API 的別名（必填；空白時啟動失敗） |
| | `db.schema.itflow` | `IM_*` 表所在的 schema 名（必填；如 `rd_user`，以 `ap_user` 連線時也填表所屬的 schema）；啟動時白名單驗證，未設或不合法即啟動失敗；DAO 以 `DbSchema.table()` 組成 `SCHEMA.表名` |
| `config/host.properties` | `rt-api.domain`、`db.connect.api.port`、`db.connect.api.path` | 連線資訊 API 的協定＋主機、port、路徑 |
| | `db.connect.api.domain.path` | 由前三個以 `${}` 組合，範本只讀這一個；四個鍵都要存在（值可空），全空時仍可啟動，第一次查 DB 才失敗 |

殼 jar `infra_manager_web/infra_manager_web/src/main/resources/`：

| 檔案 | 鍵 | 用途 |
|---|---|---|
| `application.properties` | `spring.application.name` | 專案名（`infra_manager_web`） |
| | `server.port` | 3201 |
| | `server.servlet.context-path` | `/infra_manager_web`（前端 `base` 與 axios `baseURL` 都以此為前綴） |
| `config/host.properties` | `backend.api.domain`、`backend.api.port`、`backend.api.path` | 後端 API 的協定＋主機、port、根路徑（本系統為 `/api`） |
| | `backend.api.domain.path` | 由前三個以 `${}` 組合，殼 jar 的轉發器只讀這一個；四個鍵都要存在（值可空） |

secret 一律放環境變數，不寫進 repo 內任何檔案：

| 環境變數 | 用途 |
|---|---|
| `IM_SMTP_PASSWORD` | SMTP 帳號認證的密碼。目前公司 SMTP 無帳號認證、不需設定；需要認證時 `spring.mail.username` 填帳號、`spring.mail.password=${IM_SMTP_PASSWORD:}` 由此變數讀密碼（SMTP 連線值本身放 `application.properties`，2026-10-08 裁示 ①B） |
| `IM_RACK_API_KEY` | Impact 機櫃盤點 API 金鑰 |
| AI 金鑰 | 存放方式待定，見 BACKLOG.md 第 18 項 |

本系統分批施工中，進度見 `BACKLOG.md`。
