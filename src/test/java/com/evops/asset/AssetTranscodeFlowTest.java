package com.evops.asset;

import com.evops.asset.dto.UploadResult;
import com.evops.asset.entity.Asset;
import com.evops.asset.entity.AssetFile;
import com.evops.asset.entity.AssetVersion;
import com.evops.asset.entity.TranscodeJob;
import com.evops.asset.service.AssetService;
import com.evops.asset.service.TranscodeService;
import com.evops.asset.support.AssetProperties;
import com.evops.common.BusinessException;
import com.evops.ledger.service.SchemaVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** F2 素材流水与多规格转档端到端测试（真实 H2 + 真实异步线程 + 真实 ImageIO 转档落盘）。 */
@SpringBootTest
class AssetTranscodeFlowTest {

    @Autowired private AssetService assetService;
    @Autowired private TranscodeService transcodeService;
    @Autowired private AssetProperties properties;
    @Autowired private SchemaVersionService schemaVersionService;

    /** 等到转档任务进入终态（异步线程池真实执行）。 */
    private TranscodeJob awaitTerminal(Long jobId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            TranscodeJob job = transcodeService.requireJob(jobId);
            if (!AssetEnums.JOB_PENDING.equals(job.getStatus())
                    && !AssetEnums.JOB_PROCESSING.equals(job.getStatus())) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("转档任务迟迟没有结束: jobId=" + jobId);
    }

    private byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 逐像素随机噪声：JPEG 最难压的高熵图，用来逼出二轮质量压缩。 */
    private BufferedImage noise(int w, int h, long seed) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        return img;
    }

    private MockMultipartFile pngFile(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "image/png", bytes);
    }

    @Test
    void 同一张图重复上传按指纹认出_不另开档_不重跑转档() throws Exception {
        byte[] bytes = png(noise(1600, 1200, 1));
        UploadResult first = assetService.upload(pngFile("banner-a.png", bytes), "tester", null);
        assertFalse(first.isDeduped());
        awaitTerminal(first.getJob().getId());
        Long assetId = first.getAsset().getId();

        // 同一份内容换个文件名再传：指纹一致
        UploadResult again = assetService.upload(pngFile("banner-b-改名.png", bytes), "tester", null);
        assertTrue(again.isDeduped());
        assertEquals(assetId, again.getAsset().getId());

        Asset reloaded = assetService.requireById(assetId);
        assertEquals(2, reloaded.getUploadCount());
        // 全库按这个编号只有一份档案
        List<Asset> rows = assetService.search(reloaded.getAssetNo(), null);
        assertEquals(1, rows.size());
        // 重复上传没有另起转档版本
        assertEquals(1, assetService.listVersions(assetId).size());
    }

    @Test
    void 横竖两版常规规格自动出_长边压进限定像素() throws Exception {
        // 超宽原图：两版都要自动产出，横版 1280x720，竖版 720x1280，长边都不超 1280
        byte[] bytes = png(noise(2000, 1000, 2));
        UploadResult result = assetService.upload(pngFile("wide.png", bytes), "tester", null);
        TranscodeJob job = awaitTerminal(result.getJob().getId());

        assertEquals(AssetEnums.JOB_SUCCESS, job.getStatus());
        assertEquals(2, job.getSuccessCount());
        Long versionId = job.getVersionId();
        Map<String, AssetFile> files = assetService.listFiles(versionId).stream()
                .collect(Collectors.toMap(AssetFile::getUsage, f -> f));

        AssetFile landscape = files.get("feed_landscape");
        AssetFile portrait = files.get("feed_portrait");
        assertNotNull(landscape);
        assertNotNull(portrait);
        assertEquals(1280, landscape.getWidth());
        assertEquals(720, landscape.getHeight());
        assertTrue(landscape.getLongEdge() <= 1280);
        assertEquals(720, portrait.getWidth());
        assertEquals(1280, portrait.getHeight());
        assertTrue(portrait.getLongEdge() <= 1280);
        // 产物文件真实落盘且能读回
        assertTrue(assetService.readProduct(landscape).length > 0);

        AssetVersion version = assetService.listVersions(result.getAsset().getId()).get(0);
        assertEquals(AssetEnums.JOB_SUCCESS, version.getTranscodeStatus());
        assertEquals("LANDSCAPE", result.getAsset().getOrientation());
    }

    @Test
    void 常规规格出固定标准尺寸_小图也补齐为规格尺寸() throws Exception {
        byte[] bytes = png(noise(640, 360, 3));
        UploadResult result = assetService.upload(pngFile("small.png", bytes), "tester", null);
        TranscodeJob job = awaitTerminal(result.getJob().getId());
        assertEquals(AssetEnums.JOB_SUCCESS, job.getStatus());
        Map<String, AssetFile> files = assetService.listFiles(job.getVersionId()).stream()
                .collect(Collectors.toMap(AssetFile::getUsage, f -> f));
        // 无论原图多大，常规规格都是标准件：横版 1280x720、竖版 720x1280
        AssetFile landscape = files.get("feed_landscape");
        AssetFile portrait = files.get("feed_portrait");
        assertEquals(1280, landscape.getWidth());
        assertEquals(720, landscape.getHeight());
        assertEquals(720, portrait.getWidth());
        assertEquals(1280, portrait.getHeight());
    }

    @Test
    void 体积超标自动再压一轮质量() throws Exception {
        float initial = properties.getQuality().getInitial();
        float floor = properties.getQuality().getFloor();
        // 临时把体积上限压到 1 字节：任何产物都超标，必须一轮轮降质量直到下限，稳定可复现
        org.springframework.util.unit.DataSize originLimit = properties.getMaxFileSize();
        properties.setMaxFileSize(org.springframework.util.unit.DataSize.ofBytes(1));
        try {
            byte[] bytes = png(noise(1280, 720, 42));
            UploadResult result = assetService.upload(pngFile("noisy.png", bytes), "tester", null);
            TranscodeJob job = awaitTerminal(result.getJob().getId());
            // 压到下限仍超标不算任务失败：按体积最小一轮交差，任务整体成功
            assertEquals(AssetEnums.JOB_SUCCESS, job.getStatus());

            AssetFile landscape = assetService.listFiles(job.getVersionId()).stream()
                    .filter(f -> "feed_landscape".equals(f.getUsage())).findFirst().orElseThrow(AssertionError::new);
            assertTrue(landscape.getQualityRound() >= 2, "上限 1 字节必须多轮压缩，实际轮次=" + landscape.getQualityRound());
            assertTrue(landscape.getFinalQuality() < initial, "最终质量应低于首轮 " + initial);
            assertEquals(floor, landscape.getFinalQuality(), 0.001, "应一直压到质量下限");
            assertTrue(landscape.getFileSize() > 1, "下限仍压不进时按最小一轮交差");
        } finally {
            properties.setMaxFileSize(originLimit);
        }
    }

    @Test
    void 上传损坏图片当场告知原因() {
        byte[] junk = "这压根不是图片".getBytes();
        MockMultipartFile bad = new MockMultipartFile("file", "bad.jpg", "image/jpeg", junk);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> assetService.upload(bad, "tester", null));
        assertTrue(ex.getMessage().contains("格式不支持") || ex.getMessage().contains("损坏"));
    }

    @Test
    void 后台转档失败原因落库可见_重新转档另起版本不抹失败历史() throws Exception {
        byte[] bytes = png(noise(1600, 900, 7));
        UploadResult result = assetService.upload(pngFile("ok.png", bytes), "tester", null);
        awaitTerminal(result.getJob().getId());
        Asset asset = result.getAsset();

        // 把落盘原图换成坏数据，模拟后台处理时原图读不出（v1 已成功不受影响）
        Path original = Paths.get(properties.getStorageRoot()).resolve(asset.getStoragePath());
        byte[] backup = Files.readAllBytes(original);
        Files.write(original, "broken-bytes".getBytes());

        TranscodeJob failedJob = assetService.retry(asset.getId(), "tester");
        TranscodeJob failed = awaitTerminal(failedJob.getId());
        assertEquals(AssetEnums.JOB_FAILED, failed.getStatus());
        assertNotNull(failed.getFailReason());
        assertTrue(failed.getFailReason().contains("格式不支持") || failed.getFailReason().contains("损坏")
                || failed.getFailReason().contains("解码"));
        // 失败原因同样挂在版本上，美工翻档案看得见
        AssetVersion failedVersion = assetService.listVersions(asset.getId()).stream()
                .filter(v -> v.getId().equals(failed.getVersionId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(AssetEnums.JOB_FAILED, failedVersion.getTranscodeStatus());
        assertNotNull(failedVersion.getFailReason());

        // 原图恢复后再跑一轮：新版本成功，失败的历史版本仍在
        Files.write(original, backup);
        TranscodeJob okJob = assetService.retry(asset.getId(), "tester");
        TranscodeJob ok = awaitTerminal(okJob.getId());
        assertEquals(AssetEnums.JOB_SUCCESS, ok.getStatus());

        List<AssetVersion> versions = assetService.listVersions(asset.getId());
        assertEquals(3, versions.size());
        assertEquals(AssetEnums.JOB_SUCCESS, versions.get(0).getTranscodeStatus());
        assertEquals(AssetEnums.JOB_FAILED, versions.get(1).getTranscodeStatus());
        assertEquals(AssetEnums.JOB_SUCCESS, versions.get(2).getTranscodeStatus());
        // 成功版本产出齐全
        assertEquals(2, assetService.listFiles(ok.getVersionId()).size());
        // 最新版本指针指到成功版本
        assertEquals(ok.getVersionId(), assetService.requireById(asset.getId()).getLatestVersionId());
    }

    @Test
    void 底账版本登记包含f2素材转档版本() {
        List<String> versions = schemaVersionService.list().stream()
                .map(v -> v.getVersionNo()).collect(Collectors.toList());
        assertTrue(versions.contains("f2-asset-transcode-1"));
    }
}
