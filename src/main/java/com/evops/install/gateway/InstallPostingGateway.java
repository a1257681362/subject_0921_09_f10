package com.evops.install.gateway;

import com.evops.install.service.InstallDispatchService;
import com.evops.ledger.entity.PostingOrder;
import com.evops.ledger.support.PostingInstallGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 安装派单域对上刊底账的适配器：把「上刊单确认」翻译成自动生成安装派单，
 * 「上刊单对冲」翻译成联动取消未完工派单。底账域经 ObjectProvider 取本实现，取不到则保持 F1 原样。
 */
@Service
public class InstallPostingGateway implements PostingInstallGateway {

    private final InstallDispatchService dispatchService;

    public InstallPostingGateway(InstallDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onPostingConfirmed(PostingOrder order, String operatorName) {
        dispatchService.handlePostingConfirmed(order, operatorName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onPostingOffset(PostingOrder original, String operatorName) {
        dispatchService.handlePostingOffset(original, operatorName);
    }
}
