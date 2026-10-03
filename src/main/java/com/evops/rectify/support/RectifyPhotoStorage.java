package com.evops.rectify.support;

import com.evops.common.BusinessException;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 留证照片本地落盘：单子/阶段/时刻-指纹 三级路径，库里只存相对路径。 */
@Component
public class RectifyPhotoStorage {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RectifyProperties properties;
    private Path root;

    public RectifyPhotoStorage(RectifyProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        root = Paths.get(properties.getStorageRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("留证照片目录建不起来: " + root, e);
        }
    }

    /** 存照片：整改单号/阶段/时刻-指纹前12位.ext；不去重，每次上传都是独立证据文件。 */
    public String store(String rectifyNo, String phase, String fingerprint, String fileName,
                        LocalDateTime at, byte[] data) {
        String ext = extensionOf(fileName);
        String relative = sanitize(rectifyNo) + "/" + phase.toLowerCase() + "/"
                + at.format(TS) + "-" + fingerprint.substring(0, 12) + ext;
        try {
            Path target = resolve(relative);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException e) {
            throw new UncheckedIOException("留证照片落盘失败: " + relative, e);
        }
        return relative;
    }

    public byte[] read(String relativePath) {
        try {
            Path target = resolve(relativePath);
            if (!Files.exists(target)) {
                throw new BusinessException("留证照片在存储里找不到了: " + relativePath);
            }
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new UncheckedIOException("读取留证照片失败: " + relativePath, e);
        }
    }

    /** 删除磁盘文件（仅单子关闭后允许走到这里）；文件已不在不当成错误。 */
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("删除留证照片失败: " + relativePath, e);
        }
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return ".jpg";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1 || fileName.length() - 1 - dot > 8) {
            return ".jpg";
        }
        String ext = fileName.substring(dot).toLowerCase().replaceAll("[^a-z0-9.]", "");
        return ext.isEmpty() ? ".jpg" : ext;
    }

    private String sanitize(String part) {
        return part.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private Path resolve(String relative) {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("非法的留证照片存储路径");
        }
        return target;
    }
}
