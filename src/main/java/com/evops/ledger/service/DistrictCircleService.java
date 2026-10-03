package com.evops.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.ledger.dto.DistrictCircleRequest;
import com.evops.ledger.entity.DistrictCircle;
import com.evops.ledger.mapper.DistrictCircleMapper;
import com.evops.ledger.support.GeoMath;
import com.evops.ledger.support.Operators;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商圈圈界：把拓展部"靠感觉"圈的商圈落成中心坐标+半径，重叠带判定以落库的圈界为准。
 * 圈界是当前态：重圈直接改写同名记录（点位归属不受影响，仍按录入时定的主商圈走）。
 */
@Service
public class DistrictCircleService {

    private final DistrictCircleMapper districtCircleMapper;

    public DistrictCircleService(DistrictCircleMapper districtCircleMapper) {
        this.districtCircleMapper = districtCircleMapper;
    }

    /** 圈定商圈：同名商圈不能重复圈，DB 唯一约束兜底并发。 */
    @Transactional(rollbackFor = Exception.class)
    public DistrictCircle create(DistrictCircleRequest request) {
        String name = request.getDistrictName().trim();
        Long exists = districtCircleMapper.selectCount(
                new LambdaQueryWrapper<DistrictCircle>().eq(DistrictCircle::getDistrictName, name));
        if (exists != null && exists > 0) {
            throw new BusinessException("商圈 " + name + " 已有圈界，要调整请用重圈");
        }
        DistrictCircle circle = new DistrictCircle();
        circle.setDistrictName(name);
        apply(circle, request);
        try {
            districtCircleMapper.insert(circle);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("商圈 " + name + " 已有圈界，要调整请用重圈");
        }
        return circle;
    }

    /** 重圈：同名商圈的圈界改写为新圆心/半径（当前态覆盖，不动点位归属）。 */
    @Transactional(rollbackFor = Exception.class)
    public DistrictCircle redraw(String districtName, DistrictCircleRequest request) {
        DistrictCircle circle = districtCircleMapper.selectOne(
                new LambdaQueryWrapper<DistrictCircle>().eq(DistrictCircle::getDistrictName, districtName.trim()));
        if (circle == null) {
            throw new BusinessException("商圈圈界不存在: " + districtName);
        }
        apply(circle, request);
        districtCircleMapper.updateById(circle);
        return circle;
    }

    /** 翻查：全部圈界按商圈名排序。 */
    public List<DistrictCircle> list() {
        return districtCircleMapper.selectList(new LambdaQueryWrapper<DistrictCircle>()
                .orderByAsc(DistrictCircle::getDistrictName));
    }

    private void apply(DistrictCircle circle, DistrictCircleRequest request) {
        circle.setCenterLat(GeoMath.requireLat(request.getCenterLat()));
        circle.setCenterLng(GeoMath.requireLng(request.getCenterLng()));
        circle.setRadiusKm(GeoMath.requireRadiusKm(request.getRadiusKm()));
        circle.setOperatorName(Operators.resolve(request.getOperatorName()));
        circle.setRemark(request.getRemark());
    }
}
