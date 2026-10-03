package com.evops.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.asset.entity.Asset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetMapper extends BaseMapper<Asset> {
    @Select("SELECT NEXT VALUE FOR seq_asset_no")
    long nextAssetSeq();
}
