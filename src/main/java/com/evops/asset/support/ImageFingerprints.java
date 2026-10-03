package com.evops.asset.support;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 文件指纹：同一张图重复上传按 SHA-256 认出来。 */
public final class ImageFingerprints {

    private ImageFingerprints() {}

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
