package com.evops.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.ledger.LedgerEnums;
import com.evops.ledger.dto.SiteCreateRequest;
import com.evops.ledger.entity.Site;
import com.evops.ledger.mapper.SiteMapper;
import com.evops.ledger.support.Operators;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SiteService {

    private final SiteMapper siteMapper;

    public SiteService(SiteMapper siteMapper) {
        this.siteMapper = siteMapper;
    }

    /** 挂牌建档：编号重复直接拒，DB 唯一约束兜底并发。 */
    @Transactional(rollbackFor = Exception.class)
    public Site register(SiteCreateRequest request) {
        String siteNo = request.getSiteNo().trim();
        Long exists = siteMapper.selectCount(
                new LambdaQueryWrapper<Site>().eq(Site::getSiteNo, siteNo));
        if (exists != null && exists > 0) {
            throw new BusinessException("点位编号 " + siteNo + " 已存在，重复编号不能建档");
        }
        Site site = new Site();
        site.setSiteNo(siteNo);
        site.setSizeSpec(request.getSizeSpec().trim());
        site.setOrientation(request.getOrientation().trim());
        site.setBusinessDistrict(request.getBusinessDistrict().trim());
        site.setStatus(LedgerEnums.SITE_LISTED);
        site.setWorkType(normalizeWorkType(request.getWorkType()));
        site.setOperatorName(Operators.resolve(request.getOperatorName()));
        site.setRemark(request.getRemark());
        try {
            siteMapper.insert(site);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("点位编号 " + siteNo + " 已存在，重复编号不能建档");
        }
        return site;
    }

    public Site getById(Long id) {
        Site site = siteMapper.selectById(id);
        if (site == null) {
            throw new BusinessException("点位不存在: id=" + id);
        }
        return site;
    }

    public Site getByNo(String siteNo) {
        Site site = siteMapper.selectOne(
                new LambdaQueryWrapper<Site>().eq(Site::getSiteNo, siteNo));
        if (site == null) {
            throw new BusinessException("点位不存在: " + siteNo);
        }
        return site;
    }

    /** 翻查：商圈 / 朝向 / 编号模糊，空条件全量。 */
    public List<Site> search(String siteNo, String businessDistrict, String orientation) {
        LambdaQueryWrapper<Site> wrapper = new LambdaQueryWrapper<>();
        if (siteNo != null && !siteNo.trim().isEmpty()) {
            wrapper.like(Site::getSiteNo, siteNo.trim());
        }
        if (businessDistrict != null && !businessDistrict.trim().isEmpty()) {
            wrapper.like(Site::getBusinessDistrict, businessDistrict.trim());
        }
        if (orientation != null && !orientation.trim().isEmpty()) {
            wrapper.eq(Site::getOrientation, orientation.trim());
        }
        wrapper.orderByAsc(Site::getSiteNo);
        return siteMapper.selectList(wrapper);
    }

    /** 作业性质归一：留空默认普通点位；只认 NORMAL / HIGH_ALTITUDE。 */
    private String normalizeWorkType(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return LedgerEnums.WORK_NORMAL;
        }
        String wt = raw.trim().toUpperCase();
        if (LedgerEnums.WORK_NORMAL.equals(wt) || LedgerEnums.WORK_HIGH_ALTITUDE.equals(wt)) {
            return wt;
        }
        throw new BusinessException("不支持的作业性质: " + raw + "（NORMAL 普通点位 / HIGH_ALTITUDE 高空点位）");
    }
}
