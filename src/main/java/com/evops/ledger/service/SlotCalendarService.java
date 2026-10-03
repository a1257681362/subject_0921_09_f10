package com.evops.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.ledger.LedgerEnums;
import com.evops.ledger.entity.Site;
import com.evops.ledger.entity.SlotBooking;
import com.evops.ledger.mapper.SiteMapper;
import com.evops.ledger.mapper.SlotBookingMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 档期翻查：给定月份，按周（与该月有交集的周）列出每块屏是空是占、挂的谁。 */
@Service
public class SlotCalendarService {

    private final SiteMapper siteMapper;
    private final SlotBookingMapper slotMapper;

    public SlotCalendarService(SiteMapper siteMapper, SlotBookingMapper slotMapper) {
        this.siteMapper = siteMapper;
        this.slotMapper = slotMapper;
    }

    public Map<String, Object> monthly(String period, String businessDistrict) {
        YearMonth ym = YearMonth.parse(period);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        // 与本月有交集的 ISO 周（周一可能落在上月、周日可能落在下月）
        LocalDate firstMonday = monthStart.minusDays((monthStart.getDayOfWeek().getValue() - 1));
        LocalDate cursor = firstMonday;
        List<LocalDate> weekStarts = new ArrayList<>();
        while (!cursor.isAfter(monthEnd)) {
            weekStarts.add(cursor);
            cursor = cursor.plusWeeks(1);
        }

        LambdaQueryWrapper<Site> siteWrapper = new LambdaQueryWrapper<>();
        if (businessDistrict != null && !businessDistrict.trim().isEmpty()) {
            siteWrapper.like(Site::getBusinessDistrict, businessDistrict.trim());
        }
        siteWrapper.orderByAsc(Site::getSiteNo);
        List<Site> sites = siteMapper.selectList(siteWrapper);

        List<SlotBooking> bookings = slotMapper.selectList(new LambdaQueryWrapper<SlotBooking>()
                .in(SlotBooking::getWeekStart, weekStarts));
        Map<String, SlotBooking> bookingMap = new HashMap<>();
        for (SlotBooking booking : bookings) {
            bookingMap.put(booking.getSiteId() + "|" + booking.getWeekStart(), booking);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Site site : sites) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("siteId", site.getId());
            row.put("siteNo", site.getSiteNo());
            row.put("sizeSpec", site.getSizeSpec());
            row.put("orientation", site.getOrientation());
            row.put("businessDistrict", site.getBusinessDistrict());
            List<Map<String, Object>> weeks = new ArrayList<>();
            for (LocalDate weekStart : weekStarts) {
                SlotBooking booking = bookingMap.get(site.getId() + "|" + weekStart);
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("weekStart", weekStart);
                cell.put("weekEnd", weekStart.plusDays(6));
                if (booking != null && LedgerEnums.SLOT_ACTIVE.equals(booking.getStatus())) {
                    cell.put("occupied", true);
                    cell.put("orderNo", booking.getOrderNo());
                } else {
                    cell.put("occupied", false);
                }
                weeks.add(cell);
            }
            row.put("weeks", weeks);
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("weekStarts", weekStarts);
        result.put("sites", rows);
        return result;
    }

    /** 某块屏逐周档期占用流水。 */
    public List<SlotBooking> siteTimeline(String siteNo) {
        Site site = siteMapper.selectOne(new LambdaQueryWrapper<Site>()
                .eq(Site::getSiteNo, siteNo.trim()));
        if (site == null) {
            throw new com.evops.common.BusinessException("点位不存在: " + siteNo);
        }
        return slotMapper.selectList(new LambdaQueryWrapper<SlotBooking>()
                .eq(SlotBooking::getSiteId, site.getId())
                .orderByAsc(SlotBooking::getWeekStart));
    }
}
