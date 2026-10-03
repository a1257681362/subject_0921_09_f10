package com.evops.compliance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.compliance.ComplianceEnums;
import com.evops.compliance.dto.CategoryCreateRequest;
import com.evops.compliance.dto.CategoryModifyRequest;
import com.evops.compliance.dto.CategoryRestrictRequest;
import com.evops.compliance.entity.IndustryCategory;
import com.evops.compliance.entity.IndustryCategoryOpLog;
import com.evops.compliance.entity.IndustryCategoryVersion;
import com.evops.compliance.mapper.IndustryCategoryMapper;
import com.evops.compliance.mapper.IndustryCategoryOpLogMapper;
import com.evops.compliance.mapper.IndustryCategoryVersionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 受限行业类目名单域：合规岗维护，即改即生效。
 *
 * 每次维护动作同事务做三件事：改条目 → 追加逐条操作流水（前后值 JSON）
 * → 上一生效版本置 SUPERSEDED、另落一版 EFFECTIVE 全量快照。拦截判定
 * {@link #findRestricted(String)} 直接读条目表，不经过任何缓存，保证调整当场生效。
 */
@Service
public class IndustryCategoryService {

    private final IndustryCategoryMapper categoryMapper;
    private final IndustryCategoryVersionMapper versionMapper;
    private final IndustryCategoryOpLogMapper oplogMapper;
    private final ObjectMapper objectMapper;

    public IndustryCategoryService(IndustryCategoryMapper categoryMapper,
                                   IndustryCategoryVersionMapper versionMapper,
                                   IndustryCategoryOpLogMapper oplogMapper,
                                   ObjectMapper objectMapper) {
        this.categoryMapper = categoryMapper;
        this.versionMapper = versionMapper;
        this.oplogMapper = oplogMapper;
        this.objectMapper = objectMapper;
    }

    // ---- 翻查 ----

    /** 全量翻查（含停用），编码升序。 */
    public List<IndustryCategory> list(boolean includeDisabled) {
        LambdaQueryWrapper<IndustryCategory> wrapper = new LambdaQueryWrapper<IndustryCategory>()
                .orderByAsc(IndustryCategory::getCategoryCode);
        if (!includeDisabled) {
            wrapper.eq(IndustryCategory::getStatus, ComplianceEnums.CATEGORY_ACTIVE);
        }
        return categoryMapper.selectList(wrapper);
    }

    /** 建档下拉：只要在档条目（受限的也要返回，前端要据此即时提示）。 */
    public List<IndustryCategory> listActive() {
        return list(false);
    }

    public IndustryCategory requireByCode(String code) {
        IndustryCategory category = categoryMapper.selectOne(new LambdaQueryWrapper<IndustryCategory>()
                .eq(IndustryCategory::getCategoryCode, normalize(code)));
        if (category == null) {
            throw new BusinessException("行业类目不存在: " + code);
        }
        return category;
    }

    /** 版本链（新版本在前）。 */
    public List<IndustryCategoryVersion> listVersions() {
        return versionMapper.selectList(new LambdaQueryWrapper<IndustryCategoryVersion>()
                .orderByDesc(IndustryCategoryVersion::getVersionNo));
    }

    /** 逐条操作流水（新操作在前）。 */
    public List<IndustryCategoryOpLog> listOps(String categoryCode) {
        LambdaQueryWrapper<IndustryCategoryOpLog> wrapper = new LambdaQueryWrapper<IndustryCategoryOpLog>()
                .orderByDesc(IndustryCategoryOpLog::getId);
        if (categoryCode != null && !categoryCode.trim().isEmpty()) {
            wrapper.eq(IndustryCategoryOpLog::getCategoryCode, categoryCode.trim().toUpperCase());
        }
        return oplogMapper.selectList(wrapper);
    }

    // ---- 拦截口判定（建档、授信上刊共用） ----

    /**
     * 命中受限返回受限条目（带依据），否则返回 null。
     * 只读条目表当前值——名单调整提交事务后立即按新值判定，无缓存延迟。
     */
    public IndustryCategory findRestricted(String categoryCode) {
        if (categoryCode == null || categoryCode.trim().isEmpty()) {
            return null;
        }
        IndustryCategory category = categoryMapper.selectOne(new LambdaQueryWrapper<IndustryCategory>()
                .eq(IndustryCategory::getCategoryCode, normalize(categoryCode)));
        if (category != null
                && ComplianceEnums.CATEGORY_ACTIVE.equals(category.getStatus())
                && Integer.valueOf(1).equals(category.getRestricted())) {
            return category;
        }
        return null;
    }

    /** 统一拦截话术：两个口子的提示都带类目与法规依据。 */
    public static String rejectMessage(IndustryCategory category, String action) {
        return "行业类目「" + category.getCategoryName() + "（" + category.getCategoryCode()
                + "）」命中受限行业名单，依据：" + category.getLegalBasis()
                + "；禁止" + action + "。如有异议请联系合规岗核对名单";
    }

    // ---- 合规岗维护（即改即生效 + 流水 + 版本） ----

    /** 新增类目：默认在档、非受限。 */
    @Transactional(rollbackFor = Exception.class)
    public IndustryCategory addCategory(CategoryCreateRequest request, Viewer viewer) {
        viewer.requireCompliance();
        String code = normalize(request.getCategoryCode());
        String name = request.getCategoryName().trim();
        IndustryCategory category = new IndustryCategory();
        category.setCategoryCode(code);
        category.setCategoryName(name);
        category.setStatus(ComplianceEnums.CATEGORY_ACTIVE);
        category.setRestricted(0);
        category.setCurrentVersionNo(0);
        category.setOperatorName(viewer.getUsername());
        category.setRemark(request.getRemark());
        try {
            categoryMapper.insert(category);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("类目编码「" + code + "」已存在");
        }
        recordChange(ComplianceEnums.CHANGE_ADD, category, null, category,
                null, request.getRemark(), viewer.getUsername());
        return category;
    }

    /** 设为受限：依据必填，当场对建档/上刊生效。 */
    @Transactional(rollbackFor = Exception.class)
    public IndustryCategory restrict(String code, CategoryRestrictRequest request, Viewer viewer) {
        viewer.requireCompliance();
        IndustryCategory before = requireByCode(code);
        requireActive(before);
        if (Integer.valueOf(1).equals(before.getRestricted())) {
            throw new BusinessException("类目「" + before.getCategoryName() + "」已是受限状态，不能重复设置");
        }
        String basis = request.getLegalBasis().trim();

        IndustryCategory after = copyForUpdate(before);
        after.setRestricted(1);
        after.setLegalBasis(basis);
        after.setRestrictedTime(LocalDateTime.now());
        after.setReleasedTime(null);
        categoryMapper.updateById(after);
        reload(after);

        recordChange(ComplianceEnums.CHANGE_RESTRICT, after, before, after,
                basis, request.getRemark(), viewer.getUsername());
        return after;
    }

    /** 解除受限：之后该行业客户建档、新上刊恢复正常（已冻结在途单需另行一键解冻）。 */
    @Transactional(rollbackFor = Exception.class)
    public IndustryCategory release(String code, String remark, Viewer viewer) {
        viewer.requireCompliance();
        IndustryCategory before = requireByCode(code);
        requireActive(before);
        if (!Integer.valueOf(1).equals(before.getRestricted())) {
            throw new BusinessException("类目「" + before.getCategoryName() + "」当前未受限，无需解除");
        }

        IndustryCategory after = copyForUpdate(before);
        after.setRestricted(0);
        after.setReleasedTime(LocalDateTime.now());
        categoryMapper.updateById(after);
        reload(after);

        recordChange(ComplianceEnums.CHANGE_RELEASE, after, before, after, null,
                remark, viewer.getUsername());
        return after;
    }

    /** 改名 / 更新依据。受限条目清空依据一律拒绝（拦截提示必须带得出依据）。 */
    @Transactional(rollbackFor = Exception.class)
    public IndustryCategory modify(String code, CategoryModifyRequest request, Viewer viewer) {
        viewer.requireCompliance();
        IndustryCategory before = requireByCode(code);
        requireActive(before);
        String name = request.getCategoryName().trim();
        String basis = request.getLegalBasis() == null ? before.getLegalBasis()
                : request.getLegalBasis().trim();
        if (Integer.valueOf(1).equals(before.getRestricted())
                && (basis == null || basis.isEmpty())) {
            throw new BusinessException("受限类目必须保留法规依据，不能清空");
        }

        IndustryCategory after = copyForUpdate(before);
        after.setCategoryName(name);
        after.setLegalBasis(basis);
        categoryMapper.updateById(after);
        reload(after);

        recordChange(ComplianceEnums.CHANGE_MODIFY, after, before, after, basis,
                request.getRemark(), viewer.getUsername());
        return after;
    }

    /** 停用类目：历史客户留名可翻，建档不可再选；受限条目须先解除受限再停用。 */
    @Transactional(rollbackFor = Exception.class)
    public IndustryCategory disable(String code, String remark, Viewer viewer) {
        viewer.requireCompliance();
        IndustryCategory before = requireByCode(code);
        requireActive(before);
        if (Integer.valueOf(1).equals(before.getRestricted())) {
            throw new BusinessException("类目「" + before.getCategoryName()
                    + "」仍为受限状态，请先解除受限再停用（在途单冻结以受限状态为准）");
        }

        IndustryCategory after = copyForUpdate(before);
        after.setStatus(ComplianceEnums.CATEGORY_DISABLED);
        categoryMapper.updateById(after);
        reload(after);

        recordChange(ComplianceEnums.CHANGE_DISABLE, after, before, after, null,
                remark, viewer.getUsername());
        return after;
    }

    // ---- 内部 ----

    private void recordChange(String action, IndustryCategory current,
                              IndustryCategory before, IndustryCategory after,
                              String legalBasis, String remark, String operator) {
        // 1) 逐条操作流水
        IndustryCategoryOpLog oplog = new IndustryCategoryOpLog();
        oplog.setCategoryCode(current.getCategoryCode());
        oplog.setCategoryName(current.getCategoryName());
        oplog.setAction(action);
        oplog.setBeforeJson(toJson(before));
        oplog.setAfterJson(toJson(after));
        oplog.setLegalBasis(legalBasis != null ? legalBasis : current.getLegalBasis());
        oplog.setOperatorName(operator);
        oplog.setRemark(remark);
        oplog.setOpTime(LocalDateTime.now());
        oplogMapper.insert(oplog);

        // 2) 版本：上一版 SUPERSEDED，新落一版 EFFECTIVE 全量快照
        List<IndustryCategory> all = list(true);
        Integer maxNo = versionMapper.selectList(new LambdaQueryWrapper<IndustryCategoryVersion>())
                .stream().map(IndustryCategoryVersion::getVersionNo).max(Integer::compareTo).orElse(0);
        IndustryCategoryVersion effective = versionMapper.selectOne(new LambdaQueryWrapper<IndustryCategoryVersion>()
                .eq(IndustryCategoryVersion::getStatus, ComplianceEnums.VERSION_EFFECTIVE)
                .orderByDesc(IndustryCategoryVersion::getVersionNo)
                .last("LIMIT 1"));
        if (effective != null) {
            IndustryCategoryVersion superseded = new IndustryCategoryVersion();
            superseded.setId(effective.getId());
            superseded.setStatus(ComplianceEnums.VERSION_SUPERSEDED);
            versionMapper.updateById(superseded);
        }
        int restrictedCount = (int) all.stream()
                .filter(c -> Integer.valueOf(1).equals(c.getRestricted())).count();

        IndustryCategoryVersion version = new IndustryCategoryVersion();
        version.setVersionNo(maxNo + 1);
        version.setChangeType(action);
        version.setTargetCode(current.getCategoryCode());
        version.setStatus(ComplianceEnums.VERSION_EFFECTIVE);
        version.setItemCount(all.size());
        version.setRestrictedCount(restrictedCount);
        version.setSnapshot(toJson(all));
        version.setReason(remark);
        version.setOperatorName(operator);
        version.setEffectiveTime(LocalDateTime.now());
        versionMapper.insert(version);

        // 3) 条目回指当前版本号
        IndustryCategory patch = new IndustryCategory();
        patch.setId(current.getId());
        patch.setCurrentVersionNo(version.getVersionNo());
        categoryMapper.updateById(patch);
        current.setCurrentVersionNo(version.getVersionNo());
    }

    private void requireActive(IndustryCategory category) {
        if (ComplianceEnums.CATEGORY_DISABLED.equals(category.getStatus())) {
            throw new BusinessException("类目「" + category.getCategoryName() + "」已停用，不能再调整；如需启用请新增同名编码类目");
        }
    }

    private IndustryCategory copyForUpdate(IndustryCategory src) {
        IndustryCategory patch = new IndustryCategory();
        patch.setId(src.getId());
        patch.setCategoryCode(src.getCategoryCode());
        patch.setCategoryName(src.getCategoryName());
        patch.setStatus(src.getStatus());
        patch.setRestricted(src.getRestricted());
        patch.setLegalBasis(src.getLegalBasis());
        patch.setRestrictedTime(src.getRestrictedTime());
        patch.setReleasedTime(src.getReleasedTime());
        patch.setOperatorName(src.getOperatorName());
        return patch;
    }

    private void reload(IndustryCategory patch) {
        IndustryCategory fresh = categoryMapper.selectById(patch.getId());
        patch.setCategoryCode(fresh.getCategoryCode());
        patch.setStatus(fresh.getStatus());
        patch.setRestricted(fresh.getRestricted());
        patch.setLegalBasis(fresh.getLegalBasis());
        patch.setRestrictedTime(fresh.getRestrictedTime());
        patch.setReleasedTime(fresh.getReleasedTime());
        patch.setRemark(fresh.getRemark());
    }

    private String normalize(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new BusinessException("类目编码不能为空");
        }
        return code.trim().toUpperCase();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("类目名单快照序列化失败", e);
        }
    }
}
