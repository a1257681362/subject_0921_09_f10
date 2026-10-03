package com.evops.rectify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.asset.support.ImageFingerprints;
import com.evops.common.BusinessException;
import com.evops.ledger.entity.Site;
import com.evops.ledger.mapper.SiteMapper;
import com.evops.ledger.support.Operators;
import com.evops.rectify.RectifyEnums;
import com.evops.rectify.dto.RectifyDetailView;
import com.evops.rectify.dto.RectifyDispatchRequest;
import com.evops.rectify.dto.RectifyReinspectRequest;
import com.evops.rectify.dto.RectifyRepairRequest;
import com.evops.rectify.dto.RectifyReportRequest;
import com.evops.rectify.entity.RectifyCc;
import com.evops.rectify.entity.RectifyEvent;
import com.evops.rectify.entity.RectifyOrder;
import com.evops.rectify.entity.RectifyPhoto;
import com.evops.rectify.mapper.RectifyCcMapper;
import com.evops.rectify.mapper.RectifyEventMapper;
import com.evops.rectify.mapper.RectifyOrderMapper;
import com.evops.rectify.mapper.RectifyPhotoMapper;
import com.evops.rectify.support.RectifyPhotoStorage;
import com.evops.rectify.support.RectifyProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 异常上报整改主线：
 * 巡查现场上报（照片可传可不传）→ 系统派单给维护商并给整改期限 → 维护商处理完回传
 * → 复勘通过才销项（不通过退回重处理）；超期未回传定时逐级抄送。
 *
 * 口径：
 * - 每一步流转 version_no +1 并写只追加事件（带单据快照 JSON），关闭即冻结；
 * - 留证照片在单子关闭前一律不许删；关闭后删除也写事件留底；
 * - 抄送 (rectifyId, ccLevel) 唯一，重复扫描不重发，只往更高级别走。
 */
@Service
public class RectifyService {

    private final RectifyOrderMapper orderMapper;
    private final RectifyEventMapper eventMapper;
    private final RectifyPhotoMapper photoMapper;
    private final RectifyCcMapper ccMapper;
    private final SiteMapper siteMapper;
    private final RectifyPhotoStorage storage;
    private final RectifyProperties properties;
    private final ObjectMapper objectMapper;

    public RectifyService(RectifyOrderMapper orderMapper, RectifyEventMapper eventMapper,
                          RectifyPhotoMapper photoMapper, RectifyCcMapper ccMapper,
                          SiteMapper siteMapper, RectifyPhotoStorage storage,
                          RectifyProperties properties, ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.eventMapper = eventMapper;
        this.photoMapper = photoMapper;
        this.ccMapper = ccMapper;
        this.siteMapper = siteMapper;
        this.storage = storage;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // ---- 现场上报 ----

    /** 巡查员现场提交异常：点位、类型必填，照片可传可不传。上报即生成整改单（REPORTED）。 */
    @Transactional(rollbackFor = Exception.class)
    public RectifyOrder report(RectifyReportRequest request, MultipartFile[] photos) {
        String siteNo = request.getSiteNo().trim();
        Site site = siteMapper.selectOne(new LambdaQueryWrapper<Site>().eq(Site::getSiteNo, siteNo));
        if (site == null) {
            throw new BusinessException("点位档案里没有这个点位编号: " + siteNo + "，请先挂牌建档");
        }
        String type = normalizeType(request.getAbnormalType());
        String reporter = Operators.resolve(request.getReporterName());

        RectifyOrder order = new RectifyOrder();
        order.setRectifyNo(generateNo());
        order.setSiteId(site.getId());
        order.setSiteNo(site.getSiteNo());
        order.setBusinessDistrict(site.getBusinessDistrict());
        order.setAbnormalType(type);
        order.setAbnormalDesc(trimTo(request.getAbnormalDesc(), 1000));
        order.setStatus(RectifyEnums.STATUS_REPORTED);
        order.setVersionNo(1);
        order.setReporterName(reporter);
        order.setReportTime(LocalDateTime.now());
        order.setCcLevel(0);
        order.setOperatorName(reporter);
        order.setRemark(trimTo(request.getRemark(), 255));
        orderMapper.insert(order);

        savePhotos(order, RectifyEnums.PHASE_REPORT, photos, reporter);
        appendEvent(order, RectifyEnums.EVENT_REPORT, reporter,
                "现场上报异常：" + RectifyEnums.typeLabel(type));
        return order;
    }

    // ---- 派单 ----

    /** 系统把整改单派给维护商，落整改期限。 */
    @Transactional(rollbackFor = Exception.class)
    public RectifyOrder dispatch(String rectifyNo, RectifyDispatchRequest request) {
        RectifyOrder order = requireByNo(rectifyNo);
        requireStatus(order, RectifyEnums.STATUS_REPORTED, "派单");

        int hours = request.getDeadlineHours() != null ? request.getDeadlineHours() : properties.getDeadlineHours();
        if (hours <= 0) {
            throw new BusinessException("整改期限必须是正整数小时：" + hours);
        }
        String operator = Operators.resolve(request.getOperatorName());
        LocalDateTime now = LocalDateTime.now();

        order.setVendorName(request.getVendorName().trim());
        order.setVendorContact(trimTo(request.getVendorContact(), 128));
        order.setDispatchOperator(operator);
        order.setDispatchTime(now);
        order.setDeadline(now.plusHours(hours));
        order.setStatus(RectifyEnums.STATUS_DISPATCHED);
        order.setVersionNo(order.getVersionNo() + 1);
        order.setOperatorName(operator);
        if (request.getRemark() != null && !request.getRemark().trim().isEmpty()) {
            order.setRemark(trimTo(request.getRemark(), 255));
        }
        orderMapper.updateById(order);
        appendEvent(order, RectifyEnums.EVENT_DISPATCH, operator,
                "派单给维护商「" + order.getVendorName() + "」，整改期限 " + hours + " 小时（截止 " + order.getDeadline() + "）");
        return order;
    }

    // ---- 维护商回传 ----

    /** 维护商处理完回传：处理结果必填，整改后照片可传可不传；回传后待复勘。 */
    @Transactional(rollbackFor = Exception.class)
    public RectifyOrder repair(String rectifyNo, RectifyRepairRequest request, MultipartFile[] photos) {
        RectifyOrder order = requireByNo(rectifyNo);
        requireStatus(order, RectifyEnums.STATUS_DISPATCHED, "回传处理结果");
        if (request.getRepairResult() == null || request.getRepairResult().trim().isEmpty()) {
            throw new BusinessException("回传必须填写处理结果（怎么处理的、换了什么件）");
        }
        String operator = Operators.resolve(request.getOperatorName());
        LocalDateTime now = LocalDateTime.now();

        order.setRepairResult(trimTo(request.getRepairResult(), 1000));
        order.setRepairOperator(operator);
        order.setRepairTime(now);
        order.setStatus(RectifyEnums.STATUS_REPAIRED);
        order.setVersionNo(order.getVersionNo() + 1);
        order.setOperatorName(operator);
        orderMapper.updateById(order);

        savePhotos(order, RectifyEnums.PHASE_REPAIR, photos, operator);
        appendEvent(order, RectifyEnums.EVENT_REPAIR, operator, "维护商回传处理结果，待复勘");
        return order;
    }

    // ---- 复勘 ----

    /** 复勘通过：销项关闭，关闭即冻结。 */
    @Transactional(rollbackFor = Exception.class)
    public RectifyOrder reinspectPass(String rectifyNo, RectifyReinspectRequest request) {
        RectifyOrder order = requireByNo(rectifyNo);
        requireStatus(order, RectifyEnums.STATUS_REPAIRED, "复勘");
        String operator = Operators.resolve(request.getOperatorName());
        LocalDateTime now = LocalDateTime.now();

        order.setReinspectOperator(operator);
        order.setReinspectComment(trimTo(request.getComment(), 1000));
        order.setReinspectTime(now);
        order.setStatus(RectifyEnums.STATUS_CLOSED);
        order.setVersionNo(order.getVersionNo() + 1);
        order.setClosedTime(now);
        order.setOperatorName(operator);
        orderMapper.updateById(order);
        appendEvent(order, RectifyEnums.EVENT_REINSPECT_PASS, operator, "复勘通过，销项关闭");
        return order;
    }

    /** 复勘不通过：意见必填，退回维护商重处理（重新给整改进度期限，已抄送级别不重发）。 */
    @Transactional(rollbackFor = Exception.class)
    public RectifyOrder reinspectReject(String rectifyNo, RectifyReinspectRequest request) {
        RectifyOrder order = requireByNo(rectifyNo);
        requireStatus(order, RectifyEnums.STATUS_REPAIRED, "复勘驳回");
        if (request.getComment() == null || request.getComment().trim().isEmpty()) {
            throw new BusinessException("复勘驳回必须填写不通过原因，维护商才知道怎么返工");
        }
        String operator = Operators.resolve(request.getOperatorName());
        LocalDateTime now = LocalDateTime.now();

        order.setReinspectOperator(operator);
        order.setReinspectComment(trimTo(request.getComment(), 1000));
        order.setReinspectTime(now);
        order.setStatus(RectifyEnums.STATUS_DISPATCHED);
        order.setVersionNo(order.getVersionNo() + 1);
        order.setDeadline(now.plusHours(properties.getDeadlineHours()));
        order.setOperatorName(operator);
        orderMapper.updateById(order);
        appendEvent(order, RectifyEnums.EVENT_REINSPECT_REJECT, operator,
                "复勘不通过退回重处理：" + order.getReinspectComment() + "；返工期限截止 " + order.getDeadline());
        return order;
    }

    // ---- 超期逐级抄送 ----

    /**
     * 扫描已派单整改中、过了期限还没回传的单子，按抄送阶梯逐级往上抄送。
     * 每级一条记录（唯一约束兜底幂等），只补抄高于已抄送级别的台阶；返回本次新抄送条数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int escalateDue() {
        return escalateDue(LocalDateTime.now());
    }

    /** 可指定时刻的重载，供定时任务之外（测试/补扫）确定性触发。 */
    @Transactional(rollbackFor = Exception.class)
    public int escalateDue(LocalDateTime now) {
        List<RectifyOrder> due = orderMapper.selectList(new LambdaQueryWrapper<RectifyOrder>()
                .eq(RectifyOrder::getStatus, RectifyEnums.STATUS_DISPATCHED)
                .isNotNull(RectifyOrder::getDeadline)
                .lt(RectifyOrder::getDeadline, now));
        int created = 0;
        for (RectifyOrder order : due) {
            created += escalateOne(order, now);
        }
        return created;
    }

    private int escalateOne(RectifyOrder order, LocalDateTime now) {
        long overdueHours = Math.max(0, Duration.between(order.getDeadline(), now).toHours());
        int highestLevel = 0;
        List<RectifyProperties.EscalationLevel> ladder = properties.getEscalation();
        for (int i = 0; i < ladder.size(); i++) {
            if (overdueHours >= ladder.get(i).getAfterHours()) {
                highestLevel = i + 1;
            }
        }
        int created = 0;
        for (int level = order.getCcLevel() + 1; level <= highestLevel; level++) {
            RectifyProperties.EscalationLevel target = ladder.get(level - 1);
            RectifyCc cc = new RectifyCc();
            cc.setRectifyId(order.getId());
            cc.setRectifyNo(order.getRectifyNo());
            cc.setCcLevel(level);
            cc.setCcTarget(target.getTarget());
            cc.setOverdueHours((int) overdueHours);
            cc.setReason("超过整改期限 " + overdueHours + " 小时未回传，逐级抄送至：" + target.getTarget());
            cc.setTriggerTime(now);
            try {
                ccMapper.insert(cc);
                created++;
            } catch (DuplicateKeyException e) {
                // 别的线程/节点刚抄过同一级：跳过，不重发
                continue;
            }
            order.setCcLevel(level);
            order.setOperatorName("SYSTEM");
            orderMapper.updateById(order);
            appendEvent(order, RectifyEnums.EVENT_ESCALATE, "SYSTEM", cc.getReason());
        }
        return created;
    }

    // ---- 留证照片 ----

    /** 给已上报的单子补传照片（整改前巡查补拍）；关闭后拒收。 */
    @Transactional(rollbackFor = Exception.class)
    public List<RectifyPhoto> addPhotos(String rectifyNo, String phase, MultipartFile[] photos, String uploader) {
        RectifyOrder order = requireByNo(rectifyNo);
        if (RectifyEnums.STATUS_CLOSED.equals(order.getStatus())) {
            throw new BusinessException("整改单 " + rectifyNo + " 已销项关闭，不能再补传照片");
        }
        String normalizedPhase = normalizePhase(phase);
        String who = Operators.resolve(uploader);
        return savePhotos(order, normalizedPhase, photos, who);
    }

    /**
     * 删照片：单子关闭前一律不许删（留证要完整）；关闭后允许删，但删一条写一条事件留底。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deletePhoto(Long photoId, String operatorName) {
        RectifyPhoto photo = photoMapper.selectById(photoId);
        if (photo == null) {
            throw new BusinessException("留证照片不存在: id=" + photoId);
        }
        RectifyOrder order = requireById(photo.getRectifyId());
        if (!RectifyEnums.STATUS_CLOSED.equals(order.getStatus())) {
            throw new BusinessException("整改单 " + order.getRectifyNo()
                    + " 尚未销项关闭，留证照片不许删除（关闭前一律冻结）");
        }
        String operator = Operators.resolve(operatorName);
        storage.delete(photo.getStoragePath());
        photoMapper.deleteById(photoId);

        RectifyOrder snapshotRef = order;
        RectifyEvent event = new RectifyEvent();
        event.setRectifyId(snapshotRef.getId());
        event.setRectifyNo(snapshotRef.getRectifyNo());
        event.setEventType(RectifyEnums.EVENT_PHOTO_DELETE);
        event.setVersionNo(snapshotRef.getVersionNo());
        event.setOperatorName(operator);
        event.setDetail("删除留证照片：" + photo.getPhase() + " / " + photo.getFileName()
                + "（单据已关闭，关闭前无删除记录）");
        event.setSnapshot(snapshot(snapshotRef));
        event.setEventTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    public byte[] readPhotoBytes(RectifyPhoto photo) {
        return storage.read(photo.getStoragePath());
    }

    public RectifyPhoto requirePhoto(Long photoId) {
        RectifyPhoto photo = photoMapper.selectById(photoId);
        if (photo == null) {
            throw new BusinessException("留证照片不存在: id=" + photoId);
        }
        return photo;
    }

    // ---- 翻查 ----

    /** 翻查：状态 / 点位编号 / 异常类型 / 关键字（单号或点位编号）过滤。 */
    public List<RectifyOrder> list(String status, String siteNo, String abnormalType, String keyword) {
        LambdaQueryWrapper<RectifyOrder> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(RectifyOrder::getStatus, status.trim().toUpperCase());
        }
        if (siteNo != null && !siteNo.trim().isEmpty()) {
            wrapper.eq(RectifyOrder::getSiteNo, siteNo.trim());
        }
        if (abnormalType != null && !abnormalType.trim().isEmpty()) {
            wrapper.eq(RectifyOrder::getAbnormalType, normalizeType(abnormalType));
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(RectifyOrder::getRectifyNo, kw)
                    .or().like(RectifyOrder::getSiteNo, kw));
        }
        return orderMapper.selectList(wrapper.orderByDesc(RectifyOrder::getId));
    }

    /** 翻点位档案时看它的历史异常：该点位全部整改单（含已销项），时间倒序。 */
    public List<RectifyOrder> listBySite(String siteNo) {
        if (siteNo == null || siteNo.trim().isEmpty()) {
            throw new BusinessException("点位编号不能为空");
        }
        return orderMapper.selectList(new LambdaQueryWrapper<RectifyOrder>()
                .eq(RectifyOrder::getSiteNo, siteNo.trim())
                .orderByDesc(RectifyOrder::getId));
    }

    public RectifyOrder requireByNo(String rectifyNo) {
        if (rectifyNo == null || rectifyNo.trim().isEmpty()) {
            throw new BusinessException("整改单号不能为空");
        }
        RectifyOrder order = orderMapper.selectOne(new LambdaQueryWrapper<RectifyOrder>()
                .eq(RectifyOrder::getRectifyNo, rectifyNo.trim()));
        if (order == null) {
            throw new BusinessException("整改单不存在: " + rectifyNo);
        }
        return order;
    }

    public RectifyOrder requireById(Long id) {
        RectifyOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException("整改单不存在: id=" + id);
        }
        return order;
    }

    /** 全量档案：单 + 事件版本 + 照片 + 抄送记录。 */
    public RectifyDetailView detail(String rectifyNo) {
        RectifyOrder order = requireByNo(rectifyNo);
        RectifyDetailView view = new RectifyDetailView();
        view.setOrder(order);
        view.setEvents(eventMapper.selectList(new LambdaQueryWrapper<RectifyEvent>()
                .eq(RectifyEvent::getRectifyId, order.getId())
                .orderByAsc(RectifyEvent::getId)));
        view.setPhotos(photoMapper.selectList(new LambdaQueryWrapper<RectifyPhoto>()
                .eq(RectifyPhoto::getRectifyId, order.getId())
                .orderByAsc(RectifyPhoto::getId)));
        view.setCcs(ccMapper.selectList(new LambdaQueryWrapper<RectifyCc>()
                .eq(RectifyCc::getRectifyId, order.getId())
                .orderByAsc(RectifyCc::getCcLevel)));
        return view;
    }

    public List<RectifyEvent> listEvents(Long rectifyId) {
        return eventMapper.selectList(new LambdaQueryWrapper<RectifyEvent>()
                .eq(RectifyEvent::getRectifyId, rectifyId)
                .orderByAsc(RectifyEvent::getId));
    }

    // ---- 内部方法 ----

    private List<RectifyPhoto> savePhotos(RectifyOrder order, String phase, MultipartFile[] photos, String uploader) {
        List<RectifyPhoto> saved = new ArrayList<>();
        if (photos == null) {
            return saved;
        }
        LocalDateTime batchTime = LocalDateTime.now();
        for (MultipartFile file : photos) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            byte[] data;
            try {
                data = file.getBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("读取上传照片失败: " + file.getOriginalFilename(), e);
            }
            String fingerprint = ImageFingerprints.sha256(data);
            String path = storage.store(order.getRectifyNo(), phase, fingerprint,
                    file.getOriginalFilename(), batchTime, data);
            RectifyPhoto photo = new RectifyPhoto();
            photo.setRectifyId(order.getId());
            photo.setPhase(phase);
            photo.setFileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "photo.jpg");
            photo.setContentType(file.getContentType());
            photo.setFileSize((long) data.length);
            photo.setStoragePath(path);
            photo.setFingerprintSha256(fingerprint);
            photo.setUploaderName(uploader);
            photo.setUploadTime(batchTime);
            photoMapper.insert(photo);
            saved.add(photo);
        }
        return saved;
    }

    private void appendEvent(RectifyOrder order, String eventType, String operator, String detail) {
        RectifyEvent event = new RectifyEvent();
        event.setRectifyId(order.getId());
        event.setRectifyNo(order.getRectifyNo());
        event.setEventType(eventType);
        event.setVersionNo(order.getVersionNo());
        event.setOperatorName(operator);
        event.setDetail(detail);
        event.setSnapshot(snapshot(order));
        event.setEventTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private String snapshot(RectifyOrder order) {
        try {
            return objectMapper.writeValueAsString(order);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("整改单快照序列化失败: " + order.getRectifyNo(), e);
        }
    }

    private String generateNo() {
        long seq = orderMapper.nextRectifySeq();
        return String.format("RC%010d", seq);
    }

    private void requireStatus(RectifyOrder order, String expected, String action) {
        if (RectifyEnums.STATUS_CLOSED.equals(order.getStatus())) {
            throw new BusinessException("整改单 " + order.getRectifyNo() + " 已销项关闭，不能再" + action);
        }
        if (!expected.equals(order.getStatus())) {
            throw new BusinessException("整改单 " + order.getRectifyNo() + " 当前状态是「"
                    + RectifyEnums.statusLabel(order.getStatus()) + "」，不能" + action
                    + "（仅「" + RectifyEnums.statusLabel(expected) + "」可操作）");
        }
    }

    private String normalizeType(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new BusinessException("异常类型不能为空");
        }
        String type = raw.trim().toUpperCase();
        switch (type) {
            case RectifyEnums.TYPE_PANEL_LIFT:
            case RectifyEnums.TYPE_PANEL_DAMAGE:
            case RectifyEnums.TYPE_LIGHT_FAULT:
            case RectifyEnums.TYPE_STRUCTURE_RISK:
            case RectifyEnums.TYPE_OTHER:
                return type;
            default:
                throw new BusinessException("不支持的异常类型: " + raw
                        + "（PANEL_LIFT 牌面翘边 / PANEL_DAMAGE 牌面破损 / LIGHT_FAULT 亮灯故障 / STRUCTURE_RISK 结构隐患 / OTHER 其他）");
        }
    }

    private String normalizePhase(String raw) {
        if (raw == null) {
            throw new BusinessException("照片阶段不能为空（REPORT 现场上报 / REPAIR 整改回传）");
        }
        String phase = raw.trim().toUpperCase();
        if (!RectifyEnums.PHASE_REPORT.equals(phase) && !RectifyEnums.PHASE_REPAIR.equals(phase)) {
            throw new BusinessException("不支持的照片阶段: " + raw + "（REPORT / REPAIR）");
        }
        return phase;
    }

    private String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
