package com.evops.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.asset.entity.TranscodeJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TranscodeJobMapper extends BaseMapper<TranscodeJob> {
    @Select("SELECT NEXT VALUE FOR seq_transcode_job_no")
    long nextJobSeq();
}
