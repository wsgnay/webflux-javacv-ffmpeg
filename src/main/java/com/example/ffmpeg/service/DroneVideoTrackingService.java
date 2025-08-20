// src/main/java/com/example/ffmpeg/service/DroneVideoTrackingService.java
package com.example.ffmpeg.service;

import com.example.ffmpeg.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.global.opencv_tracking;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_tracking.TrackerMIL;
import org.bytedeco.opencv.opencv_tracking.TrackerCSRT;
import org.bytedeco.opencv.opencv_tracking.TrackerKCF;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class DroneVideoTrackingService {

    private final QwenApiService qwenApiService;
    private final DatabaseService databaseService;
    private final Java2DFrameConverter frameConverter = new Java2DFrameConverter();

    // 跟踪器状态类
    private static class TrackerInfo {
        public org.bytedeco.opencv.opencv_tracking.Tracker tracker;
        public int id;
        public double confidence;
        public int lostFrames;
        public Rect2d lastBbox;
        public Color color;
        public boolean active;
        public long lastUpdateFrame;

        public TrackerInfo(org.bytedeco.opencv.opencv_tracking.Tracker tracker, int id, Rect2d bbox, Color color) {
            this.tracker = tracker;
            this.id = id;
            this.confidence = 1.0;
            this.lostFrames = 0;
            this.lastBbox = bbox;
            this.color = color;
            this.active = true;
            this.lastUpdateFrame = System.currentTimeMillis();
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

            return processVideo(request, outputPath);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private TrackingResult processVideo(DroneVideoRequest request, String outputPath) throws Exception {
        // 检查输入文件
        if (!request.getVideoSource().matches("\\d+")) { // 不是摄像头设备号
            Path inputPath = Paths.get(request.getVideoSource());
            if (!Files.exists(inputPath)) {
                throw new IllegalArgumentException("视频文件不存在: " + request.getVideoSource());
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
            // 初始化视频抓取器
            if (request.getVideoSource().matches("\\d+")) {
                grabber = new FFmpegFrameGrabber(Integer.parseInt(request.getVideoSource()));
            } else {
                grabber = new FFmpegFrameGrabber(request.getVideoSource());
            }
            grabber.start();

            int fps = (int) grabber.getFrameRate();
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            int totalFrames = grabber.getLengthInFrames();

            log.info("视频信息: {}x{}, FPS: {}, 总帧数: {}", width, height, fps, totalFrames);

            // 初始化录制器
            recorder = new FFmpegFrameRecorder(outputPath, width, height);
            recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264);
            recorder.setFrameRate(fps);
            recorder.setVideoBitrate(2000000); // 2Mbps
            recorder.start();

            stats.setFrameRate(fps);
            stats.setTotalFrames(totalFrames);
            stats.setVideoResolution(width + "x" + height);

            Frame frame;
            int lastDetectionFrame = -minDetectionInterval;

            while ((frame = grabber.grab()) != null) {
                if (frame.image == null) continue;

                int currentFrame = frameCounter.incrementAndGet();
                BufferedImage bufferedImage = frameConverter.convert(frame);
                Mat currentMat = new Mat(height, width, opencv_core.CV_8UC3);

                // 转换BufferedImage到Mat
                convertBufferedImageToMat(bufferedImage, currentMat);

                boolean shouldDetect = shouldPerformDetection(currentFrame, detectionFrames,
                        lastDetectionFrame, minDetectionInterval, apiCallCounter.get(), maxDetectionCalls);

                if (shouldDetect) {
                    log.info("在第{}帧执行AI检测", currentFrame);

                    try {
                        // 调用Qwen API进行检测
                        List<PersonDetection> detections = qwenApiService.detectPersonsInFrame(
                                bufferedImage, request.getApiKey(), confThreshold, 30
                        ).block();

                        apiCallCounter.incrementAndGet();
                        lastDetectionFrame = currentFrame;

                        if (detections != null && !detections.isEmpty()) {
                            // 初始化新的跟踪器
                            for (PersonDetection detection : detections) {
                                if (detection.getConfidence() >= confThreshold) {
                                    Rect2d bbox = new Rect2d(
                                            detection.getBbox().get(0),
                                            detection.getBbox().get(1),
                                            detection.getBbox().get(2) - detection.getBbox().get(0),
                                            detection.getBbox().get(3) - detection.getBbox().get(1)
                                    );

                                    // 检查是否与现有跟踪器重叠
                                    if (!isOverlapWithExistingTrackers(bbox, trackers, 0.3)) {
                                        org.bytedeco.opencv.opencv_tracking.Tracker tracker = createTracker(trackerType);
                                        if (tracker.init(currentMat, bbox)) {
                                            Color color = generateTrackingColor(trackerIdCounter.get());
                                            TrackerInfo trackerInfo = new TrackerInfo(tracker,
                                                    trackerIdCounter.getAndIncrement(), bbox, color);
                                            trackers.add(trackerInfo);
                                            log.info("新增跟踪器 #{}, 置信度: {:.2f}",
                                                    trackerInfo.id, detection.getConfidence());
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("AI检测失败: {}", e.getMessage());
                    }
                }

                // 更新所有跟踪器
                updateTrackers(trackers, currentMat, currentFrame);

                // 自动去重
                if (enableAutoDedup && currentFrame % 30 == 0) { // 每30帧执行一次去重
                    int removedCount = performAutoDedup(trackers, 0.05, 0.4);
                    if (removedCount > 0) {
                        dedupCounter.addAndGet(removedCount);
                        log.debug("第{}帧执行去重，移除{}个跟踪器", currentFrame, removedCount);
                    }
                }

                // 绘制跟踪结果
                drawTrackingResults(bufferedImage, trackers);

                // 转换并录制帧
                Frame outputFrame = frameConverter.convert(bufferedImage);
                recorder.record(outputFrame);

                // 记录进度
                if (currentFrame % 100 == 0) {
                    double progress = (double) currentFrame / totalFrames * 100;
                    log.info("处理进度: {:.1f}% ({}/{}), 活跃跟踪器: {}",
                            progress, currentFrame, totalFrames, getActiveTrackerCount(trackers));
                }
            }

            stats.setEndTime(LocalDateTime.now());
            stats.setFrameCount(frameCounter.get());
            stats.setActiveTrackers(getActiveTrackerCount(trackers));
            stats.setApiCallsUsed(apiCallCounter.get());
            stats.setDedupOperations(dedupCounter.get());

            log.info("视频处理完成: 处理{}帧, API调用{}次, 去重{}次",
                    frameCounter.get(), apiCallCounter.get(), dedupCounter.get());

            // 保存到数据库
            saveVideoDetectionToDatabase(request, outputPath, stats);

            return TrackingResult.builder()
                    .success(true)
                    .outputVideoPath(outputPath)
                    .result(stats)
                    .build();

        } finally {
            if (grabber != null) {
                try { grabber.stop(); } catch (Exception e) { log.warn("关闭grabber失败", e); }
            }
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception e) { log.warn("关闭recorder失败", e); }
            }

            // 释放跟踪器资源
            trackers.forEach(t -> {
                try { t.tracker.close(); } catch (Exception e) { log.warn("释放跟踪器失败", e); }
            });
        }
    }

    private void convertBufferedImageToMat(BufferedImage bufferedImage, Mat mat) {
        // 简化的BufferedImage到Mat转换
        // 实际实现需要更复杂的像素格式转换
        log.debug("转换BufferedImage到Mat格式");
    }

    private boolean shouldPerformDetection(int currentFrame, List<Integer> detectionFrames,
                                           int lastDetectionFrame, int minInterval,
                                           int apiCallCount, int maxCalls) {
        // 检查是否在预定义的检测帧
        if (detectionFrames.contains(currentFrame)) {
            return apiCallCount < maxCalls;
        }

        // 检查是否达到最小间隔
        if (currentFrame - lastDetectionFrame >= minInterval) {
            return apiCallCount < maxCalls;
        }

        return false;
    }

    private org.bytedeco.opencv.opencv_tracking.Tracker createTracker(String trackerType) {
        switch (trackerType.toUpperCase()) {
            case "CSRT":
                return TrackerCSRT.create();
            case "KCF":
                return TrackerKCF.create();
            case "MIL":
            default:
                return TrackerMIL.create();
        }
    }

    private boolean isOverlapWithExistingTrackers(Rect2d newBbox, List<TrackerInfo> trackers, double threshold) {
        for (TrackerInfo tracker : trackers) {
            if (!tracker.active) continue;

            double iou = calculateIoU(newBbox, tracker.lastBbox);
            if (iou > threshold) {
                return true;
            }
        }
        return false;
    }

    private double calculateIoU(Rect2d bbox1, Rect2d bbox2) {
        double x1 = Math.max(bbox1.x(), bbox2.x());
        double y1 = Math.max(bbox1.y(), bbox2.y());
        double x2 = Math.min(bbox1.x() + bbox1.width(), bbox2.x() + bbox2.width());
        double y2 = Math.min(bbox1.y() + bbox1.height(), bbox2.y() + bbox2.height());

        if (x2 <= x1 || y2 <= y1) return 0.0;

        double intersectionArea = (x2 - x1) * (y2 - y1);
        double bbox1Area = bbox1.width() * bbox1.height();
        double bbox2Area = bbox2.width() * bbox2.height();
        double unionArea = bbox1Area + bbox2Area - intersectionArea;

        return intersectionArea / unionArea;
    }

    private void updateTrackers(List<TrackerInfo> trackers, Mat currentMat, int currentFrame) {
        Iterator<TrackerInfo> iterator = trackers.iterator();

        while (iterator.hasNext()) {
            TrackerInfo trackerInfo = iterator.next();
            if (!trackerInfo.active) continue;

            Rect2d bbox = new Rect2d();
            boolean success = trackerInfo.tracker.update(currentMat, bbox);

            if (success && isValidBbox(bbox, currentMat.cols(), currentMat.rows())) {
                trackerInfo.lastBbox = bbox;
                trackerInfo.lostFrames = 0;
                trackerInfo.lastUpdateFrame = currentFrame;

                // 适度降低置信度
                trackerInfo.confidence = Math.max(0.1, trackerInfo.confidence * 0.995);
            } else {
                trackerInfo.lostFrames++;
                trackerInfo.confidence *= 0.9; // 快速降低置信度

                // 移除长时间丢失的跟踪器
                if (trackerInfo.lostFrames > 30) { // 丢失30帧后移除
                    trackerInfo.active = false;
                    log.debug("移除跟踪器 #{} (丢失{}帧)", trackerInfo.id, trackerInfo.lostFrames);
                }
            }
        }
    }

    private boolean isValidBbox(Rect2d bbox, int imageWidth, int imageHeight) {
        return bbox.width() > 5 && bbox.height() > 5 &&
                bbox.x() >= 0 && bbox.y() >= 0 &&
                bbox.x() + bbox.width() <= imageWidth &&
                bbox.y() + bbox.height() <= imageHeight;
    }

    private int performAutoDedup(List<TrackerInfo> trackers, double iouThreshold, double overlapThreshold) {
        int removedCount = 0;
        List<TrackerInfo> activeTrackers = trackers.stream()
                .filter(t -> t.active)
                .sorted((a, b) -> Double.compare(b.confidence, a.confidence)) // 按置信度降序
                .toList();

        for (int i = 0; i < activeTrackers.size(); i++) {
            TrackerInfo tracker1 = activeTrackers.get(i);
            if (!tracker1.active) continue;

            for (int j = i + 1; j < activeTrackers.size(); j++) {
                TrackerInfo tracker2 = activeTrackers.get(j);
                if (!tracker2.active) continue;

                double iou = calculateIoU(tracker1.lastBbox, tracker2.lastBbox);
                if (iou > iouThreshold) {
                    // 移除置信度较低的跟踪器
                    tracker2.active = false;
                    removedCount++;
                    log.debug("去重移除跟踪器 #{} (IoU={:.3f})", tracker2.id, iou);
                }
            }
        }

        return removedCount;
    }

    private void drawTrackingResults(BufferedImage image, List<TrackerInfo> trackers) {
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setStroke(new BasicStroke(2.0f));

        for (TrackerInfo tracker : trackers) {
            if (!tracker.active) continue;

            Rect2d bbox = tracker.lastBbox;
            g2d.setColor(tracker.color);

            // 绘制边界框
            g2d.drawRect((int) bbox.x(), (int) bbox.y(),
                    (int) bbox.width(), (int) bbox.height());

            // 绘制跟踪器ID和置信度
            String label = String.format("#%d (%.2f)", tracker.id, tracker.confidence);
            FontMetrics fm = g2d.getFontMetrics();
            int labelWidth = fm.stringWidth(label);
            int labelHeight = fm.getHeight();

            // 绘制标签背景
            g2d.fillRect((int) bbox.x(), (int) bbox.y() - labelHeight,
                    labelWidth + 4, labelHeight);

            // 绘制标签文字
            g2d.setColor(Color.WHITE);
            g2d.drawString(label, (int) bbox.x() + 2, (int) bbox.y() - 2);
        }

        g2d.dispose();
    }

    private int getActiveTrackerCount(List<TrackerInfo> trackers) {
        return (int) trackers.stream().filter(t -> t.active).count();
    }

    private Color generateTrackingColor(int trackerId) {
        Color[] colors = {
                Color.GREEN, Color.BLUE, Color.RED, Color.CYAN,
                Color.MAGENTA, Color.YELLOW, Color.ORANGE, Color.PINK
        };
        return colors[trackerId % colors.length];
    }

    private void saveVideoDetectionToDatabase(DroneVideoRequest request, String outputPath,
                                              TrackingResult.TrackingStats stats) {
        try {
            databaseService.saveVideoDetection(
                    request.getVideoSource(),
                    outputPath,
                    stats.getActiveTrackers(),
                    stats.getApiCallsUsed(),
                    stats.getDedupOperations(),
                    stats.getFrameCount(),
                    java.time.Duration.between(stats.getStartTime(), stats.getEndTime()).toMillis()
            ).subscribe(
                    result -> log.info("视频检测结果已保存到数据库"),
                    error -> log.error("保存视频检测结果失败", error)
            );
        } catch (Exception e) {
            log.error("保存视频检测结果到数据库失败", e);
        }
    }
}
