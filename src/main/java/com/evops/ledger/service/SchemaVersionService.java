package com.evops.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.ledger.entity.SchemaVersion;
import com.evops.ledger.mapper.SchemaVersionMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 底账起步工作区的版本登记翻查（版本选择依据）。 */
@Service
public class SchemaVersionService {

    private final SchemaVersionMapper mapper;

    public SchemaVersionService(SchemaVersionMapper mapper) {
        this.mapper = mapper;
    }

    public List<SchemaVersion> list() {
        return mapper.selectList(new LambdaQueryWrapper<SchemaVersion>()
                .orderByAsc(SchemaVersion::getId));
    }
}
