package com.evops.asset.support;

import com.evops.asset.support.AssetProperties.Spec;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

/**
 * 多规格转档核心（JDK 原生 ImageIO，不引三方库）：
 * 1. 解码原图，读不出尺寸即判格式不支持；
 * 2. 按规格宽高比居中裁切（横版 16:9 / 竖版 9:16），不拉伸变形；
 * 3. 长边压进限定像素；
 * 4. 先按首轮质量出 JPEG，体积超标就再压一轮质量（每轮降一档，直至质量下限），取体积最小的一轮。
 */
@Component
public class ImageTranscoder {

    private final AssetProperties properties;

    public ImageTranscoder(AssetProperties properties) {
        this.properties = properties;
    }

    /** 解码并读出原图尺寸；读不出来说明不是受支持的图片。 */
    public SourceImage decode(byte[] data) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(data));
        } catch (IOException e) {
            throw new TranscodeFailureException("图片解码失败，文件可能已损坏", e);
        }
        if (image == null) {
            throw new TranscodeFailureException("图片格式不支持或文件已损坏（仅支持 JPG / PNG / GIF）");
        }
        return new SourceImage(image, image.getWidth(), image.getHeight());
    }

    /** 跑单个规格：裁切 + 限长边 + 多轮压质量。 */
    public Product transcode(SourceImage source, Spec spec) {
        int[] ratio = spec.ratioParts();
        BufferedImage cropped = centerCrop(source.image, ratio[0], ratio[1]);
        BufferedImage scaled = scaleToLongEdge(cropped, ratio, spec.getLongEdge());

        long maxBytes = properties.getMaxFileSize().toBytes();
        float initial = properties.getQuality().getInitial();
        float floor = properties.getQuality().getFloor();
        float decrement = properties.getQuality().getDecrement();

        byte[] best = null;
        float bestQuality = initial;
        float quality = initial;
        int round = 0;
        // 首轮 initial；超标则每轮降 decrement，压到 floor 仍超标也取最小一轮交差
        while (true) {
            round++;
            byte[] encoded = encodeJpeg(scaled, quality);
            if (best == null || encoded.length < best.length) {
                best = encoded;
                bestQuality = quality;
            }
            if (encoded.length <= maxBytes || quality <= floor + 1e-6) {
                break;
            }
            quality = Math.max(floor, quality - decrement);
        }

        Product product = new Product();
        product.bytes = best;
        product.width = scaled.getWidth();
        product.height = scaled.getHeight();
        product.fileSize = (long) best.length;
        product.qualityRound = round;
        product.finalQuality = Math.round(bestQuality * 100) / 100f;
        product.withinLimit = best.length <= maxBytes;
        product.contentType = "image/jpeg";
        return product;
    }

    /** 居中裁切到目标宽高比，多出的边居中裁掉。 */
    private BufferedImage centerCrop(BufferedImage src, int ratioW, int ratioH) {
        int w = src.getWidth();
        int h = src.getHeight();
        double target = ratioW / (double) ratioH;
        double current = w / (double) h;
        int cropW;
        int cropH;
        if (current > target) {
            // 图更“宽”，按高定宽
            cropH = h;
            cropW = (int) Math.round(h * target);
        } else {
            cropW = w;
            cropH = (int) Math.round(w / target);
        }
        cropW = Math.min(cropW, w);
        cropH = Math.min(cropH, h);
        int x = (w - cropW) / 2;
        int y = (h - cropH) / 2;
        return src.getSubimage(x, y, cropW, cropH);
    }

    /** 按比例算出常规规格的固定像素：长边就是限定值，短边按比例取整。 */
    private BufferedImage scaleToLongEdge(BufferedImage src, int[] ratio, int longEdge) {
        int targetW;
        int targetH;
        if (ratio[0] >= ratio[1]) {
            targetW = longEdge;
            targetH = (int) Math.round(longEdge * ratio[1] / (double) ratio[0]);
        } else {
            targetH = longEdge;
            targetW = (int) Math.round(longEdge * ratio[0] / (double) ratio[1]);
        }
        // 常规规格出固定尺寸（信息流渠道要标准件）：裁完的图不足也放到目标尺寸
        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, targetW, targetH);
            g.drawImage(src, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/jpeg");
        if (!writers.hasNext()) {
            throw new UncheckedIOException(new IOException("当前 JDK 没有 JPEG 编码器"));
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException("JPEG 编码失败", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** 转档失败：消息面向美工，可直接落库展示。 */
    public static class TranscodeFailureException extends BusinessException {
        public TranscodeFailureException(String message) {
            super(message);
        }

        public TranscodeFailureException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 解码后的原图。 */
    public static class SourceImage {
        public final BufferedImage image;
        public final int width;
        public final int height;

        public SourceImage(BufferedImage image, int width, int height) {
            this.image = image;
            this.width = width;
            this.height = height;
        }
    }

    /** 单个规格的转档产物。 */
    public static class Product {
        public byte[] bytes;
        public int width;
        public int height;
        public long fileSize;
        /** 第几轮质量（1 = 首轮质量即达标） */
        public int qualityRound;
        public float finalQuality;
        /** 压到质量下限是否仍超体积上限 */
        public boolean withinLimit;
        public String contentType;
    }
}
