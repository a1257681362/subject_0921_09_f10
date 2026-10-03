package com.evops.playlist.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.playlist.entity.Playlist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlaylistMapper extends BaseMapper<Playlist> {
    @Select("SELECT NEXT VALUE FOR seq_playlist_no")
    long nextPlaylistSeq();
}
