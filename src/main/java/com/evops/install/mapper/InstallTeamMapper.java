package com.evops.install.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.install.entity.InstallTeam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InstallTeamMapper extends BaseMapper<InstallTeam> {
    @Select("SELECT NEXT VALUE FOR seq_install_team_no")
    long nextTeamSeq();

    /**
     * 对安装队行加行级悲观锁：匹配某队前先锁住它，把并发的两张单排队，
     * 避免同一时段被同时排给同一个队（配合 t_install_order 排期重叠校验双保险）。
     */
    @Select("SELECT * FROM t_install_team WHERE id = #{id} FOR UPDATE")
    InstallTeam lockById(@Param("id") Long id);
}
