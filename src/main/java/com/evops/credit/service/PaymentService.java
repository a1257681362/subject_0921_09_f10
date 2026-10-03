package com.evops.credit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BusinessException;
import com.evops.common.Viewer;
import com.evops.credit.CreditEnums;
import com.evops.credit.dto.PaymentDetailView;
import com.evops.credit.dto.PaymentRegisterRequest;
import com.evops.credit.dto.WriteoffRequest;
import com.evops.credit.entity.CreditOccupation;
import com.evops.credit.entity.Customer;
import com.evops.credit.entity.Payment;
import com.evops.credit.entity.PaymentWriteoff;
import com.evops.credit.mapper.CreditOccupationMapper;
import com.evops.credit.mapper.CustomerMapper;
import com.evops.credit.mapper.PaymentMapper;
import com.evops.credit.mapper.PaymentWriteoffMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 回款与核销域：回款先登记成凭证，再按单核销；
 * 支持一笔回款分次核销多张单、一张单被多笔回款分次核销（部分回款分次核）。
 *
 * 超额防护全部走数据库条件更新：回款已核不超实收、单据已核不超单据额，并发也不会核穿。
 * 挂起（ACTIVE）中的单据回款核销，等额实时释放客户占用；落架转应收（RELEASED）后核销只结应收、不动额度。
 */
@Service
public class PaymentService {

    private final PaymentMapper paymentMapper;
    private final PaymentWriteoffMapper writeoffMapper;
    private final CreditOccupationMapper occupationMapper;
    private final CustomerMapper customerMapper;
    private final CustomerService customerService;
    private final CreditService creditService;

    public PaymentService(PaymentMapper paymentMapper,
                          PaymentWriteoffMapper writeoffMapper,
                          CreditOccupationMapper occupationMapper,
                          CustomerMapper customerMapper,
                          CustomerService customerService,
                          CreditService creditService) {
        this.paymentMapper = paymentMapper;
        this.writeoffMapper = writeoffMapper;
        this.occupationMapper = occupationMapper;
        this.customerMapper = customerMapper;
        this.customerService = customerService;
        this.creditService = creditService;
    }

    /** 回款登记：一笔回款一张凭证，登记后处于待核销，不立即动额度。 */
    @Transactional(rollbackFor = Exception.class)
    public Payment register(PaymentRegisterRequest request, Viewer viewer) {
        Customer customer = customerService.requireViewable(request.getCustomerNo(), viewer);
        BigDecimal amount = CustomerService.normalizeMoney(request.getReceivedAmount());
        LocalDate date = request.getReceivedDate() != null ? request.getReceivedDate() : LocalDate.now();
        if (date.isAfter(LocalDate.now())) {
            throw new BusinessException("回款到账日期不能晚于今天");
        }
        String voucher = request.getVoucherNo() == null ? null : request.getVoucherNo().trim();
        if (voucher != null && !voucher.isEmpty()) {
            Long dup = paymentMapper.selectCount(new LambdaQueryWrapper<Payment>()
                    .eq(Payment::getVoucherNo, voucher));
            if (dup != null && dup > 0) {
                throw new BusinessException("银行流水/收据号 " + voucher + " 已登记过回款，不能重复登记");
            }
        }

        Payment payment = new Payment();
        payment.setPaymentNo(generatePaymentNo());
        payment.setCustomerId(customer.getId());
        payment.setCustomerName(customer.getCustomerName());
        payment.setReceivedAmount(amount);
        payment.setAllocatedAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        payment.setReceivedDate(date);
        payment.setVoucherNo(voucher);
        payment.setPayerAccount(request.getPayerAccount());
        payment.setStatus(CreditEnums.PAYMENT_RECEIVED);
        payment.setOperatorName(viewer.getUsername());
        payment.setRemark(request.getRemark());
        paymentMapper.insert(payment);
        return payment;
    }

    /** 按单核销（支持分次）。 */
    @Transactional(rollbackFor = Exception.class)
    public PaymentDetailView writeoff(WriteoffRequest request, Viewer viewer) {
        BigDecimal amount = CustomerService.normalizeMoney(request.getAmount());
        CreditOccupation occ = creditService.requireByOrderNo(request.getOrderNo());
        Customer customer = customerService.requireOwned(occ.getCustomerId(), viewer);
        if (CreditEnums.OCC_REVERSED.equals(occ.getStatus())) {
            throw new BusinessException("单据 " + occ.getOrderNo() + " 已对冲更正，不形成应收，不能核销");
        }
        BigDecimal outstanding = occ.getAmount().subtract(occ.getSettledAmount());
        if (amount.compareTo(outstanding) > 0) {
            throw new BusinessException("本次核销 " + amount.toPlainString() + " 超过单据 "
                    + occ.getOrderNo() + " 未核销额 " + outstanding.toPlainString());
        }

        Payment payment;
        if (request.getPaymentNo() != null && !request.getPaymentNo().trim().isEmpty()) {
            payment = requirePayment(request.getPaymentNo());
            if (!payment.getCustomerId().equals(customer.getId())) {
                throw new BusinessException("回款凭证 " + payment.getPaymentNo()
                        + " 不属于单据客户「" + customer.getCustomerName() + "」");
            }
        } else {
            payment = autoPickPayment(customer.getId(), amount);
        }
        BigDecimal paymentRemaining = payment.getReceivedAmount().subtract(payment.getAllocatedAmount());
        if (amount.compareTo(paymentRemaining) > 0) {
            throw new BusinessException("回款 " + payment.getPaymentNo() + " 剩余可核 "
                    + paymentRemaining.toPlainString() + "，不足本次核销 " + amount.toPlainString());
        }

        LocalDate date = request.getWriteoffDate() != null ? request.getWriteoffDate() : LocalDate.now();
        if (date.isAfter(LocalDate.now())) {
            throw new BusinessException("核销日期不能晚于今天");
        }

        // 回款已核额条件更新：allocated+本次<=实收，并发超额由它兜底
        int payUpdated = paymentMapper.update(null, new LambdaUpdateWrapper<Payment>()
                .eq(Payment::getId, payment.getId())
                .apply("allocated_amount + {0} <= received_amount", amount)
                .setSql("allocated_amount = allocated_amount + " + amount.toPlainString()));
        if (payUpdated == 0) {
            throw new BusinessException("回款 " + payment.getPaymentNo() + " 可核余额刚被占用，请刷新后重试");
        }

        // 单据已核额条件更新：settled+本次<=单据额
        int occUpdated = occupationMapper.update(null, new LambdaUpdateWrapper<CreditOccupation>()
                .eq(CreditOccupation::getId, occ.getId())
                .ne(CreditOccupation::getStatus, CreditEnums.OCC_REVERSED)
                .apply("settled_amount + {0} <= amount", amount)
                .setSql("settled_amount = settled_amount + " + amount.toPlainString()));
        if (occUpdated == 0) {
            throw new BusinessException("单据 " + occ.getOrderNo() + " 可核余额刚变化，请刷新后重试");
        }

        // 挂起中回款：钱回来了，等额释放占用；转应收后的核销不再动额度
        if (CreditEnums.OCC_ACTIVE.equals(occ.getStatus())) {
            int custUpdated = customerMapper.update(null, new LambdaUpdateWrapper<Customer>()
                    .eq(Customer::getId, customer.getId())
                    .apply("occupied_amount - {0} >= 0", amount)
                    .setSql("occupied_amount = occupied_amount - " + amount.toPlainString()));
            if (custUpdated == 0) {
                throw new BusinessException("客户占用额异常，回款释放失败：单据 " + occ.getOrderNo());
            }
        }

        PaymentWriteoff row = new PaymentWriteoff();
        row.setPaymentId(payment.getId());
        row.setPaymentNo(payment.getPaymentNo());
        row.setCustomerId(customer.getId());
        row.setOccupationId(occ.getId());
        row.setOrderId(occ.getOrderId());
        row.setOrderNo(occ.getOrderNo());
        row.setAmount(amount);
        row.setWriteoffDate(date);
        row.setOperatorName(viewer.getUsername());
        row.setRemark(request.getRemark());
        writeoffMapper.insert(row);

        settleDerivedStatuses(payment.getId(), occ.getId());
        return detail(payment.getPaymentNo());
    }

    /** 重算凭证与占用单的派生状态（SETTLED/PARTIAL、已结清/部分核销）。 */
    private void settleDerivedStatuses(Long paymentId, Long occupationId) {
        Payment payment = paymentMapper.selectById(paymentId);
        String payStatus = payment.getAllocatedAmount().compareTo(payment.getReceivedAmount()) >= 0
                ? CreditEnums.PAYMENT_SETTLED : CreditEnums.PAYMENT_PARTIAL;
        Payment pPatch = new Payment();
        pPatch.setId(paymentId);
        pPatch.setStatus(payStatus);
        paymentMapper.updateById(pPatch);

        CreditOccupation occ = occupationMapper.selectById(occupationId);
        String settleStatus;
        int cmp = occ.getSettledAmount().compareTo(occ.getAmount());
        if (cmp >= 0) {
            settleStatus = CreditEnums.SETTLE_SETTLED;
        } else if (occ.getSettledAmount().signum() > 0) {
            settleStatus = CreditEnums.SETTLE_PARTIAL;
        } else {
            settleStatus = CreditEnums.SETTLE_UNSETTLED;
        }
        CreditOccupation oPatch = new CreditOccupation();
        oPatch.setId(occupationId);
        oPatch.setSettleStatus(settleStatus);
        occupationMapper.updateById(oPatch);
    }

    /** 自动挑该客户最早一笔余额足够的未核销完回款。 */
    private Payment autoPickPayment(Long customerId, BigDecimal amount) {
        List<Payment> candidates = paymentMapper.selectList(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getCustomerId, customerId)
                .ne(Payment::getStatus, CreditEnums.PAYMENT_SETTLED)
                .orderByAsc(Payment::getReceivedDate)
                .orderByAsc(Payment::getId));
        for (Payment p : candidates) {
            if (p.getReceivedAmount().subtract(p.getAllocatedAmount()).compareTo(amount) >= 0) {
                return p;
            }
        }
        throw new BusinessException("客户没有余额足够的未核销完回款，请先登记回款或指定回款凭证号");
    }

    public Payment requirePayment(String paymentNo) {
        Payment payment = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getPaymentNo, paymentNo == null ? "" : paymentNo.trim()));
        if (payment == null) {
            throw new BusinessException("回款凭证不存在: " + paymentNo);
        }
        return payment;
    }

    public PaymentDetailView detail(String paymentNo) {
        Payment payment = requirePayment(paymentNo);
        List<PaymentWriteoff> rows = writeoffMapper.selectList(new LambdaQueryWrapper<PaymentWriteoff>()
                .eq(PaymentWriteoff::getPaymentId, payment.getId())
                .orderByAsc(PaymentWriteoff::getId));
        PaymentDetailView view = new PaymentDetailView();
        view.setPayment(payment);
        view.setWriteoffs(rows);
        return view;
    }

    /** 回款翻查：销售限名下；可按客户/到账区间过滤。 */
    public List<Payment> list(Viewer viewer, String customerNo, LocalDate from, LocalDate to) {
        LambdaQueryWrapper<Payment> wrapper = new LambdaQueryWrapper<>();
        if (viewer.isScoped()) {
            List<Long> owned = customerService.list(viewer, null, null).stream()
                    .map(Customer::getId).collect(java.util.stream.Collectors.toList());
            if (owned.isEmpty()) {
                wrapper.apply("1 = 0"); // 名下没有客户，查不出任何回款
            } else {
                wrapper.in(Payment::getCustomerId, owned);
            }
        }
        if (customerNo != null && !customerNo.trim().isEmpty()) {
            Customer customer = customerService.requireViewable(customerNo, viewer);
            wrapper.eq(Payment::getCustomerId, customer.getId());
        }
        if (from != null) {
            wrapper.ge(Payment::getReceivedDate, from);
        }
        if (to != null) {
            wrapper.le(Payment::getReceivedDate, to);
        }
        return paymentMapper.selectList(wrapper.orderByDesc(Payment::getId));
    }

    private String generatePaymentNo() {
        long seq = paymentMapper.nextPaymentSeq();
        return String.format("RCV%010d", seq);
    }
}
