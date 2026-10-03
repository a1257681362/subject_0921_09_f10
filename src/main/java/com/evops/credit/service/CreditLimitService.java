package com.evops.credit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.credit.CreditEnums;
import com.evops.credit.dto.LimitApprovalRequest;
import com.evops.credit.dto.LimitChangeRequest;
import com.evops.credit.entity.CreditLimitVersion;
import com.evops.credit.entity.Customer;
import com.evops.credit.mapper.CreditLimitVersionMapper;
import com.evops.credit.mapper.CustomerMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 信用额度版本域：建档之后额度一动都要走审批。
 * 申请（销售，仅名下客户）→ PENDING；财务审批 → EFFECTIVE（客户额度/状态落新值，
 * 上一生效版本置 SUPERSEDED，前后值都留版本）或 REJECTED（客户不变）。
 * 申请人不能审批自己的申请（职责分离）。
 */
@Service
public class CreditLimitService {

    private final CreditLimitVersionMapper versionMapper;
    private final CustomerMapper customerMapper;
    private final CustomerService customerService;

    public CreditLimitService(CreditLimitVersionMapper versionMapper,
                              CustomerMapper customerMapper,
                              CustomerService customerService) {
        this.versionMapper = versionMapper;
        this.customerMapper = customerMapper;
        this.customerService = customerService;
    }

    /** 提交额度/状态变更申请。同一客户同时只准有一个待审批版本。 */
    @Transactional(rollbackFor = Exception.class)
    public CreditLimitVersion apply(String customerNo, LimitChangeRequest request, Viewer viewer) {
        Customer customer = customerService.requireViewable(customerNo, viewer);
        String type = request.getChangeType() == null ? "" : request.getChangeType().trim().toUpperCase();
        Long pending = versionMapper.selectCount(new LambdaQueryWrapper<CreditLimitVersion>()
                .eq(CreditLimitVersion::getCustomerId, customer.getId())
                .eq(CreditLimitVersion::getStatus, CreditEnums.VERSION_PENDING));
        if (pending != null && pending > 0) {
            throw new BusinessException("客户「" + customer.getCustomerName() + "」已有待审批的额度变更，请先审批或驳回");
        }

        CreditLimitVersion version = new CreditLimitVersion();
        version.setCustomerId(customer.getId());
        version.setBeforeLimit(customer.getCreditLimit());
        version.setBeforeStatus(customer.getCreditStatus());
        version.setStatus(CreditEnums.VERSION_PENDING);
        version.setApplicantName(viewer.getUsername());
        version.setApplyReason(request.getReason());
        version.setApplyTime(LocalDateTime.now());

        switch (type) {
            case CreditEnums.CHANGE_ADJUST: {
                BigDecimal after = CustomerService.normalizeMoney(request.getAfterLimit());
                version.setChangeType(CreditEnums.CHANGE_ADJUST);
                version.setAfterLimit(after);
                version.setAfterStatus(customer.getCreditStatus());
                break;
            }
            case CreditEnums.CHANGE_FREEZE: {
                if (CreditEnums.CUSTOMER_FROZEN.equals(customer.getCreditStatus())) {
                    throw new BusinessException("客户已是冻结状态，不能重复冻结");
                }
                version.setChangeType(CreditEnums.CHANGE_FREEZE);
                version.setAfterLimit(customer.getCreditLimit());
                version.setAfterStatus(CreditEnums.CUSTOMER_FROZEN);
                break;
            }
            case CreditEnums.CHANGE_UNFREEZE: {
                if (CreditEnums.CUSTOMER_ACTIVE.equals(customer.getCreditStatus())) {
                    throw new BusinessException("客户当前在档，无需解冻");
                }
                version.setChangeType(CreditEnums.CHANGE_UNFREEZE);
                version.setAfterLimit(customer.getCreditLimit());
                version.setAfterStatus(CreditEnums.CUSTOMER_ACTIVE);
                break;
            }
            default:
                throw new BusinessException("不支持的额度变更类型: " + request.getChangeType()
                        + "（仅 ADJUST/FREEZE/UNFREEZE；建档授信在建客户时完成）");
        }

        Integer maxNo = versionMapper.selectList(new LambdaQueryWrapper<CreditLimitVersion>()
                        .eq(CreditLimitVersion::getCustomerId, customer.getId()))
                .stream().map(CreditLimitVersion::getVersionNo).max(Integer::compareTo).orElse(0);
        version.setVersionNo(maxNo + 1);
        versionMapper.insert(version);
        return version;
    }

    /** 财务审批通过：落客户新值，上一生效版本置 SUPERSEDED，本版生效。 */
    @Transactional(rollbackFor = Exception.class)
    public CreditLimitVersion approve(Long versionId, LimitApprovalRequest request, Viewer viewer) {
        viewer.requireApprove();
        CreditLimitVersion version = requireVersion(versionId);
        Customer customer = customerService.requireById(version.getCustomerId());
        if (!CreditEnums.VERSION_PENDING.equals(version.getStatus())) {
            throw new BusinessException("只有待审批版本可审批，该版本当前状态: " + version.getStatus());
        }
        String approver = viewer.getUsername();
        if (approver.equals(version.getApplicantName())) {
            throw new BusinessException("申请人不能审批自己提交的额度变更（职责分离）");
        }
        if (CreditEnums.CHANGE_ADJUST.equals(version.getChangeType())
                && version.getAfterLimit().compareTo(customer.getOccupiedAmount()) < 0) {
            throw new BusinessException("调整后额度 " + version.getAfterLimit().toPlainString()
                    + " 低于当前在额占用 " + customer.getOccupiedAmount().toPlainString()
                    + "，请先回款释放或撤刊后再下调");
        }

        LocalDateTime now = LocalDateTime.now();
        version.setStatus(CreditEnums.VERSION_EFFECTIVE);
        version.setApproverName(approver);
        version.setApproveComment(request.getComment());
        version.setApproveTime(now);
        versionMapper.updateById(version);

        // 上一生效版本置 SUPERSEDED（建档 v1 也会在首次调额/冻结时被取代，历史永久可翻）
        if (customer.getCurrentVersionId() != null) {
            CreditLimitVersion prev = new CreditLimitVersion();
            prev.setId(customer.getCurrentVersionId());
            prev.setStatus(CreditEnums.VERSION_SUPERSEDED);
            versionMapper.updateById(prev);
        }

        Customer update = new Customer();
        update.setId(customer.getId());
        update.setCreditLimit(version.getAfterLimit());
        update.setCreditStatus(version.getAfterStatus());
        update.setCurrentVersionId(version.getId());
        update.setCurrentVersionNo(version.getVersionNo());
        customerMapper.updateById(update);
        return version;
    }

    /** 财务驳回：版本置 REJECTED 留底，客户额度/状态不变。 */
    @Transactional(rollbackFor = Exception.class)
    public CreditLimitVersion reject(Long versionId, LimitApprovalRequest request, Viewer viewer) {
        viewer.requireApprove();
        CreditLimitVersion version = requireVersion(versionId);
        if (!CreditEnums.VERSION_PENDING.equals(version.getStatus())) {
            throw new BusinessException("只有待审批版本可驳回，该版本当前状态: " + version.getStatus());
        }
        version.setStatus(CreditEnums.VERSION_REJECTED);
        version.setApproverName(viewer.getUsername());
        version.setApproveComment(request.getComment());
        version.setApproveTime(LocalDateTime.now());
        versionMapper.updateById(version);
        return version;
    }

    /** 版本翻查（新版本号在前）；销售只能翻自己名下客户。 */
    public List<CreditLimitVersion> listVersions(String customerNo, Viewer viewer) {
        Customer customer = customerService.requireViewable(customerNo, viewer);
        return versionMapper.selectList(new LambdaQueryWrapper<CreditLimitVersion>()
                .eq(CreditLimitVersion::getCustomerId, customer.getId())
                .orderByDesc(CreditLimitVersion::getVersionNo));
    }

    /** 待审批：财务看全部；销售只看自己名下客户的申请。 */
    public List<CreditLimitVersion> listPending(Viewer viewer) {
        LambdaQueryWrapper<CreditLimitVersion> wrapper = new LambdaQueryWrapper<CreditLimitVersion>()
                .eq(CreditLimitVersion::getStatus, CreditEnums.VERSION_PENDING)
                .orderByAsc(CreditLimitVersion::getId);
        List<CreditLimitVersion> all = versionMapper.selectList(wrapper);
        if (viewer.isAllCustomers()) {
            return all;
        }
        java.util.Set<Long> owned = customerService.list(viewer, null, null).stream()
                .map(Customer::getId).collect(Collectors.toSet());
        return all.stream().filter(v -> owned.contains(v.getCustomerId())).collect(Collectors.toList());
    }

    private CreditLimitVersion requireVersion(Long versionId) {
        CreditLimitVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException("额度版本不存在: id=" + versionId);
        }
        return version;
    }

    /** 可用额度。 */
    public static BigDecimal available(Customer customer) {
        return customer.getCreditLimit().subtract(customer.getOccupiedAmount());
    }
}
