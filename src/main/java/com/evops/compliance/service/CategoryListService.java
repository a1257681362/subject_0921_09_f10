package com.evops.compliance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.compliance.ComplianceEnums;
import com.evops.compliance.dto.CategoryItemSpec;
import com.evops.compliance.dto.PublishResult;
import com.evops.compliance.entity.CategoryList;
import com.evops.compliance.entity.CategoryListItem;
import com.evops.compliance.entity.CategoryListVersion;
import com.evops.compliance.mapper.CategoryListItemMapper;
import com.evops.compliance.mapper.CategoryListMapper;
import com.evops.compliance.mapper.CategoryListVersionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 受限行业类目名单域：名单主档（全公司一份）→ 版本（草稿/发布即生效/旧版取代）→ 条目（类目+依据）。
 *
 * 口径：
 * - 拦截实时查当前生效版本条目，发布新版即时生效（无缓存）；
 * - 调整只能"另起新版 → 整表配置 → 发布"，生效版不可作废，不留无名单空窗；
 * - 每次发布一个版本（经手人/发布时刻/调整说明/全量条目），版本链即操作记录。
 */
@Service
public class CategoryListService {

    /** 名单主档全公司一份（schema 预置 id=1） */
    static final long SINGLE_LIST_ID = 1L;

    private final CategoryListMapper listMapper;
    private final CategoryListVersionMapper versionMapper;
    private final CategoryListItemMapper itemMapper;

    public CategoryListService(CategoryListMapper listMapper,
                               CategoryListVersionMapper versionMapper,
                               CategoryListItemMapper itemMapper) {
        this.listMapper = listMapper;
        this.versionMapper = versionMapper;
        this.itemMapper = itemMapper;
    }

    // ---- 拦截口径（客户建档、上刊提交两个口共用） ----

    /**
     * 行业类目命中当前生效名单即抛 BusinessException（提示含类目、依据、名单版本）；
     * 类目为空（历史数据）或名单尚未发布任何版本时放行。
     *
     * @param category 行业类目
     * @param scene    拦截场景（"客户建档" / "上刊提交"），用于提示语
     */
    public void requireNotRestricted(String category, String scene) {
        CategoryListItem hit = findRestricted(category);
        if (hit != null) {
            CategoryListVersion effective = currentEffective();
            throw new BusinessException("行业类目「" + category.trim() + "」属于受限行业，" + scene + "被拦截。依据："
                    + hit.getBasis() + "（名单版本 v" + (effective == null ? "?" : effective.getVersionNo())
                    + "，合规岗维护；如需特批请联系合规岗）");
        }
    }

    /** 命中当前生效名单的条目（含依据）；未命中/类目空/无生效版返回 null。 */
    public CategoryListItem findRestricted(String category) {
        if (category == null || category.trim().isEmpty()) {
            return null;
        }
        CategoryListVersion effective = currentEffective();
        if (effective == null) {
            return null;
        }
        return itemMapper.selectOne(new LambdaQueryWrapper<CategoryListItem>()
                .eq(CategoryListItem::getVersionId, effective.getId())
                .eq(CategoryListItem::getCategoryName, category.trim())
                .last("LIMIT 1"));
    }

    // ---- 查询 ----

    public CategoryList requireList() {
        CategoryList list = listMapper.selectById(SINGLE_LIST_ID);
        if (list == null) {
            throw new BusinessException("受限行业名单主档未初始化（schema 预置缺失）");
        }
        return list;
    }

    /** 当前生效版本；名单尚未发布过任何版本时返回 null（=全部放行）。 */
    public CategoryListVersion currentEffective() {
        return versionMapper.selectOne(new LambdaQueryWrapper<CategoryListVersion>()
                .eq(CategoryListVersion::getStatus, ComplianceEnums.VERSION_EFFECTIVE)
                .orderByDesc(CategoryListVersion::getVersionNo)
                .last("LIMIT 1"));
    }

    /** 生效版条目（无生效版=空列表）。 */
    public List<CategoryListItem> effectiveItems() {
        CategoryListVersion effective = currentEffective();
        if (effective == null) {
            return Collections.emptyList();
        }
        return itemsOf(effective.getId());
    }

    /** 生效版 类目→依据 映射（冻结事件留底、前端预校验用）。 */
    public Map<String, String> effectiveBasisMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (CategoryListItem item : effectiveItems()) {
            map.put(item.getCategoryName(), item.getBasis());
        }
        return map;
    }

    public List<CategoryListItem> itemsOf(Long versionId) {
        return itemMapper.selectList(new LambdaQueryWrapper<CategoryListItem>()
                .eq(CategoryListItem::getVersionId, versionId)
                .orderByAsc(CategoryListItem::getId));
    }

    public List<CategoryListVersion> listVersions() {
        return versionMapper.selectList(new LambdaQueryWrapper<CategoryListVersion>()
                .orderByDesc(CategoryListVersion::getVersionNo));
    }

    public CategoryListVersion requireVersion(Long versionId) {
        CategoryListVersion v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new BusinessException("名单版本不存在: id=" + versionId);
        }
        return v;
    }

    // ---- 维护（合规岗/系统） ----

    /**
     * 另起新版：从当前生效版拷贝条目做起点（无生效版则空草稿）。
     * 全名单同时只允许一个编制中草稿：已有草稿直接返回它（重复点击幂等）。
     */
    @Transactional(rollbackFor = Exception.class)
    public CategoryListVersion newVersion(Viewer viewer) {
        viewer.requireListAdmin();
        CategoryList list = requireList();
        CategoryListVersion draft = versionMapper.selectOne(new LambdaQueryWrapper<CategoryListVersion>()
                .eq(CategoryListVersion::getListId, list.getId())
                .eq(CategoryListVersion::getStatus, ComplianceEnums.VERSION_DRAFT)
                .last("LIMIT 1"));
        if (draft != null) {
            return draft;
        }
        CategoryListVersion effective = currentEffective();

        CategoryListVersion v = new CategoryListVersion();
        v.setListId(list.getId());
        v.setVersionNo(nextVersionNo());
        v.setStatus(ComplianceEnums.VERSION_DRAFT);
        v.setItemCount(0);
        v.setOperatorName(viewer.getUsername());
        versionMapper.insert(v);

        if (effective != null) {
            int count = 0;
            for (CategoryListItem item : itemsOf(effective.getId())) {
                CategoryListItem copy = new CategoryListItem();
                copy.setVersionId(v.getId());
                copy.setCategoryName(item.getCategoryName());
                copy.setBasis(item.getBasis());
                itemMapper.insert(copy);
                count++;
            }
            CategoryListVersion patch = new CategoryListVersion();
            patch.setId(v.getId());
            patch.setItemCount(count);
            versionMapper.updateById(patch);
            v.setItemCount(count);
        }
        return v;
    }

    /** 整表配置草稿条目：类目去重、依据必填；发布后冻结，只能 DRAFT 配。 */
    @Transactional(rollbackFor = Exception.class)
    public CategoryListVersion configure(Long versionId, List<CategoryItemSpec> items, Viewer viewer) {
        viewer.requireListAdmin();
        CategoryListVersion v = requireVersion(versionId);
        requireDraft(v, "配置条目");

        // 先整表清空再逐条插入（整表替换式配置，与 F4 刊例档位配置同口径）
        itemMapper.delete(new LambdaQueryWrapper<CategoryListItem>()
                .eq(CategoryListItem::getVersionId, v.getId()));
        Set<String> seen = new LinkedHashSet<>();
        List<CategoryListItem> rows = new ArrayList<>();
        for (CategoryItemSpec spec : items) {
            String name = spec.getCategoryName() == null ? "" : spec.getCategoryName().trim();
            String basis = spec.getBasis() == null ? "" : spec.getBasis().trim();
            if (name.isEmpty()) {
                throw new BusinessException("行业类目不能为空");
            }
            if (basis.isEmpty()) {
                throw new BusinessException("类目「" + name + "」的受限依据不能为空（拦截提示要带依据）");
            }
            if (!seen.add(name)) {
                throw new BusinessException("行业类目「" + name + "」重复配置，同一版本内类目唯一");
            }
            CategoryListItem row = new CategoryListItem();
            row.setVersionId(v.getId());
            row.setCategoryName(name);
            row.setBasis(basis);
            rows.add(row);
        }
        for (CategoryListItem row : rows) {
            try {
                itemMapper.insert(row);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("行业类目「" + row.getCategoryName() + "」重复配置，同一版本内类目唯一");
            }
        }
        CategoryListVersion patch = new CategoryListVersion();
        patch.setId(v.getId());
        patch.setItemCount(rows.size());
        versionMapper.updateById(patch);
        v.setItemCount(rows.size());
        return v;
    }

    /**
     * 发布：即时生效（拦截口径实时读生效版），旧生效版自动 SUPERSEDED；
     * 返回相对旧生效版的类目增减，供"新增类目一键批量冻结在途单"。
     */
    @Transactional(rollbackFor = Exception.class)
    public PublishResult publish(Long versionId, String changeSummary, Viewer viewer) {
        viewer.requireListAdmin();
        CategoryListVersion v = requireVersion(versionId);
        requireDraft(v, "发布");
        CategoryList list = requireList();
        CategoryListVersion old = currentEffective();

        Set<String> oldCats = old == null ? Collections.emptySet()
                : itemsOf(old.getId()).stream().map(CategoryListItem::getCategoryName).collect(Collectors.toSet());
        Set<String> newCats = itemsOf(v.getId()).stream()
                .map(CategoryListItem::getCategoryName).collect(Collectors.toSet());

        // 旧生效版置已被新版取代（历史永久可翻）
        if (old != null) {
            CategoryListVersion supersede = new CategoryListVersion();
            supersede.setId(old.getId());
            supersede.setStatus(ComplianceEnums.VERSION_SUPERSEDED);
            versionMapper.updateById(supersede);
        }

        // 新版生效：发布人/发布时刻/调整说明随版本留底（版本链即操作记录）
        LocalDateTime publishTime = LocalDateTime.now();
        CategoryListVersion patch = new CategoryListVersion();
        patch.setId(v.getId());
        patch.setStatus(ComplianceEnums.VERSION_EFFECTIVE);
        patch.setPublishTime(publishTime);
        patch.setOperatorName(viewer.getUsername());
        patch.setChangeSummary(changeSummary == null || changeSummary.trim().isEmpty()
                ? "名单调整" : changeSummary.trim());
        versionMapper.updateById(patch);

        // 主档当前版本指针
        CategoryList listPatch = new CategoryList();
        listPatch.setId(list.getId());
        listPatch.setCurrentVersionId(v.getId());
        listPatch.setCurrentVersionNo(v.getVersionNo());
        listPatch.setOperatorName(viewer.getUsername());
        listMapper.updateById(listPatch);

        v.setStatus(ComplianceEnums.VERSION_EFFECTIVE);
        v.setPublishTime(publishTime);
        v.setOperatorName(viewer.getUsername());
        v.setChangeSummary(patch.getChangeSummary());
        List<String> added = newCats.stream().filter(c -> !oldCats.contains(c)).collect(Collectors.toList());
        List<String> removed = oldCats.stream().filter(c -> !newCats.contains(c)).collect(Collectors.toList());
        return new PublishResult(v, added, removed);
    }

    private void requireDraft(CategoryListVersion v, String action) {
        if (!ComplianceEnums.VERSION_DRAFT.equals(v.getStatus())) {
            throw new BusinessException("名单版本 v" + v.getVersionNo() + " 已"
                    + ComplianceEnums.versionStatusLabel(v.getStatus())
                    + "，不能" + action + "；调整请另起新版");
        }
    }

    private int nextVersionNo() {
        CategoryListVersion max = versionMapper.selectOne(new LambdaQueryWrapper<CategoryListVersion>()
                .orderByDesc(CategoryListVersion::getVersionNo)
                .last("LIMIT 1"));
        return max == null ? 1 : max.getVersionNo() + 1;
    }
}
