package com.evops.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.asset.AssetEnums;
import com.evops.asset.entity.Asset;
import com.evops.asset.entity.AssetFile;
import com.evops.asset.entity.AssetVersion;
import com.evops.asset.entity.TranscodeJob;
import com.evops.asset.mapper.AssetFileMapper;
import com.evops.asset.mapper.AssetMapper;
import com.evops.asset.mapper.AssetVersionMapper;
import com.evops.asset.mapper.TranscodeJobMapper;
import com.evops.asset.support.AssetProperties;
import com.evops.asset.support.AssetStorage;
import com.evops.asset.support.ImageTranscoder;
import com.evops.asset.support.ImageTranscoder.Product;
import com.evops.asset.support.ImageTranscoder.SourceImage;
import com.evops.asset.support.AssetProperties.Spec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 转档任务编排：建任务（PENDING）→ 后台处理（PROCESSING）→ 成功出齐规格 / 失败落原因（FAILED）。
 * 转档状态与失败原因都落库，美工轮询任务号即可看到，不会干等。
 */
@Service
public class TranscodeService {

    private static final Logger log = LoggerFactory.getLogger(TranscodeService.class);
    private static final int REASON_MAX = 900;

    private final TranscodeJobMapper jobMapper;
    private final AssetVersionMapper versionMapper;
    private final AssetFileMapper fileMapper;
    private final AssetMapper assetMapper;
    private final AssetStorage storage;
    private final ImageTranscoder transcoder;
    private final AssetProperties properties;

    public TranscodeService(TranscodeJobMapper jobMapper, AssetVersionMapper versionMapper,
                            AssetFileMapper fileMapper, AssetMapper assetMapper,
                            AssetStorage storage, ImageTranscoder transcoder,
                            AssetProperties properties) {
        this.jobMapper = jobMapper;
        this.versionMapper = versionMapper;
        this.fileMapper = fileMapper;
        this.assetMapper = assetMapper;
        this.storage = storage;
        this.transcoder = transcoder;
        this.properties = properties;
    }

    /** 建一版转档版本和对应任务。随外层事务提交，状态 PENDING。 */
    @Transactional(rollbackFor = Exception.class)
    public TranscodeJob startJob(Asset asset, int versionNo, String source, String operator) {
        AssetVersion version = new AssetVersion();
        version.setAssetId(asset.getId());
        version.setVersionNo(versionNo);
        version.setSource(source);
        version.setFileName(asset.getFileName());
        version.setFileSize(asset.getFileSize());
        version.setWidth(asset.getWidth());
        version.setHeight(asset.getHeight());
        version.setStoragePath(asset.getStoragePath());
        version.setTranscodeStatus(AssetEnums.JOB_PENDING);
        version.setOperatorName(operator);
        versionMapper.insert(version);

        TranscodeJob job = new TranscodeJob();
        job.setJobNo(String.format("TJ%010d", jobMapper.nextJobSeq()));
        job.setAssetId(asset.getId());
        job.setVersionId(version.getId());
        job.setStatus(AssetEnums.JOB_PENDING);
        StringBuilder specNames = new StringBuilder();
        for (Spec spec : properties.getSpecs()) {
            if (specNames.length() > 0) {
                specNames.append(",");
            }
            specNames.append(spec.getName());
        }
        job.setSpecs(specNames.toString());
        job.setSuccessCount(0);
        job.setOperatorName(operator);
        jobMapper.insert(job);
        return job;
    }

    /**
     * 后台执行转档。仅 PENDING 任务能抢进 PROCESSING（条件更新兜底并发/重复执行）。
     * 任何失败都落 FAILED + 原因，不抛出线程。
     */
    public void processJob(Long jobId) {
        TranscodeJob job = jobMapper.selectById(jobId);
        if (job == null || !AssetEnums.JOB_PENDING.equals(job.getStatus())) {
            return;
        }
        int claimed = jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                .eq(TranscodeJob::getId, jobId)
                .eq(TranscodeJob::getStatus, AssetEnums.JOB_PENDING)
                .set(TranscodeJob::getStatus, AssetEnums.JOB_PROCESSING));
        if (claimed == 0) {
            return;
        }
        AssetVersion version = versionMapper.selectById(job.getVersionId());
        markVersion(version.getId(), AssetEnums.JOB_PROCESSING, null, version.getWidth(), version.getHeight());

        try {
            Asset asset = assetMapper.selectById(job.getAssetId());
            byte[] original = storage.read(version.getStoragePath());
            SourceImage source = transcoder.decode(original);

            int success = 0;
            for (Spec spec : properties.getSpecs()) {
                Product product = transcoder.transcode(source, spec);
                String path = storage.storeProduct(asset.getId(), version.getVersionNo(), spec.getName(), product.bytes);
                AssetFile file = new AssetFile();
                file.setAssetId(asset.getId());
                file.setVersionId(version.getId());
                file.setUsage(spec.getName());
                file.setWidth(product.width);
                file.setHeight(product.height);
                file.setLongEdge(Math.max(product.width, product.height));
                file.setFileSize(product.fileSize);
                file.setQualityRound(product.qualityRound);
                file.setFinalQuality(product.finalQuality);
                file.setContentType(product.contentType);
                file.setStoragePath(path);
                fileMapper.insert(file);
                success++;
            }

            markVersion(version.getId(), AssetEnums.JOB_SUCCESS, null, source.width, source.height);
            finishJob(jobId, AssetEnums.JOB_SUCCESS, success, null);
            // 最新版本指针指到成功版本
            assetMapper.update(null, new LambdaUpdateWrapper<Asset>()
                    .eq(Asset::getId, asset.getId())
                    .set(Asset::getLatestVersionId, version.getId()));
        } catch (Exception e) {
            String reason = friendlyReason(e);
            log.warn("转档失败 job={} version={}: {}", job.getJobNo(), version.getVersionNo(), reason);
            markVersion(version.getId(), AssetEnums.JOB_FAILED, reason, version.getWidth(), version.getHeight());
            finishJob(jobId, AssetEnums.JOB_FAILED, 0, reason);
        }
    }

    public TranscodeJob requireJob(Long id) {
        TranscodeJob job = jobMapper.selectById(id);
        if (job == null) {
            throw new IllegalArgumentException("转档任务不存在: id=" + id);
        }
        return job;
    }

    public TranscodeJob requireJobByNo(String jobNo) {
        TranscodeJob job = jobMapper.selectOne(new LambdaQueryWrapper<TranscodeJob>()
                .eq(TranscodeJob::getJobNo, jobNo));
        if (job == null) {
            throw new IllegalArgumentException("转档任务不存在: " + jobNo);
        }
        return job;
    }

    public List<TranscodeJob> listJobs(Long assetId) {
        return jobMapper.selectList(new LambdaQueryWrapper<TranscodeJob>()
                .eq(TranscodeJob::getAssetId, assetId)
                .orderByDesc(TranscodeJob::getId));
    }

    private void markVersion(Long versionId, String status, String reason, Integer width, Integer height) {
        versionMapper.update(null, new LambdaUpdateWrapper<AssetVersion>()
                .eq(AssetVersion::getId, versionId)
                .set(AssetVersion::getTranscodeStatus, status)
                .set(AssetVersion::getFailReason, reason)
                .set(width != null, AssetVersion::getWidth, width)
                .set(height != null, AssetVersion::getHeight, height));
    }

    private void finishJob(Long jobId, String status, int successCount, String reason) {
        jobMapper.update(null, new LambdaUpdateWrapper<TranscodeJob>()
                .eq(TranscodeJob::getId, jobId)
                .set(TranscodeJob::getStatus, status)
                .set(TranscodeJob::getSuccessCount, successCount)
                .set(TranscodeJob::getFailReason, reason)
                .set(TranscodeJob::getFinishedTime, LocalDateTime.now()));
    }

    /** 异常消息面向美工展示；兜底异常不给堆栈，只给可读提示。 */
    private String friendlyReason(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "转档处理异常：" + e.getClass().getSimpleName();
        }
        return message.length() > REASON_MAX ? message.substring(0, REASON_MAX) : message;
    }
}
