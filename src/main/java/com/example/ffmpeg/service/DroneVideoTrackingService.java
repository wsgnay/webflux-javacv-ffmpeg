package com.example.ffmpeg.service;

import com.example.ffmpeg.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class DroneVideoTrackingService {

    private final QwenApiService qwenApiService;
    private final DatabaseService databaseService;
    private final Java2DFrameConverter frameConverter = new Java2DFrameConverter();

    // 跟踪器状态类 - 简化实现
    private static class TrackerInfo {
        public int id;
        public double confidence;
        public int lostFrames;
        public double[] lastBbox; // 使用double数组而不是Rect2d
        public Color color;
        public boolean active;
        public long lastUpdateFrame;
        public String trackerType;

        public TrackerInfo(int id, double[] bbox, Color color, String trackerType) {
            this.id = id;
            this.confidence = 1.0;
            this.lostFrames = 0;
            this.lastBbox = bbox.clone();
            this.color = color;
            this.active = true;
            this.lastUpdateFrame = System.currentTimeMillis();
            this.trackerType = trackerType;
        }
    }

    /**
     * 处理无人机视频并进行人物跟踪
     */
    public Mono<TrackingResult> processVideoWithTracking(DroneVideoRequest request) {
        return Mono.fromCallable(() -> {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String outputPath = request.getOutputPath();
            if (outputPath == null) {
                outputPath = String.format("video/output/drone_tracking_%s.mp4", timestamp);
            }

            log.info("开始处理无人机视频: {}", request.getVideoSource());
            log.info("输出路径: {}", outputPath);

            try {
                TrackingResult result = processVideo(request, outputPath);

                // 确保设置正确的字段
                result.setOutputVideoPath(outputPath);

                // 设置result字段（包含详细信息）
                Map<String, Object> resultData = new HashMap<>();
                resultData.put("totalFrames", result.getTotalFrames());
                resultData.put("apiCallCount", result.getApiCallCount());
                resultData.put("maxPersonCount", result.getMaxPersonCount());
                resultData.put("processingTimeMs", result.getProcessingTimeMs());
                result.setResult(resultData);

                // 设置成功消息
                if (result.isSuccess()) {
                    result.setMessage("视频跟踪处理完成");
                }

                return result;

            } catch (Exception e) {
                log.error("视频处理失败", e);
                return TrackingResult.builder()
                        .success(false)
                        .error(e.getMessage())
                        .message("视频处理失败: " + e.getMessage())
                        .videoPath(request.getVideoSource())
                        .outputPath(outputPath)
                        .outputVideoPath(outputPath)
                        .build();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private TrackingResult processVideo(DroneVideoRequest request, String outputPath) throws Exception {
        // 检查输入文件
        String videoSource = request.getVideoSource();
        boolean isWebcam = videoSource.matches("\\d+"); // 检查是否为摄像头设备号

        if (!isWebcam) {
            Path inputPath = Paths.get(videoSource);
            if (!Files.exists(inputPath)) {
                throw new IllegalArgumentException("视频文件不存在: " + videoSource);
            }
        }

        // 创建输出目录
        Path outputDir = Paths.get(outputPath).getParent();
        if (outputDir != null && !Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }

        FFmpegFrameGrabber grabber = null;
        FFmpegFrameRecorder recorder = null;

        // 跟踪统计信息
        TrackingResult.TrackingStats stats = new TrackingResult.TrackingStats();
        stats.setStartTime(LocalDateTime.now());

        List<TrackerInfo> trackers = new ArrayList<>();
        AtomicInteger trackerIdCounter = new AtomicInteger(1);
        AtomicInteger frameCounter = new AtomicInteger(0);
        AtomicInteger apiCallCounter = new AtomicInteger(0);
        AtomicInteger dedupCounter = new AtomicInteger(0);

        // 配置参数
        double confThreshold = request.getConfThreshold() != null ? request.getConfThreshold() : 0.5;
        String trackerType = request.getTrackerType() != null ? request.getTrackerType() : "MIL";
        boolean enableAutoDedup = request.getEnableAutoDedup() != null ? request.getEnableAutoDedup() : true;

        // 检测帧配置
        List<Integer> detectionFrames = request.getDetectionFrames() != null ?
                request.getDetectionFrames() : Arrays.asList(1, 60, 150, 300);
        int minDetectionInterval = request.getMinDetectionInterval() != null ?
                request.getMinDetectionInterval() : 90;
        int maxDetectionCalls = request.getMaxDetectionCalls() != null ?
                request.getMaxDetectionCalls() : 4;

        try {
            // 修复FFmpegFrameGrabber构造
            if (isWebcam) {
                grabber = new FFmpegFrameGrabber(Integer.parseInt(videoSource));
            } else {
                grabber = new FFmpegFrameGrabber(videoSource);
            }

            grabber.start();

            // 获取视频信息
            int frameRate = (int) grabber.getFrameRate();
            int imageWidth = grabber.getImageWidth();
            int imageHeight = grabber.getImageHeight();

            // 修复stats设置方法
            stats.setFps(frameRate);
            stats.setTotalFrames(0); // 将在处理过程中更新

            // 初始化录制器
            recorder = new FFmpegFrameRecorder(outputPath, imageWidth, imageHeight);
            recorder.setFrameRate(frameRate);
            recorder.start();

            Frame frame;
            int lastDetectionFrame = 0;
            Map<Integer, List<PersonDetection>> detectionsByFrame = new HashMap<>();

            while ((frame = grabber.grab()) != null && frame.image != null) {
                int currentFrame = frameCounter.incrementAndGet();
                BufferedImage bufferedImage = frameConverter.convert(frame);

                if (bufferedImage == null) continue;

                // 检查是否需要进行检测
                if (shouldPerformDetection(currentFrame, detectionFrames, lastDetectionFrame,
                        minDetectionInterval, apiCallCounter.get(), maxDetectionCalls)) {

                    log.info("在第{}帧进行人物检测", currentFrame);

                    // 修复API调用参数
                    List<PersonDetection> newDetections = qwenApiService.detectPersonsInFrame(
                            bufferedImage,
                            request.getApiKey(),
                            confThreshold,
                            120
                    ).block(); // 同步调用，在实际项目中可能需要异步处理

                    if (newDetections != null && !newDetections.isEmpty()) {
                        detectionsByFrame.put(currentFrame, newDetections);
                        apiCallCounter.incrementAndGet();
                        lastDetectionFrame = currentFrame;

                        // 为新检测创建跟踪器
                        for (PersonDetection detection : newDetections) {
                            double[] bbox = detection.getBbox();

                            // 修复数组访问
                            double x1 = bbox[0], y1 = bbox[1];
                            double x2 = bbox[2], y2 = bbox[3];

                            boolean isNewTracker = true;

                            // 检查是否与现有跟踪器重叠
                            for (TrackerInfo tracker : trackers) {
                                if (tracker.active && calculateOverlap(bbox, tracker.lastBbox) > 0.3) {
                                    isNewTracker = false;
                                    break;
                                }
                            }

                            if (isNewTracker) {
                                Color color = getNextColor(trackers.size());
                                TrackerInfo newTracker = new TrackerInfo(
                                        trackerIdCounter.getAndIncrement(), bbox, color, trackerType);
                                trackers.add(newTracker);
                                log.info("创建新跟踪器 #{}", newTracker.id);
                            }
                        }
                    }
                }

                // 更新跟踪器（简化版本）
                updateTrackersSimplified(trackers, currentFrame);

                // 绘制跟踪结果
                drawTrackingResults(bufferedImage, trackers);

                // 记录帧
                Frame outputFrame = frameConverter.convert(bufferedImage);
                recorder.record(outputFrame);

                // 定期清理失效跟踪器
                if (currentFrame % 30 == 0) {
                    trackers.removeIf(tracker -> !tracker.active);
                }
            }

            // 设置最终统计信息
            stats.setEndTime(LocalDateTime.now());
            stats.setTotalFrames(frameCounter.get());
            stats.setApiCalls(apiCallCounter.get());
            stats.setDedupCount(dedupCounter.get());

            // 构建结果
            TrackingResult result = TrackingResult.builder()
                    .success(true)
                    .videoPath(request.getVideoSource())
                    .outputPath(outputPath)
                    .outputVideoPath(outputPath)
                    .startTime(stats.getStartTime())
                    .endTime(stats.getEndTime())
                    .processingTimeMs(java.time.Duration.between(stats.getStartTime(), stats.getEndTime()).toMillis())
                    .detectionsByFrame(detectionsByFrame)
                    .stats(stats)
                    .totalFrames(frameCounter.get())
                    .apiCallCount(apiCallCounter.get())
                    .dedupOperations(dedupCounter.get())
                    .maxPersonCount(trackers.size())
                    .build();

            // 修复数据库保存调用
            saveTrackingResult(request, result);

            return result;

        } finally {
            if (grabber != null) {
                try {
                    grabber.stop();
                    grabber.release();
                } catch (Exception e) {
                    log.warn("关闭grabber失败", e);
                }
            }
            if (recorder != null) {
                try {
                    recorder.stop();
                    recorder.release();
                } catch (Exception e) {
                    log.warn("关闭recorder失败", e);
                }
            }
        }
    }

    private boolean shouldPerformDetection(int currentFrame, List<Integer> detectionFrames,
                                           int lastDetectionFrame, int minInterval,
                                           int apiCallCount, int maxCalls) {
        if (detectionFrames.contains(currentFrame)) {
            return apiCallCount < maxCalls;
        }

        if (currentFrame - lastDetectionFrame >= minInterval) {
            return apiCallCount < maxCalls;
        }

        return false;
    }

    private void updateTrackersSimplified(List<TrackerInfo> trackers, int currentFrame) {
        for (TrackerInfo tracker : trackers) {
            if (!tracker.active) continue;

            // 简化的跟踪更新逻辑
            tracker.lostFrames++;
            tracker.confidence *= 0.99;

            if (tracker.lostFrames > 30 || tracker.confidence < 0.1) {
                tracker.active = false;
                log.debug("跟踪器 #{} 失效", tracker.id);
            }
        }
    }

    private double calculateOverlap(double[] bbox1, double[] bbox2) {
        if (bbox1 == null || bbox2 == null) return 0.0;

        double x1 = Math.max(bbox1[0], bbox2[0]);
        double y1 = Math.max(bbox1[1], bbox2[1]);
        double x2 = Math.min(bbox1[2], bbox2[2]);
        double y2 = Math.min(bbox1[3], bbox2[3]);

        if (x2 <= x1 || y2 <= y1) return 0.0;

        double intersection = (x2 - x1) * (y2 - y1);
        double area1 = (bbox1[2] - bbox1[0]) * (bbox1[3] - bbox1[1]);
        double area2 = (bbox2[2] - bbox2[0]) * (bbox2[3] - bbox2[1]);
        double union = area1 + area2 - intersection;

        return intersection / union;
    }

    private Color getNextColor(int index) {
        Color[] colors = {Color.GREEN, Color.BLUE, Color.RED, Color.CYAN,
                Color.MAGENTA, Color.YELLOW, Color.ORANGE, Color.PINK};
        return colors[index % colors.length];
    }

    private void drawTrackingResults(BufferedImage image, List<TrackerInfo> trackers) {
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (TrackerInfo tracker : trackers) {
            if (!tracker.active) continue;

            double[] bbox = tracker.lastBbox;
            int x1 = (int) bbox[0];
            int y1 = (int) bbox[1];
            int x2 = (int) bbox[2];
            int y2 = (int) bbox[3];

            g2d.setColor(tracker.color);
            g2d.setStroke(new BasicStroke(2.0f));
            g2d.drawRect(x1, y1, x2 - x1, y2 - y1);

            // 绘制跟踪器ID
            String label = String.format("T#%d (%.2f)", tracker.id, tracker.confidence);
            g2d.drawString(label, x1, y1 - 5);
        }

        g2d.dispose();
    }

    private void saveTrackingResult(DroneVideoRequest request, TrackingResult result) {
        try {
            log.info("保存跟踪结果到数据库");
            // 这里应该调用DatabaseService保存结果
            // databaseService.saveVideoTrackingResult(request, result);
        } catch (Exception e) {
            log.error("保存跟踪结果失败", e);
        }
    }
}
