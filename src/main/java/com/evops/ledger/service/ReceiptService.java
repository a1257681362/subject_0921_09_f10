package com.evops.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.ledger.dto.ReceiptRequest;
import com.evops.ledger.entity.OrderReceipt;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.mapper.OrderReceiptMapper;
import com.evops.ledger.support.Operators;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** 外部回执：挂回原单据；给对冲单号也回溯到根单。 */
@Service
public class ReceiptService {

    private final OrderReceiptMapper receiptMapper;
    private final OrderService orderService;

    public ReceiptService(OrderReceiptMapper receiptMapper, OrderService orderService) {
        this.receiptMapper = receiptMapper;
        this.orderService = orderService;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderReceipt attach(ReceiptRequest request) {
        PostingOrder target = orderService.requireByNo(request.getOrderNo());
        // 回溯根单：无论回执指向原单还是对冲单，都挂到同一条根链
        PostingOrder root = target.getRootOrderId() != null && target.getRootOrderId().equals(target.getId())
                ? target
                : orderService.requireById(target.getRootOrderId());

        OrderReceipt receipt = new OrderReceipt();
        receipt.setReceiptNo(request.getReceiptNo().trim());
        receipt.setOrderId(target.getId());
        receipt.setOrderNo(target.getOrderNo());
        receipt.setRootOrderId(root.getId());
        receipt.setRootOrderNo(root.getOrderNo());
        receipt.setSource(request.getSource());
        receipt.setContent(request.getContent());
        receipt.setOccurDate(request.getOccurDate() != null ? request.getOccurDate() : LocalDate.now());
        receipt.setOperatorName(Operators.resolve(request.getOperatorName()));
        try {
            receiptMapper.insert(receipt);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("回执编号 " + receipt.getReceiptNo() + " 已存在");
        }
        return receipt;
    }

    /** 按根单链翻回执（原单 + 对冲单的回执一起看）。 */
    public List<OrderReceipt> listByRoot(Long rootOrderId) {
        return receiptMapper.selectList(new LambdaQueryWrapper<OrderReceipt>()
                .eq(OrderReceipt::getRootOrderId, rootOrderId)
                .orderByAsc(OrderReceipt::getId));
    }

    public List<OrderReceipt> listByOrderNo(String orderNo) {
        PostingOrder order = orderService.requireByNo(orderNo);
        return receiptMapper.selectList(new LambdaQueryWrapper<OrderReceipt>()
                .eq(OrderReceipt::getOrderId, order.getId())
                .orderByAsc(OrderReceipt::getId));
    }
}
