package com.evops.playlist.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.playlist.entity.PlaylistEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlaylistEventMapper extends BaseMapper<PlaylistEvent> {
}
