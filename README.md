# EVOPS 基础工作区

这是 springboot 的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

## F1：户外广告点位与上刊底账（f1-ledger-1）

围绕"点位档案 → 按周档期 → 上刊单"立底账，本版跑通挂牌、上刊、落架、翻查；结算不在本轮范围。

业务口径：

- **点位档案**：编号、尺寸、朝向、商圈缺一不可；点位编号唯一，重复存不进去。
- **档期按周排**：ISO 周（周一至周日），同一点位同一周只能挂一张画面。
- **上刊单**：画面挂在点位上，状态 `HUNG 挂起 / TAKEN_DOWN 落架 / OFFSET 已对冲`；单号 `PO+序列` 全局唯一。
- **改错不抹记录**：原单置"已对冲"并释放档期，另补一张对冲单挂同一根单（原单、对冲单可按根链翻查）。
- **随单留底**：每步动作写事件留底——经手人、发生日、事件时刻、单据快照；支持挂起中单据的版本选择与回退（回退也留一条事件，关闭单拒绝回退）。
- **关闭即冻结**：落架/对冲后的单据不能静默修改、重复落架或回退。
- **跨月归属**：归属期间 `biz_period` 按发生日落库固化，不随查询日漂移。
- **外部回执**：挂回原单据；回执指向对冲单时自动回溯到根单。
- **月度档期周历**：`/api/ledger/calendar?period=yyyy-MM` 一眼看哪块屏哪周空着。

账号：演示账号 `demo / demo`，骨架账号 `bootstrap / bootstrap`（均为财务/系统视角，看全部客户、可审批）；F5 销售账号 `sales1 / sales1`、`sales2 / sales2`（只见名下客户），财务账号 `finance1 / finance1`；F6 巡查账号 `inspector1 / inspector1`（现场上报）、维护商账号 `vendor1 / vendor1`（回传处理结果），派单与复勘用 `demo / demo`；F8 安装师傅账号 `installer1 / installer1`（到场打卡、完工回传），安装派单匹配/改期用 `demo / demo`；F9 代理账号 `agent1 / agent1`、`agent2 / agent2`（只能查自己账单），代理建档挂协议/调比例/出结算用 `demo / demo` 或 `finance1 / finance1`；F10 合规岗账号 `compliance1 / compliance1`（维护受限行业名单、批量冻结与处置），名单维护与冻结处置也可用 `demo / demo`。

## F2：素材流水与多规格转档（f2-asset-transcode-1）

美工传一张原图，横竖两版常规规格自动出，不用再手工导七八个版本。

业务口径：

- **素材档案按文件指纹认人**：原图算 SHA-256 指纹，`fingerprint_sha256` 唯一；同一张图重复上传（哪怕改了文件名）只累加 `upload_count`，**不另开档、不重跑转档**。
- **横竖两版常规规格自动出**：横版 `16:9`、竖版 `9:16`，均居中裁切不变形，**长边压进限定像素**（默认 1280px，出标准件 1280×720 / 720×1280）。
- **体积超标再压一轮质量**：产物先按首轮质量（0.85）出 JPEG，超过体积上限（默认 300KB）就每轮降一档质量（步长 0.15，下限 0.50），取体积最小一轮；压到下限仍超标也交差，任务不算失败。规格/长边/上限/质量档均在 `application.yml` 的 `asset.*` 配置。
- **结果记进素材档案、标清用途**：每个产物一行，`usage` 记规格标识（信息流横版 `feed_landscape` / 信息流竖版 `feed_portrait`），并记尺寸、长边、体积、质量轮次、最终质量。
- **多规格转档版本**：上传首轮记 v1（来源 UPLOAD）；手动再跑不抹历史，另起一版（来源 RETRY），各版本产物独立可翻。
- **多规格转档状态**：任务 `PENDING 待处理 / PROCESSING 转档中 / SUCCESS 成功 / FAILED 失败`，状态同时挂在版本和任务上；**失败原因落库**（如"图片格式不支持或文件已损坏"），前端轮询即可见，美工不干等；失败可一键重新转档。
- 上传接口立即返回任务号，转档在后台线程池异步执行（事务提交后触发，避免后台读不到未提交任务）。
- 原图与产物落盘 `asset.storage-root`（默认 `./data/assets`，原图按指纹存、产物按 素材/版本/规格 存），库里存相对路径。

## F3：点位经纬度建档与半径捞点（f3-site-geo-1）

拓展部圈商圈不再只靠感觉：点位档案补上经纬度，商圈落成圆心+半径的圈界，给个坐标和公里数就能把圈里在营点位全捞出来，跑盘清单对得上。

业务口径：

- **坐标十进制度落库，小数位不丢**：纬度 `DECIMAL(10,7)`、经度 `DECIMAL(11,7)`，最多 7 位小数；超界（纬度 ±90、经度 ±180）或超位直接拒收，**不静默截断**。
- **经纬度建档版本**：首次建档案记 v1（来源 INITIAL）；坐标录错了纠偏重录，不抹历史，另起一版（来源 CORRECT），旧版置 SUPERSEDED 可回翻；点位档案上的坐标始终指向当前生效版本。
- **经纬度建档状态**：点位 `UNFILED 未建档 / FILED 已建档`，未建档的点位不进半径检索结果。
- **商圈圈界**：每个商圈一条圆心坐标+半径（公里），同名不能重复圈；圈错了按商圈名**重圈**（当前态覆盖）。点位归属不受影响——**归属始终按录入时定的主商圈走**。
- **半径捞点**：给个坐标、给个公里数（0~100，最多 3 位小数），圈里**在营且已建档**的点位全列出，Haversine 球面距离，**按距离升序**（并列按点位编号稳住）。
- **商圈重叠带标出来**：点位坐标同时落进 ≥2 个商圈圈界的，结果里标「重叠带」并列出实际覆盖的商圈名单；标记只作提示，不改归属。
- **整页能导出**：检索结果整页导出 CSV（带 BOM，Excel 打开不乱码），列含主商圈归属、距离、十进制度坐标、重叠带标记。

## F4：年度刊例与统一计价（f4-rate-card-1）

两家店报价差几十块、客户拿着单子来砍价——刊例收进 evops-base 做统一入口：点位挂年度刊例，按购买档期长度落阶梯折扣；报价只按提交当天的刊例算，历史报价留当时单价快照，调价不许回写旧单。计价逻辑全在 `PricingEngine` 一处，不散在各接口。

业务口径：

- **年度刊例**：一个自然年一份（年份唯一），状态 `DRAFT 编制中 / EFFECTIVE 生效中 / CLOSED 已封存`；封存后不能再起新版、不能提交新报价（历史报价照翻）。
- **年度刊例版本**：建档自动带 v1 草稿；**草稿可反复整表配置，发布后冻结**，档位与点位价一个字不能改。调价只能「另起新版本」（从上一版拷贝档位与点位价做起点）再发布；新版发布时旧生效版自动置 `SUPERSEDED 已被新版取代`，历史永久可翻。版本状态 `DRAFT / EFFECTIVE / SUPERSEDED / REVOKED 已作废`；生效版录错了可作废（留底不删），作废后该年度重新发布版本才能报价。
- **点位挂年度刊例**：版本内每个点位一条挂牌**周单价（未税，折前）**；点位必须已挂牌、不能重复配。没挂进生效刊例的点位不能报价。
- **按购买档期长度落阶梯折扣**：档位按周数配区间 `[minWeeks, maxWeeks]`（闭区间，末档 `maxWeeks` 留空表示「及以上」），档位比例 = 刊例价 × 比例（1 不打折、0.9 九折）。配置即校验：首档从 1 周起、首尾相接**不许断档/重叠**、开口档只能在最后、比例随周数递减（买越长越便宜）。
- **报价按提交当天的刊例算**：按提交日所在自然年取「生效中」刊例的当前版本；提交日留空取今天，**不许按未来日期报价**；刊例没发布/已封存/点位没挂价都直接拒收。
- **历史报价留单价快照，不回写旧单**：提交当时把刊例年份、刊例编号、版本号、税率、点位挂牌价、落档档位、档位比例、折后单价、未税/税额/含税金额，连同点位编号/尺寸/朝向/商圈全部复制进报价单与报价行；之后刊例调价、换版、点位改档都与旧单无关。系统**只提供提交与查询，没有改价接口**，从接口层杜绝回写。报价单号 `QT+序列` 全局唯一。
- **含税未税并列展示，默认含税**：折后未税单价 = 挂牌价 × 档位比例（四舍五入到分）；行金额 = 折后单价 × 周数；税额 = 未税 × 版本税率（默认 6%，可在 `rate-card.default-tax-rate` 配）；含税 = 未税 + 税额。列表、详情、试算都是含税/未税/税额三列并列，含税列加粗为默认口径。
- **计价逻辑收到一处**：所有金额只由 `PricingEngine.price(提交日, 报价行)` 算出，报价提交与报价试算共用，接口层不算一分钱。
- **财务随查随拉的阶梯对照表**：`GET /api/rate-cards/years/{year}` 拉年度当前版本的档位+点位价全量；`.../tiers/export` 导出 CSV（带 BOM，含年份、版本、状态、税率、周数区间、档位比例、中文折扣）。

## F5：客户授信与回款核销（f5-credit-1）

老客户先上刊后结钱是行规，但额度和回款不记销售小本本——全部落在 evops-base，授信与回款凭证串成一条账。

业务口径：

- **客户建档给信用额度**：客户名全局唯一、与报价单上的客户名精确对应；建档即记 v1 直接生效（前值 0），归属销售决定数据隔离边界。
- **上刊凭报价单占用、撤刊释放**：上刊单填已提交报价单号 `quoteNo` 即为授信单——客户与**含税占用额从报价单快照取**（金额仍只由 `PricingEngine` 一处算出）；报价必须是单行（单点单周，与上刊单一致，多行请拆单），**一张报价单最多背一张上刊单**。不填报价单号是普通单，不占额度，流程与 F1 完全一致。
- **余额不足拦在提交口**：占用走数据库原子条件更新（在档 且 占用+本次 ≤ 额度），并发超额也由它兜底；冻结户不能新占用。占用失败单据与档期整体回滚，不留半截。
- **撤刊即释放，转应收**：落架撤刊立即把未核销部分还回可用额度，占用行置 `RELEASED` 转应收继续跟踪；**对冲属更正，释放额度但不转应收**（`REVERSED`，不能再核销）；已有回款核销的单禁止直接对冲。
- **回款登记后按单核销，部分回款分次核**：先登记回款凭证（实收额），再按上刊单核销；一笔回款可分次核多张单、一张单也可被多笔回款分次核销。回款已核不超实收、单据已核不超单据额，都走条件更新防核穿。**挂起期间回款核销等额实时释放占用**；撤刊转应收后的核销只结应收、不动额度。
- **额度动一动走审批，前后值都留着**：建档之后调额 `ADJUST`、冻结 `FREEZE`、解冻 `UNFREEZE` 一律先进 `PENDING`，财务审批后才 `EFFECTIVE`（客户落新值、上一生效版置 `SUPERSEDED`），驳回置 `REJECTED` 客户不变；版本里前额度/后额度、前状态/后状态、申请人/审批人全留。申请人不能审批自己（职责分离）；下调额度不得低于当前在额占用。
- **月底财务拉每户占用明细**：`GET /api/credit/reports/occupation?period=yyyy-MM` 按固化归属期间拉每户每单，`.../export` 导出 CSV（带 BOM，含占用/已核销/未核销、占用与核销状态）。
- **销售只能看自己名下客户**：`Viewer` 统一隔离——销售（sales 角色）查询/申请/上刊/回款都限名下客户，财务/系统（finance、system 角色）看全部并可审批。

信用额度模块：客户档案（额度、在额占用、可用额度）；信用额度版本（建档/调额/冻结/解冻，前后值、申请审批留痕）；信用额度状态（`ACTIVE 在档 / FROZEN 冻结`）。

## F6：异常上报与整改销项（f6-rectify-1）

上周三大风三块牌面翘边，巡查员电话打了一圈、事后查不到谁报的、怎么处理的——异常处置全链路落进 evops-base：巡查员现场就能报，系统派整改单给维护商，处理完回传，复勘通过才销项；拖过期限自动逐级往上抄送，照片在销项前一张都不许删。

业务口径：

- **巡查现场就能提交异常**：点位、异常类型（`PANEL_LIFT 牌面翘边 / PANEL_DAMAGE 牌面破损 / LIGHT_FAULT 亮灯故障 / STRUCTURE_RISK 结构隐患 / OTHER 其他`）必填，点位必须已挂牌；异常说明可填，**照片可传可不传、可多张**。上报即生成整改单，单号 `RC+序列` 全局唯一，谁报的、什么时刻、在哪个点位，一翻就有。
- **系统派单给整改期限**：整改单派给维护商（承派商 + 联系方式），派单时刻落 `dispatch_time`，按配置 `rectify.deadline-hours`（默认 48 小时）算 `deadline`，可临时覆盖。
- **维护商处理完回传**：处理结果（怎么处理的、换了什么件）必填，整改后照片可传可不传；回传后单据变「已回传待复勘」。
- **复勘通过才销项，不通过退回重处理**：驳回必须填原因，单据退回「已派单整改中」并**重新给整改期限**（返工也计时，避免驳回单绕过超期抄送）；通过才 `CLOSED 已销项`。
- **异常上报状态**：`REPORTED 已上报待派单 / DISPATCHED 已派单整改中 / REPAIRED 已回传待复勘 / CLOSED 已销项`；**关闭即冻结**，销项后不能再派单、回传、复勘、补照片。
- **异常上报版本**：每流转一次 `version_no +1`，每步写只追加事件（经手人、时刻、详情、**事件后单据快照 JSON**）：上报/派单/回传/复勘通过/复勘驳回/超期抄送/关闭后删照片，全程可翻。
- **拖过期限自动往上抄送**：定时任务（默认每 10 分钟，启动 30 秒先扫一轮）扫 `DISPATCHED 且当前时刻 > deadline` 的单，按 `rectify.escalation` 阶梯逐级抄送（默认：期限一过→维护主管，超期 48h→运营经理，超期 120h→分管总监）；**每级只抄一次**，`(整改单, 抄送级别)` 唯一约束 + 落库级别双重幂等，重复扫描、多节点都不重发，只往更高级别走；回传后立即退出抄送。
- **照片在单子关闭前不许删，留证要完整**：服务层与接口双拦，未销项删除直接拒；销项关闭后允许清理，但**删一条写一条 `PHOTO_DELETE` 事件**（谁删的、删的哪阶段哪张）。照片落盘 `rectify.storage-root`（默认 `./data/rectify-photos`，按 单号/阶段/时刻-指纹 存），SHA-256 指纹随档（不去重，每次上传都是独立证据），库里存相对路径。
- **翻点位档案看得到历史异常**：`GET /api/rectify/sites/{siteNo}/history` 列该点位全部整改单（含已销项），时间倒序；整改单也支持按状态/点位/类型/关键字翻查，详情带事件版本、照片、抄送记录全量档案。

异常上报模块：异常整改单（点位、类型、上报人、维护商、期限、复勘）；异常上报版本（上报/派单/回传/复勘/抄送逐事件 + 快照留底）；异常上报状态（`REPORTED / DISPATCHED / REPAIRED / CLOSED`）。

## F7：屏体播放列表排片（f7-playlist-1）

排片不再散在各屏的小本本里——按日期时段给屏编排播放单、素材统一从素材库里挑，排片锁版的版本链收进 evops-base：改一次留一版，周五下班前下周排片全部锁版，到点系统自动切，切不过去一定告警。

业务口径：

- **一块屏一条播放列表**：播放列表挂在已挂牌点位（屏）上，一屏一条；单号 `PL+序列` 全局唯一。编排按 ISO 自然周（周一至周日）组织，一周一串版本。
- **按日期时段编排，素材从库里挑**：一周编排整表替换式提交（含若干自然日时段，每段一个播放单）；播放单里的素材必须是素材库里的在用素材（按 `AS…` 编号认领，存编号/名称快照），库里没有或已归档直接拒收。
- **跨零点时段按自然日切了再比**：结束时间不晚于开始时间即视为跨零点（也可显式声明），编排进来先切成「当日到 24:00」「次日从 00:00 起」两行；周日跨到下周一凌晨的溢出段归本周版本，到点切换时周一凌晨仍按上周版本播。
- **同屏同时段只许一条有效排片**：切到同一自然日后按半开区间 `[start, end)` 比较，重叠保存就报错（首尾相接不算撞）；同一自然日上还要和相邻周版本的跨零点溢出段再比一次，撞了同样拒。
- **编排改动留版本**：保存/整段复制/整段顺延/锁版/锁后改单/审批，每次都另起一版（版本链只追加，旧版永久可翻），每版带全量快照。版本状态 `DRAFT 草稿 / LOCKED 已锁版 / PENDING 待审批 / REJECTED 已驳回 / SUPERSEDED 已被新版取代`。
- **整段复制、整段顺延**：来源周整段复制到目标周（默认下周）生成新草稿；某周全部时段整体平移 N 分钟（正延后、负提前），跨零点、跨自然日按切分后的段重算，平移推出周界直接拒。
- **周五下班前下周排片全部锁版**：定时任务（默认每 10 分钟扫，周五下班时刻默认 18:00 可在 `playlist.friday-deadline` 配）到点把每块屏下周没锁的草稿全部锁版（`AUTO_LOCK`）；下周完全没排落 `WEEK_EMPTY` 告警、锁版改单还在待审批落 `PENDING_CHANGE` 告警、自动锁版落不下落 `LOCK_FAILED`，都不静默。也可人工提前锁版（`MANUAL`）。
- **锁了之后再动就走审批**：周锁版后该周不能直接保存/复制/顺延，只能提交「锁版改单申请」（必填原因，版本进 `PENDING`，旧锁版继续生效）；财务/系统审批通过后改单版本锁版生效、原锁版版本置 `SUPERSEDED`；驳回置 `REJECTED` 留底、原锁版不变。**申请人不能审批自己**（职责分离），销售角色只能申请。
- **到点自动切换，失败必告警**：定时任务（默认每 30 秒扫）按各屏当前锁版版本与当前自然日/时刻推进在播指针（半开区间命中，重复扫描幂等）；播放单素材在库里找不到时不推进指针，落 `MISSING_ASSET` + `SWITCH_FAILED` 告警；锁版周内段间空档（含周日跨零点段结束后、本周首段开始前的跨周边界空档）落 `GAP` 告警。告警按「屏+自然日+时段+类型」幂等去重，重复扫描不重发；支持人工确认解除。
- **翻查**：播放列表翻查、版本链、某周编排（按自然日切好的时段+播放单）、全量档案（版本/周锁版/事件/告警）、待审批改单、告警列表一应俱全。

播放列表模块：播放列表（屏、在播指针、周锁版）；播放列表版本（保存/复制/顺延/锁版/改单审批逐版 + 快照留底）；播放列表状态（`DRAFT / LOCKED / PENDING / REJECTED / SUPERSEDED`，在播切换以 live 指针 + 告警为准）。

## F8：安装派单与资质匹配（f8-install-dispatch-1）

业务方不用再抱着调度电话挨个问安装队——上刊单一确认，安装派单自动在 evops-base 生成，按资质和档期匹配安装队：高空证过期的接不了高空点位，一个队同一时段只排一个点位；师傅到场拍照打卡，完工回传回执，上刊单跟着派单走状态；改期通知到队；月底按队出完工量和准时率，结算认这个数。

业务口径：

- **点位分普通/高空**：点位档案补作业性质 `NORMAL 普通 / HIGH_ALTITUDE 高空`（建档可填，默认普通）。高空点位是资质硬门槛。
- **安装队与资质建档**：安装队队名全局唯一，状态 `ACTIVE 在册 / DISABLED 停用`；资质一证一行（`WORK_AT_HEIGHT 高空作业证 / ELECTRICIAN 电工证 / SAFETY_OTHER 其他`），证书编号全局唯一，记发证日/到期日，资质状态 `VALID 在册有效 / EXPIRED 已过期 / REVOKED 已吊销`。**到期当日仍有效，次日起过期**（匹配按计划作业日实时比 `expiry_date`，不靠定时改状态）。
- **上刊确认自动生成派单**：上刊单（`POST /api/orders`）确认后自动生成一张安装派单，单号 `ID+序列` 全局唯一，回写上刊单 `installDispatchId/installStatus`；一张上刊单最多一张派单（唯一约束兜底，重复触发幂等）。派单带点位作业性质、画面、默认档期（上刊周周一 09:00 起、默认 4 小时，可在 `install.*` 配）。
- **按资质 + 档期匹配安装队**：自动选「在册、资质合格、该时段空闲」的第一个队派成（`ASSIGNED`）。高空点位额外要求一张状态 VALID 且到期日不早于计划作业日的高空作业证，**高空证过期/吊销/没证的队一律匹配不上**；没有合格空闲队时派单落 `PENDING 待匹配`，不阻断上刊，由定时补扫（默认每 10 分钟）或人工补匹配继续。人工补匹配也强制过资质与档期，不合格直接拒。
- **一个队同一时段只排一个点位**：同一安装队的计划时段按半开区间 `[planStart, planEnd)` 比较，重叠即判忙（首尾相接不算撞），只对未取消派单比较；匹配时对候选队加行级悲观锁串行化，并发两张单不会同时排给一个队。
- **师傅到场拍照打卡**：必须上传现场照片才算到场（无照片拒收），状态进 `ARRIVED`。
- **完工回传回执，上刊单跟着走**：回执内容（安装结果/件料）必填，完工照片可传可不传；按完工时刻对计划结束判**准时/延误**（晚于计划结束即延误），落一单一回执；完工回写上刊单安装状态与完工时刻。状态 `PENDING 待匹配 / ASSIGNED 已派单 / ARRIVED 已到场打卡 / COMPLETED 已完工 / CANCELLED 已取消`，完工即冻结，不能重复打卡/完工。
- **改期要通知到队**：仅已派单未到场可改期，新档期仍过资质与档期校验；每次改期给该队留一条通知（新旧档期、原因、第几次），派单 `rescheduleCount+1`。
- **上刊对冲联动取消**：上刊单对冲更正时，其未完工派单联动取消并释放队档期（已完工的保留——安装已实际发生，只留事件不阻断对冲）；档期释放后同周新单可再派给该队。
- **资质匹配版本**：每流转一次 `version_no+1`，逐事件只追加留底（自动生成/匹配/改期/通知/到场/完工/取消），每条带事件后派单快照 JSON，全程可翻。
- **月底按队结算**：`GET /api/install/reports/team-month?period=yyyy-MM` 按完工时刻所在月出每队完工量、准时完工、延误完工、**准时率（准时/完工量）**、在途未完工；`.../export` 导出 CSV（带 BOM）。**结算就认这个数**。
- **翻查**：派单翻查（状态/队/点位/单号）、全量档案（事件版本+照片+回执+改期通知）、按上刊单号查派单、照片下载齐全。

资质匹配模块：安装派单（来源上刊单、点位作业性质、队与档期、到场/完工、准时）；资质匹配版本（自动生成/匹配/改期/到场/完工/取消逐事件 + 快照留底）；资质匹配状态（`PENDING / ASSIGNED / ARRIVED / COMPLETED / CANCELLED`，资质门槛以高空作业证 `VALID 且到期日不早于计划作业日` 为准）。

## F9：代理协议与结算快照（f9-agent-settlement-1）

月底财务拿计算器给代理算分账，一位一位按、按到手抖还出错——规则写进 evops-base：点位挂代理协议，比例与有效期定清楚；结算期一到按实际上刊流水给各方算钱，四舍五入到分，各方合计必须跟总额对平，差一分都标出来。

业务口径：

- **代理建档绑登录账号**：代理名全局唯一，绑定一个登录账号（`login_username` 全局唯一）作为「只能查自己账单」的隔离边界；状态 `ACTIVE 在册 / DISABLED 停用`（停用不参与新结算，历史账单照翻）。代理编号 `AG+序列`。
- **点位挂代理协议**：一个代理在一个点位一份协议（同一代理同一点位重复挂存不进去），协议号 `AA+序列`；分账比例（0~1，四位小数）与起始生效结算期（自然月 `yyyy-MM`）在挂协议时定清楚；点位必须在营。协议状态 `ACTIVE 生效中 / TERMINATED 已终止`。
- **代理协议版本**：挂协议即 v1（`INITIAL`，起始期默认当期、可指定未来期，不许追溯过去期）；**比例和有效期不在主档上改，全部落在只追加的版本链里**，历史永久可翻。版本状态 `PENDING 待生效 / EFFECTIVE 生效中 / SUPERSEDED 已被新版取代`。
- **中途调整从下期生效，历史期不重算**：调比例（`ADJUST`）或终止（`TERMINATE`）一律另起版本，起始期最早只能是「下一结算期」（留空即下期），不能当期生效、不能追溯；同一协议同时只准有一个待生效版本，同一起始期只许一版。出结算时先把到期的待生效版转生效（上一版置 `SUPERSEDED`），当期即按新比例算。某结算期用哪版 = `effective_from_period <= 该期` 的最大版本号；命中终止版即该期起不再分账。
- **按实际已上刊流水结算**：结算期一到，按该代理名下点位当期【实际落架】的授信上刊单（单点单周、金额取报价含税总额快照）归集流水，归属期按**落架月**固化；挂起未下架的单留待落架期结算，对冲更正单不形成流水。结算单号 `ST+序列`。
- **四舍五入到分、差一分都标出来**：每张流水独立算代理分账 `HALF_UP(流水×比例)` 与平台分账原始 `HALF_UP(流水×(1-比例))`，行尾差 = 流水−代理−平台原始（差一分也落 `line_rounding_diff`）。结算头汇总：`total_commission + total_platform = total_revenue` 分毫不差，独立四舍五入产生的尾差 `rounding_diff` 单独标出并由**平台方兜底吸收**（`total_platform = 平台原始 + 尾差`），`balanced=1` 才是对平。
- **结算即冻结，历史不重算**：结算时把协议版本号、分账比例全部复制进结算行（快照），之后调比例、换版都与历史账单无关；`(代理, 结算期)` 唯一，重复出结算幂等跳过，已结算期一个字不重算。结算头另存全量快照 JSON。
- **代理只能查自己的账单**：`AgentViewer` 统一隔离——代理（agent 角色）只能看/导出 `login_username` 等于自己的代理账单，别家的看不见；挂协议、调比例、终止、出结算只许财务/系统（finance、system 角色）。
- **月底拉账导出**：`GET /api/agent-settlements?period=yyyy-MM` 翻账单（代理只见自己），`.../export` 导出按期汇总 CSV（含流水、代理/平台分账、**尾差列**、是否对平），`.../{settlementNo}/export` 导出单张逐笔流水分账行 CSV（带 BOM，Excel 打开不乱码）。

代理协议模块：代理协议（点位挂代理、状态）；代理协议版本（建档/调比例/终止逐版 + 比例与有效期，下期生效、历史不重算）；代理协议状态（`ACTIVE / TERMINATED`，版本 `PENDING / EFFECTIVE / SUPERSEDED`）。

## F10：受限行业类目名单（f10-restricted-category-1）

漏接一单受限行业、全公司陪着做检查——这口子在系统里堵死：客户建档、上刊提交两个口都校验行业类目，命中受限名单当场拦，提示里带上依据；名单合规岗维护、调整即时生效、每次调整留操作记录；已经建档的客户后来进了名单的，在途单能批量冻结。页面和接口两头都拦，不留绕过去的道。

业务口径：

- **两个口当场拦，提示带依据**：客户建档必填行业类目，命中当前生效名单即拒收；上刊提交同样校验——普通单必填行业类目（快照落单），授信单取客户档案类目（占用额度之前校验，命中则单据、档期、占用整体回滚）。拦截提示含类目、受限依据、名单版本号。报价只是报价不拦，上刊必拦。
- **行业类目落库固化、建档后不可改**：客户与上刊单上的行业类目都随档固化（授信单取客户类目快照），不提供修改接口；历史建档客户类目为空的，由合规岗/系统补录（仅空可补），补录同步回填该户在途单快照，补录成受限类目会明确提示去冻结。
- **名单合规岗维护，调整即时生效**：名单全公司一份；调整走「另起新版（从生效版拷贝条目）→ 整表配置（类目+依据）→ 发布」，发布即时生效（拦截口径实时读生效版，无缓存），旧生效版自动置 `SUPERSEDED`；生效版不可作废，不留无名单空窗。维护与处置只认合规岗/系统（`compliance` / `system` 角色）。
- **每次调整留操作记录**：每发一次布就是一个版本——经手人、发布时刻、调整说明、全量条目（类目+依据）随版本冻结，版本链即操作记录，永久可翻。
- **老客户后进名单，在途单批量冻结**：发布后按新增类目（或任意指定类目集、默认生效版全量）命中「挂起中且合规状态正常」的在途单（按单上类目快照匹配，普通单/授信单通吃），一键批量冻结：原子条件更新置 `FROZEN`（重复触发、并发都幂等），逐单写 `COMPLIANCE_FREEZE` 事件，一批一记录（批次：类目集、命中客户数、单据数、经手人；逐单记录行：客户与类目快照、处置状态）。曾被同类目解冻特批的单不再重复冻结。
- **冻结中全拦，处置只认合规**：冻结中的单落架/对冲/留痕更正/版本回退一律拒（提示联系合规岗）；处置出口两个——**解冻**（特批放行，单据恢复 `NORMAL`，事件与记录留底）、**强制落架**（走正常落架主流程释放档期与授信占用，事件注明合规强制，记录置 `FORCE_CLOSED`）。
- **页面接口两头都拦**：前端客户建档/上刊表单带行业类目字段，页面缓存生效名单做命中即时提示与提交前拦截；服务端两个口同样硬校验，直接调接口绕不过去。

类目名单模块：类目名单主档（全公司一份，当前生效版本指针）+ 名单条目（行业类目+受限依据，随版本冻结）；类目名单版本（调整另起新版、发布即时生效、旧版自动取代，经手人/发布时刻/调整说明留底，版本链即操作记录）；类目名单状态（`DRAFT 编制中 / EFFECTIVE 生效中 / SUPERSEDED 已被新版取代`）。

## 接口一览（均在 /api 下，除 health/login 外需登录）

| 动作 | 接口 |
|---|---|
| 登录/登出/当前人 | `POST /api/login`、`POST /api/logout`、`GET /api/me` |
| 点位挂牌/翻查/详情 | `POST /api/sites`、`GET /api/sites`、`GET /api/sites/{id}` |
| 上刊 | `POST /api/orders` |
| 落架 / 对冲 / 留痕更正 / 版本回退 | `POST /api/orders/{orderNo}/take-down`、`/offset`、`/edit`、`/rollback` |
| 单据翻查/详情/事件留底/根单链 | `GET /api/orders`、`/api/orders/{orderNo}`、`/events`、`/chain` |
| 外部回执 | `POST /api/receipts`、`GET /api/receipts/order/{orderNo}` |
| 月度档期周历/点位档期流水/底账版本 | `GET /api/ledger/calendar`、`/api/ledger/sites/{siteNo}/timeline`、`/api/ledger/versions` |
| 原图上传（指纹去重，自动转档） | `POST /api/assets/upload`（multipart：file、operatorName、remark） |
| 素材翻查 / 详情 / 档案全量 | `GET /api/assets?keyword=&status=`、`/api/assets/{id}`、`/api/assets/{id}/detail` |
| 当前转档规格 | `GET /api/assets/specs` |
| 转档状态轮询 / 重新转档 | `GET /api/assets/jobs/{jobId}`、`POST /api/assets/{id}/retry` |
| 产物下载 / 原图下载 | `GET /api/assets/files/{fileId}/download`、`/api/assets/{id}/original` |
| 经纬度建档 / 建档版本翻查 | `POST /api/sites/{siteNo}/geo`、`GET /api/sites/{siteNo}/geo/versions` |
| 半径捞点 / 整页导出 CSV | `GET /api/sites/geo/search?lat=&lng=&radiusKm=`、`GET /api/sites/geo/search/export?...` |
| 商圈圈界：圈定 / 翻查 / 重圈 | `POST /api/districts`、`GET /api/districts`、`PUT /api/districts/{districtName}` |
| 年度刊例：建档 / 翻查 / 版本翻查 / 另起新版 / 封存 | `POST /api/rate-cards`、`GET /api/rate-cards?year=`、`GET /api/rate-cards/{id}/versions`、`POST /api/rate-cards/{id}/versions`、`POST /api/rate-cards/{id}/close` |
| 刊例版本：草稿配置 / 详情 / 发布 / 作废 | `PUT /api/rate-cards/versions/{versionId}`、`GET /api/rate-cards/versions/{versionId}`、`POST .../publish`、`POST .../revoke` |
| 财务阶梯对照表 / 导出 CSV | `GET /api/rate-cards/years/{year}`、`GET /api/rate-cards/years/{year}/tiers/export` |
| 报价：试算 / 提交 / 详情 / 翻查 | `POST /api/quotes/preview`、`POST /api/quotes`、`GET /api/quotes/{quoteNo}`、`GET /api/quotes?customerName=&rateYear=&from=&to=` |
| 客户建档授信 / 翻查 / 详情 / 额度版本 | `POST /api/credit/customers`、`GET /api/credit/customers?keyword=&status=`、`GET /api/credit/customers/{customerNo}`、`GET /api/credit/customers/{customerNo}/versions` |
| 额度变更：申请 / 待审批 / 通过 / 驳回 | `POST /api/credit/customers/{customerNo}/applications`、`GET /api/credit/limit-versions/pending`、`POST /api/credit/limit-versions/{id}/approve`、`POST .../reject` |
| 回款登记 / 翻查 / 详情 / 按单核销 | `POST /api/credit/payments`、`GET /api/credit/payments?customerNo=&from=&to=`、`GET /api/credit/payments/{paymentNo}`、`POST /api/credit/write-offs` |
| 占用应收明细 / 月末占用报表 / 导出 CSV | `GET /api/credit/occupations?customerNo=&period=&status=&settleStatus=`、`GET /api/credit/reports/occupation?period=yyyy-MM`、`GET /api/credit/reports/occupation/export?period=` |
| 异常类型字典 / 现场上报（multipart，照片可选） | `GET /api/rectify/types`、`POST /api/rectify/orders` |
| 整改单翻查 / 详情（事件+照片+抄送） | `GET /api/rectify/orders?status=&siteNo=&type=&keyword=`、`GET /api/rectify/orders/{rectifyNo}` |
| 派单 / 维护商回传（multipart，照片可选） | `POST /api/rectify/orders/{rectifyNo}/dispatch`、`POST /api/rectify/orders/{rectifyNo}/repair` |
| 复勘通过销项 / 驳回返工 | `POST /api/rectify/orders/{rectifyNo}/reinspect/pass`、`POST /api/rectify/orders/{rectifyNo}/reinspect/reject` |
| 补传照片 / 关闭后删照片（留事件） / 下载 | `POST /api/rectify/orders/{rectifyNo}/photos`、`POST /api/rectify/photos/{photoId}/delete`、`GET /api/rectify/photos/{photoId}/download` |
| 点位历史异常 / 立即扫描超期抄送 | `GET /api/rectify/sites/{siteNo}/history`、`POST /api/rectify/escalations/scan` |
| 播放列表：建档 / 翻查 / 全量档案 / 版本链 | `POST /api/playlists`、`GET /api/playlists`、`GET /api/playlists/{playlistNo}`、`GET /api/playlists/{playlistNo}/versions` |
| 周编排：查看 / 整表保存（留版本） | `GET /api/playlists/{playlistNo}/schedule?weekStart=`、`PUT /api/playlists/{playlistNo}/schedule` |
| 整段复制 / 整段顺延 / 人工锁版 | `POST /api/playlists/{playlistNo}/segments/copy`、`/segments/shift`、`POST /api/playlists/{playlistNo}/lock` |
| 锁后改单：申请 / 待审批 / 通过 / 驳回 | `POST /api/playlists/{playlistNo}/change-requests`、`GET /api/playlists/change-requests/pending`、`POST .../change-requests/{versionId}/approve`、`POST .../change-requests/{versionId}/reject` |
| 事件留底 / 告警翻查 / 解除告警 | `GET /api/playlists/{playlistNo}/events`、`GET /api/playlists/alerts?status=`、`POST /api/playlists/alerts/{alertId}/resolve` |
| 到点切换扫描 / 周五自动锁版扫描 | `POST /api/playlists/switch/scan`、`POST /api/playlists/locks/scan` |
| 安装队建档 / 停用 / 启用 / 翻查 / 详情 | `POST /api/install/teams`、`POST /api/install/teams/{teamNo}/disable`、`/enable`、`GET /api/install/teams`、`GET /api/install/teams/{teamNo}` |
| 资质登记 / 队资质翻查 / 吊销 | `POST /api/install/certs`、`GET /api/install/teams/{teamNo}/certs`、`POST /api/install/certs/{certId}/revoke` |
| 安装派单翻查 / 详情（事件+照片+回执+通知） / 按上刊单查派单 | `GET /api/install/orders`、`GET /api/install/orders/{dispatchNo}`、`GET /api/install/by-order/{orderNo}` |
| 人工补匹配 / 改期（通知到队） / 待匹配补扫 | `POST /api/install/orders/{dispatchNo}/match`、`/reschedule`、`POST /api/install/match/scan` |
| 到场拍照打卡（照片必传） / 完工回传回执（回执必填，照片可选） | `POST /api/install/orders/{dispatchNo}/arrive`、`POST /api/install/orders/{dispatchNo}/complete`（均 multipart） |
| 安装照片下载 | `GET /api/install/photos/{photoId}/download` |
| 月末按队完工量/准时率 / 导出 CSV | `GET /api/install/reports/team-month?period=yyyy-MM`、`GET /api/install/reports/team-month/export?period=` |
| 代理建档（绑登录账号）/ 停用 / 启用 / 翻查 / 详情 | `POST /api/agents`、`POST /api/agents/{agentNo}/disable`、`/enable`、`GET /api/agents`、`GET /api/agents/{agentNo}` |
| 点位挂协议 / 翻查 / 详情 / 版本链 | `POST /api/agents/agreements`、`GET /api/agents/agreements`、`GET /api/agents/agreements/{agreementNo}`、`GET /api/agents/agreements/{agreementNo}/versions` |
| 中途调比例（下期生效）/ 终止 | `POST /api/agents/agreements/{agreementNo}/adjust`、`POST /api/agents/agreements/{agreementNo}/terminate` |
| 出结算（按实际落架流水分账）/ 账单翻查 / 详情 | `POST /api/agent-settlements`、`GET /api/agent-settlements?period=&agentNo=`、`GET /api/agent-settlements/{settlementNo}` |
| 结算汇总导出 CSV / 单张逐笔分账行导出 CSV | `GET /api/agent-settlements/export?period=yyyy-MM`、`GET /api/agent-settlements/{settlementNo}/export` |
| 生效名单 / 版本链 / 版本详情 | `GET /api/compliance/list/effective`、`GET /api/compliance/list/versions`、`GET /api/compliance/list/versions/{versionId}` |
| 另起新版 / 整表配置草稿 / 发布生效（合规岗） | `POST /api/compliance/list/versions`、`PUT /api/compliance/list/versions/{versionId}/items`、`POST /api/compliance/list/versions/{versionId}/publish` |
| 冻结命中预览 / 批量冻结 / 批次翻查 / 批次明细（合规岗） | `GET /api/compliance/freeze/preview?categories=`、`POST /api/compliance/freeze`、`GET /api/compliance/freeze/batches`、`GET /api/compliance/freeze/batches/{batchNo}` |
| 冻结单解冻 / 强制落架（合规岗） | `POST /api/compliance/orders/{orderNo}/unfreeze`、`POST /api/compliance/orders/{orderNo}/force-take-down` |
| 历史客户行业类目补录（合规岗/系统） | `POST /api/credit/customers/{customerNo}/industry-category` |

授信上刊仍走上刊接口 `POST /api/orders`，请求体带 `quoteNo`（引用已提交报价单）即为授信单；落架/对冲接口不变，撤刊即释放额度。

演示界面：启动后浏览器打开根路径 `/`（周例会演示用，十五个页签覆盖全流程）。

## 启动

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；Spring Boot 会自动执行 `src/main/resources/schema.sql`。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。
4. 验证：`mvn test`（11 项底账口径测试 + 7 项素材转档测试 + 8 项坐标建档测试 + 13 项年度刊例计价测试 + 13 项客户授信回款测试 + 10 项异常上报整改测试 + 11 项屏体播放排片测试 + 12 项安装派单资质匹配测试 + 12 项代理协议结算测试 + 2 项受限行业名单测试，独立内存 H2）。

表结构交付副本：`docs/schema/evops.sql`（容器内根目录 `../../docs/schema` 不可写，交付副本置于工作区内），应与 `src/main/resources/schema.sql` 保持一致。
