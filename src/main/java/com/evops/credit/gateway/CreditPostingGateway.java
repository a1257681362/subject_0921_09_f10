package com.evops.credit.gateway;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.compliance.service.CategoryListService;
import com.evops.credit.entity.CreditOccupation;
import com.evops.credit.entity.Customer;
import com.evops.credit.service.CreditService;
import com.evops.credit.service.CustomerService;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.mapper.PostingOrderMapper;
import com.evops.ledger.support.PostingCreditGateway;
import com.evops.ratecard.RateCardEnums;
import com.evops.ratecard.entity.Quote;
import com.evops.ratecard.entity.QuoteLine;
import com.evops.ratecard.mapper.QuoteLineMapper;
import com.evops.ratecard.mapper.QuoteMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 授信域对上刊底账的适配器：把“引用报价单的上刊单”翻译成客户额度占用。
 * 客户与含税占用额全部从报价单快照取，金额仍只由刊例 PricingEngine 一处算出。
 */
@Service
public class CreditPostingGateway implements PostingCreditGateway {

    private final QuoteMapper quoteMapper;
    private final QuoteLineMapper quoteLineMapper;
    private final PostingOrderMapper orderMapper;
    private final CustomerService customerService;
    private final CreditService creditService;
    /** 受限行业名单域：授信上刊提交口校验客户行业类目 */
    private final CategoryListService categoryListService;

    public CreditPostingGateway(QuoteMapper quoteMapper,
                                QuoteLineMapper quoteLineMapper,
                                PostingOrderMapper orderMapper,
                                CustomerService customerService,
                                CreditService creditService,
                                CategoryListService categoryListService) {
        this.quoteMapper = quoteMapper;
        this.quoteLineMapper = quoteLineMapper;
        this.orderMapper = orderMapper;
        this.customerService = customerService;
        this.creditService = creditService;
        this.categoryListService = categoryListService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void occupyFor(PostingOrder order, String quoteNo, String operatorName) {
        String qno = quoteNo.trim();
        Quote quote = quoteMapper.selectOne(new LambdaQueryWrapper<Quote>().eq(Quote::getQuoteNo, qno));
        if (quote == null) {
            throw new BusinessException("报价单不存在: " + qno);
        }
        if (!RateCardEnums.QUOTE_SUBMITTED.equals(quote.getStatus())) {
            throw new BusinessException("报价单 " + qno + " 状态不是已提交，不能据此占用授信");
        }
        Long lineCount = quoteLineMapper.selectCount(new LambdaQueryWrapper<QuoteLine>()
                .eq(QuoteLine::getQuoteId, quote.getId()));
        if (lineCount == null || lineCount != 1) {
            throw new BusinessException("报价单 " + qno + " 含 " + (lineCount == null ? 0 : lineCount)
                    + " 行，与单点位单周上刊单不一致，请拆成单点单周的报价单后再上刊占用");
        }

        Customer customer = customerService.findByName(quote.getCustomerName());
        if (customer == null) {
            throw new BusinessException("报价单客户「" + quote.getCustomerName()
                    + "」尚未建档授信，请先客户建档给额度");
        }
        // F10 上刊提交口：客户行业类目命中受限名单当场拦（占用额度之前，失败整体回滚，单据档期都不落）
        categoryListService.requireNotRestricted(customer.getIndustryCategory(), "上刊提交");
        // 销售只能给自己名下客户上刊占额；财务/系统不受限
        Viewer.forOperator(operatorName).checkOwn(customer.getOwnerSales());

        BigDecimal amount = quote.getTotalInclTax();
        CreditOccupation occ;
        try {
            occ = creditService.occupy(order, customer, qno, amount);
        } catch (BusinessException e) {
            throw e;
        }

        // 回写授信快照到上刊单（quote_no 唯一索引兜底一报价多单）；行业类目取客户档案快照落单
        PostingOrder patch = new PostingOrder();
        patch.setId(order.getId());
        patch.setQuoteNo(qno);
        patch.setCustomerId(customer.getId());
        patch.setOccupiedAmount(occ.getAmount());
        patch.setCreditUsed(1);
        patch.setIndustryCategory(customer.getIndustryCategory());
        try {
            orderMapper.updateById(patch);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("报价单 " + qno + " 已被授信上刊单占用，一张报价单最多背一张上刊单");
        }
        order.setQuoteNo(qno);
        order.setCustomerId(customer.getId());
        order.setOccupiedAmount(occ.getAmount());
        order.setCreditUsed(1);
        order.setIndustryCategory(customer.getIndustryCategory());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseFor(PostingOrder order, boolean reversed, String operatorName) {
        creditService.release(order, reversed, operatorName);
    }
}
