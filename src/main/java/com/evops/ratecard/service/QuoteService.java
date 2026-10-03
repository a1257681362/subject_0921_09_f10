package com.evops.ratecard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.ledger.support.Operators;
import com.evops.ratecard.RateCardEnums;
import com.evops.ratecard.dto.PricedLine;
import com.evops.ratecard.dto.PricingResult;
import com.evops.ratecard.dto.QuoteDetailView;
import com.evops.ratecard.dto.QuoteSubmitRequest;
import com.evops.ratecard.entity.Quote;
import com.evops.ratecard.entity.QuoteLine;
import com.evops.ratecard.mapper.QuoteLineMapper;
import com.evops.ratecard.mapper.QuoteMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 报价：只负责把 {@link PricingEngine} 的算价结果固化成单据快照，并提供翻查。
 *
 * 不回写旧单口径：
 * - 价格、档位、税率、点位信息全部在提交当时从生效刊例算出后复制进 t_quote / t_quote_line；
 * - 之后年度刊例调价、换版、点位档案改动，都与已提交报价无关（无外键级联、无 update 路径）；
 * - 本服务只提供提交与查询，故意不提供改价/改单接口，从接口层杜绝回写。
 */
@Service
public class QuoteService {

    private final QuoteMapper quoteMapper;
    private final QuoteLineMapper lineMapper;
    private final PricingEngine pricingEngine;

    public QuoteService(QuoteMapper quoteMapper,
                        QuoteLineMapper lineMapper,
                        PricingEngine pricingEngine) {
        this.quoteMapper = quoteMapper;
        this.lineMapper = lineMapper;
        this.pricingEngine = pricingEngine;
    }

    /**
     * 提交报价：按提交当天（submitDate 留空取今天）的生效刊例统一算价，算完整批落库。
     * 提交后的报价单只读。
     */
    @Transactional(rollbackFor = Exception.class)
    public QuoteDetailView submit(QuoteSubmitRequest request) {
        LocalDate submitDate = request.getSubmitDate() != null ? request.getSubmitDate() : LocalDate.now();
        if (submitDate.isAfter(LocalDate.now())) {
            throw new BusinessException("报价日期不能晚于今天");
        }
        // 唯一计价入口：含税/未税、档位、单价都由它算，接口层不算一分钱
        PricingResult pricing = pricingEngine.price(submitDate, request.getLines());
        String operator = Operators.resolve(request.getOperatorName());

        Quote quote = new Quote();
        quote.setQuoteNo(generateQuoteNo());
        quote.setCustomerName(request.getCustomerName().trim());
        quote.setSubmitDate(submitDate);
        quote.setRateYear(pricing.getRateYear());
        quote.setRateCardId(pricing.getRateCardId());
        quote.setRateCardNo(pricing.getRateCardNo());
        quote.setVersionId(pricing.getVersionId());
        quote.setVersionNo(pricing.getVersionNo());
        quote.setTaxRate(pricing.getTaxRate());
        quote.setTotalExTax(pricing.getTotalExTax());
        quote.setTotalTax(pricing.getTotalTax());
        quote.setTotalInclTax(pricing.getTotalInclTax());
        quote.setStatus(RateCardEnums.QUOTE_SUBMITTED);
        quote.setOperatorName(operator);
        quote.setRemark(request.getRemark());
        quoteMapper.insert(quote);

        int lineNo = 0;
        for (PricedLine priced : pricing.getLines()) {
            lineNo++;
            QuoteLine line = new QuoteLine();
            line.setQuoteId(quote.getId());
            line.setLineNo(lineNo);
            line.setSiteNo(priced.getSiteNo());
            line.setSiteId(priced.getSiteId());
            line.setSizeSpec(priced.getSizeSpec());
            line.setOrientation(priced.getOrientation());
            line.setBusinessDistrict(priced.getBusinessDistrict());
            line.setWeeks(priced.getWeeks());
            line.setTierNo(priced.getTierNo());
            line.setDiscountRate(priced.getDiscountRate());
            line.setListPrice(priced.getListPrice());
            line.setUnitPrice(priced.getUnitPrice());
            line.setAmountExTax(priced.getAmountExTax());
            line.setTaxRate(priced.getTaxRate());
            line.setTaxAmount(priced.getTaxAmount());
            line.setAmountInclTax(priced.getAmountInclTax());
            lineMapper.insert(line);
        }
        return detail(quote.getQuoteNo());
    }

    /** 试算：不落库，给销售报价前预览含税/未税并列结果，同样只走 PricingEngine。 */
    public PricingResult preview(LocalDate submitDate, QuoteSubmitRequest request) {
        LocalDate date = submitDate != null ? submitDate
                : (request.getSubmitDate() != null ? request.getSubmitDate() : LocalDate.now());
        if (date.isAfter(LocalDate.now())) {
            throw new BusinessException("报价日期不能晚于今天");
        }
        return pricingEngine.price(date, request.getLines());
    }

    /** 翻查报价单（默认含税口径随单头展示，未税额同行并列）。 */
    public QuoteDetailView detail(String quoteNo) {
        Quote quote = quoteMapper.selectOne(
                new LambdaQueryWrapper<Quote>().eq(Quote::getQuoteNo, quoteNo == null ? "" : quoteNo.trim()));
        if (quote == null) {
            throw new BusinessException("报价单不存在: " + quoteNo);
        }
        List<QuoteLine> lines = lineMapper.selectList(new LambdaQueryWrapper<QuoteLine>()
                .eq(QuoteLine::getQuoteId, quote.getId())
                .orderByAsc(QuoteLine::getLineNo));
        QuoteDetailView view = new QuoteDetailView();
        view.setQuote(quote);
        view.setLines(lines);
        return view;
    }

    /** 报价单列表：可按客户/刊例年份/提交区间过滤，新单在前。 */
    public java.util.List<Quote> list(String customerName, Integer rateYear,
                                      LocalDate from, LocalDate to) {
        LambdaQueryWrapper<Quote> wrapper = new LambdaQueryWrapper<Quote>();
        if (customerName != null && !customerName.trim().isEmpty()) {
            wrapper.like(Quote::getCustomerName, customerName.trim());
        }
        if (rateYear != null) {
            wrapper.eq(Quote::getRateYear, rateYear);
        }
        if (from != null) {
            wrapper.ge(Quote::getSubmitDate, from);
        }
        if (to != null) {
            wrapper.le(Quote::getSubmitDate, to);
        }
        return quoteMapper.selectList(wrapper.orderByDesc(Quote::getId));
    }

    private String generateQuoteNo() {
        long seq = quoteMapper.nextQuoteSeq();
        return String.format("QT%010d", seq);
    }
}
