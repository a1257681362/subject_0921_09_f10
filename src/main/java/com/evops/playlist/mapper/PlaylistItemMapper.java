package com.evops.playlist.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.playlist.entity.PlaylistItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlaylistItemMapper extends BaseMapper<PlaylistItem> {
}
