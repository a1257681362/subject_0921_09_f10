package com.evops.compliance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.compliance.entity.ComplianceFreezeBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ComplianceFreezeBatchMapper extends BaseMapper<ComplianceFreezeBatch> {
    /** 冻结批次号全局序列，并发取号不重。 */
    @Select("SELECT NEXT VALUE FOR seq_freeze_batch_no")
    long nextBatchSeq();
}
