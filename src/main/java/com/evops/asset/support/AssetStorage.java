package com.evops.asset.support;

import com.evops.common.BusinessException;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 原图 / 转档产物本地落盘。库里只存相对路径，读取时拼回根目录。 */
@Component
public class AssetStorage {

    private final AssetProperties properties;
    private Path root;

    public AssetStorage(AssetProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        root = Paths.get(properties.getStorageRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("素材存储目录建不起来: " + root, e);
        }
    }

    /** 原图：按指纹落一份，重复上传直接覆盖同一路径（内容相同）。 */
    public String storeOriginal(String fingerprint, String ext, byte[] data) {
        String relative = "original/" + fingerprint + "." + ext;
        write(relative, data);
        return relative;
    }

    /** 规格产物：素材/版本/规格 三级路径，重跑覆盖旧文件。 */
    public String storeProduct(Long assetId, int versionNo, String specName, byte[] data) {
        String relative = "products/" + assetId + "/v" + versionNo + "/" + specName + ".jpg";
        write(relative, data);
        return relative;
    }

    public byte[] read(String relativePath) {
        try {
            Path target = resolve(relativePath);
            if (!Files.exists(target)) {
                throw new BusinessException("素材文件在存储里找不到了: " + relativePath);
            }
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new UncheckedIOException("读取素材文件失败: " + relativePath, e);
        }
    }

    private void write(String relative, byte[] data) {
        try {
            Path target = resolve(relative);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException e) {
            throw new UncheckedIOException("素材落盘失败: " + relative, e);
        }
    }

    private Path resolve(String relative) {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("非法的素材存储路径");
        }
        return target;
    }
}
