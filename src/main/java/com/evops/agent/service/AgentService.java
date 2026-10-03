package com.evops.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.agent.AgentEnums;
import com.evops.agent.dto.AgentCreateRequest;
import com.evops.agent.entity.Agent;
import com.evops.agent.mapper.AgentMapper;
import com.evops.common.AgentViewer;
import com.evops.common.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 代理档案域：代理编号全局唯一，绑定登录账号决定账单隔离边界。
 * 建档 / 停用 / 启用只许财务/系统；代理自己只能翻自己名下档案。
 */
@Service
public class AgentService {

    private final AgentMapper agentMapper;

    public AgentService(AgentMapper agentMapper) {
        this.agentMapper = agentMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public Agent create(AgentCreateRequest request, AgentViewer viewer) {
        viewer.requireManage();
        String name = request.getAgentName().trim();
        String login = request.getLoginUsername().trim();

        Agent agent = new Agent();
        agent.setAgentNo(generateAgentNo());
        agent.setAgentName(name);
        agent.setLoginUsername(login);
        agent.setContactName(request.getContactName());
        agent.setContactPhone(request.getContactPhone());
        agent.setStatus(AgentEnums.AGENT_ACTIVE);
        agent.setOperatorName(viewer.getUsername());
        agent.setRemark(request.getRemark());
        try {
            agentMapper.insert(agent);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("代理名称或绑定登录账号已存在（代理名与登录账号均全局唯一）：" + name + " / " + login);
        }
        return agent;
    }

    /** 停用：不参与新结算，历史账单照翻。 */
    @Transactional(rollbackFor = Exception.class)
    public Agent changeStatus(String agentNo, String status, AgentViewer viewer) {
        viewer.requireManage();
        Agent agent = requireByNo(agentNo);
        if (!AgentEnums.AGENT_ACTIVE.equals(status) && !AgentEnums.AGENT_DISABLED.equals(status)) {
            throw new BusinessException("不支持的代理状态: " + status);
        }
        agent.setStatus(status);
        agentMapper.updateById(agent);
        return agent;
    }

    /** 翻查：代理视角只见自己名下；财务/系统看全部；可按名称模糊、状态过滤。 */
    public List<Agent> list(AgentViewer viewer, String keyword, String status) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<>();
        if (viewer.isScoped()) {
            wrapper.eq(Agent::getLoginUsername, viewer.getUsername());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like(Agent::getAgentName, keyword.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(Agent::getStatus, status.trim());
        }
        return agentMapper.selectList(wrapper.orderByDesc(Agent::getId));
    }

    public Agent requireByNo(String agentNo) {
        Agent agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getAgentNo, agentNo == null ? "" : agentNo.trim()));
        if (agent == null) {
            throw new BusinessException("代理不存在: " + agentNo);
        }
        return agent;
    }

    public Agent requireById(Long id) {
        Agent agent = agentMapper.selectById(id);
        if (agent == null) {
            throw new BusinessException("代理不存在: id=" + id);
        }
        return agent;
    }

    /** 读取并做归属校验（代理只能看自己）。 */
    public Agent requireViewable(String agentNo, AgentViewer viewer) {
        Agent agent = requireByNo(agentNo);
        viewer.checkOwn(agent.getLoginUsername());
        return agent;
    }

    private String generateAgentNo() {
        long seq = agentMapper.nextAgentSeq();
        return String.format("AG%010d", seq);
    }
}
