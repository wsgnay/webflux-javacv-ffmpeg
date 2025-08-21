package com.example.ffmpeg.service;

import com.example.ffmpeg.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.librealsense.frame;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
// 导入OpenCV跟踪器相关类
import org.bytedeco.opencv.global.opencv_tracking;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_tracking.*;
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
    private final OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();

    // 跟踪器状态类 - 使用真正的OpenCV跟踪器
    private static class TrackerInfo {
        public Tracker tracker; // 真正的OpenCV跟踪器
        public int id;
        public double confidence;
        public int lostFrames;
        public Rect2d lastBbox;
        public Color color;
        public boolean active;
        public long lastUpdateFrame;
        public String trackerType;
        public int createdFrame;

        public TrackerInfo(int id, Rect2d bbox, Color color, String trackerType, int createdFrame) {
            this.id = id;
            this.confidence = 1.0;
            this.lostFrames = 0;
            this.lastBbox = new Rect2d(bbox.x(), bbox.y(), bbox.width(), bbox.height());
            this.color = color;
            this.active = true;
            this.lastUpdateFrame = System.currentTimeMillis();
            this.trackerType = trackerType;
            this.createdFrame = createdFrame;
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
        String videoSource = request.getVideoSource();
        if (!videoSource.matches("\\d+")) {
            Path inputPath = Paths.get(videoSource);
            if (!Files.exists(inputPath)) {
                throw new IllegalArgumentException("视频文件不存在: " + videoSource);
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
                .sorted((a, b) -> Double.compare(b.confidence, a.confidence))
                .toList();

        for (int i = 0; i < activeTrackers.size(); i++) {
            TrackerInfo tracker1 = activeTrackers.get(i);
            if (!tracker1.active) continue;

            for (int j = i + 1; j < activeTrackers.size(); j++) {
                TrackerInfo tracker2 = activeTrackers.get(j);
                if (!tracker2.active) continue;

                double iou = calculateIoU(tracker1.lastBbox, tracker2.lastBbox);
                if (iou > iouThreshold) {
                    tracker2.active = false;
                    removedCount++;
                    log.debug("去重移除跟踪器 #{} ({}) (IoU={:.3f})",
                            tracker2.id, tracker2.trackerType, iou);
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

            // 根据丢失帧数调整颜色透明度
            float alpha = tracker.lostFrames > 0 ?
                    Math.max(0.3f, 1.0f - (float)tracker.lostFrames / 30.0f) : 1.0f;

            Color color = new Color(
                    tracker.color.getRed(),
                    tracker.color.getGreen(),
                    tracker.color.getBlue(),
                    (int)(255 * alpha)
            );
            g2d.setColor(color);

            // 绘制边界框
            g2d.drawRect((int) bbox.x(), (int) bbox.y(),
                    (int) bbox.width(), (int) bbox.height());

            // 绘制跟踪器ID、类型和置信度
            String label = String.format("#%d (%s) %.2f", tracker.id, tracker.trackerType, tracker.confidence);
            if (tracker.lostFrames > 0) {
                label += " [LOST:" + tracker.lostFrames + "]";
            }

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
                Color.MAGENTA, Color.YELLOW, Color.ORANGE, Color.PINK,
                new Color(128, 0, 128), new Color(255, 165, 0), // Purple, Orange
                new Color(0, 128, 128), new Color(128, 128, 0)  // Teal, Olive
        };
        return colors[trackerId % colors.length];
    }

    private void saveVideoDetectionToDatabase(DroneVideoRequest request, String outputPath,
                                              TrackingResult.TrackingStats stats) {
        try {
            TrackingResult trackingResult = TrackingResult.builder()
                    .success(true)
                    .videoPath(request.getVideoSource())
                    .outputVideoPath("/" + outputPath)
                    .outputPath(outputPath)
                    .totalFrames(stats.getTotalFrames())
                    .maxPersonCount(stats.getMaxPersonCount())
                    .apiCallCount(stats.getApiCalls())
                    .dedupOperations(stats.getDedupCount())
                    .processingTimeMs(stats.getProcessingTimeMs())
                    .startTime(stats.getStartTime())
                    .endTime(stats.getEndTime())
                    .stats(stats)
                    .build();

            Map<String, Object> config = new HashMap<>();
            config.put("confThreshold", request.getConfThreshold());
            config.put("trackerType", request.getTrackerType());
            config.put("enableAutoDedup", request.getEnableAutoDedup());
            config.put("model", request.getModelName());
            config.put("maxDetectionCalls", request.getMaxDetectionCalls());
            config.put("minDetectionInterval", request.getMinDetectionInterval());

            String videoName;
            if (request.getVideoSource().matches("\\d+")) {
                videoName = "camera_" + request.getVideoSource();
            } else {
                videoName = java.nio.file.Paths.get(request.getVideoSource()).getFileName().toString();
            }

            databaseService.saveVideoDetection(
                    request.getVideoSource(),
                    videoName,
                    trackingResult,
                    config
            ).subscribe(
                    result -> log.info("视频检测结果已保存到数据库"),
                    error -> log.error("保存视频检测结果失败", error)
            );
        } catch (Exception e) {
            log.error("保存视频检测结果到数据库失败", e);
        }
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
// 初始化视频源
String videoSourcePath;
            if (videoSource.matches("\\d+")) {
int deviceId = Integer.parseInt(videoSource);
String osName = System.getProperty("os.name").toLowerCase();
                if (osName.contains("windows")) {
videoSourcePath = "video=" + deviceId;
grabber = new FFmpegFrameGrabber(videoSourcePath);
                    grabber.setFormat("dshow");
                } else if (osName.contains("mac")) {
videoSourcePath = "" + deviceId;
grabber = new FFmpegFrameGrabber(videoSourcePath);
                    grabber.setFormat("avfoundation");
                } else {
videoSourcePath = "/dev/video" + deviceId;
grabber = new FFmpegFrameGrabber(videoSourcePath);
                    grabber.setFormat("v4l2");
                }
                        log.info("初始化摄像头设备: {}, 格式: {}", videoSourcePath, grabber.getFormat());
        } else {
videoSourcePath = videoSource;
grabber = new FFmpegFrameGrabber(videoSourcePath);
                log.info("初始化视频文件: {}", videoSourcePath);
            }

                    // 设置摄像头参数
                    if (videoSource.matches("\\d+")) {
        grabber.setImageWidth(1280);
                grabber.setImageHeight(720);
                grabber.setFrameRate(30);
            }

                    grabber.start();

int fps = (int) grabber.getFrameRate();
int width = grabber.getImageWidth();
int height = grabber.getImageHeight();
int totalFrames = 0;

            if (!videoSource.matches("\\d+")) {
totalFrames = grabber.getLengthInFrames();
            }

                    log.info("视频信息: {}x{}, FPS: {}, 总帧数: {}", width, height, fps,
                             totalFrames > 0 ? totalFrames : "未知(实时流)");

// 初始化录制器
recorder = new FFmpegFrameRecorder(outputPath, width, height);
            recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264);
            recorder.setFrameRate(fps);
            recorder.setVideoBitrate(2000000);
            recorder.start();

            stats.setFps(fps);
            stats.setTotalFrames(totalFrames);

Frame frame;
int lastDetectionFrame = -minDetectionInterval;

            while ((frame = grabber.grab()) != null) {
        if (frame.image == null) continue;

int currentFrame = frameCounter.incrementAndGet();
BufferedImage bufferedImage = frameConverter.convert(frame);
Mat mat = matConverter.convert(frame);

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
        // 为每个检测结果创建跟踪器
        for (PersonDetection detection : detections) {
double[] bbox = detection.getBbox();
                                if (bbox != null && bbox.length >= 4) {
Rect2d rect2d = new Rect2d(
        bbox[0], bbox[1],
        bbox[2] - bbox[0], bbox[3] - bbox[1]
);

// 检查是否与现有跟踪器重叠
                                    if (!isOverlapWithExistingTrackers(rect2d, trackers, 0.3)) {
int trackerId = trackerIdCounter.getAndIncrement();
Color color = generateTrackingColor(trackerId);
TrackerInfo trackerInfo = new TrackerInfo(trackerId, rect2d, color,
        trackerType, currentFrame);

                                        if (initializeOpenCVTracker(trackerInfo, mat, rect2d, trackerType)) {
        trackers.add(trackerInfo);
                                            log.info("创建新跟踪器 #{} ({}), 位置: ({:.0f},{:.0f},{:.0f},{:.0f})",
                                                     trackerId, trackerType, rect2d.x(), rect2d.y(), rect2d.width(), rect2d.height());
        }
        }
        }
        }
        }
        } catch (Exception e) {
        log.warn("AI检测失败: {}", e.getMessage());
        }
        }

// 更新现有跟踪器 - 使用真正的OpenCV跟踪
updateOpenCVTrackers(trackers, mat, currentFrame);

// 自动去重
                if (enableAutoDedup && currentFrame % 30 == 0) {
int removedCount = performAutoDedup(trackers, 0.05, 0.4);
                    if (removedCount > 0) {
        dedupCounter.addAndGet(removedCount);
                        log.debug("第{}帧自动去重，移除{}个跟踪器", currentFrame, removedCount);
                    }
                            }

// 绘制跟踪结果
drawTrackingResults(bufferedImage, trackers);

// 转换并录制帧
Frame outputFrame = frameConverter.convert(bufferedImage);
                recorder.record(outputFrame);

// 记录进度
                if (currentFrame % 100 == 0) {
        if (totalFrames > 0) {
double progress = (double) currentFrame / totalFrames * 100;
                        log.info("处理进度: {:.1f}% ({}/{}), 活跃跟踪器: {}",
                                 progress, currentFrame, totalFrames, getActiveTrackerCount(trackers));
        } else {
        log.info("已处理帧数: {}, 活跃跟踪器: {}",
                 currentFrame, getActiveTrackerCount(trackers));
        }
        }

        // 对于摄像头流的停止条件
        if (videoSource.matches("\\d+") && currentFrame > 3000) {
        log.info("达到最大处理帧数，停止摄像头流处理");
                    break;
                            }
                            }

                            stats.setEndTime(LocalDateTime.now());
        stats.setTotalFrames(frameCounter.get());
        stats.setActiveTrackers(getActiveTrackerCount(trackers));
        stats.setApiCalls(apiCallCounter.get());
        stats.setDedupCount(dedupCounter.get());

        log.info("视频处理完成: 处理{}帧, API调用{}次, 去重{}次",
                 frameCounter.get(), apiCallCounter.get(), dedupCounter.get());

// 保存到数据库
saveVideoDetectionToDatabase(request, outputPath, stats);

            return TrackingResult.builder()
                    .success(true)
                    .outputVideoPath("/" + outputPath)
                    .outputPath(outputPath)
                    .totalFrames(stats.getTotalFrames())
        .maxPersonCount(stats.getMaxPersonCount())
        .apiCallCount(stats.getApiCalls())
        .dedupOperations(stats.getDedupCount())
        .processingTimeMs(stats.getProcessingTimeMs())
        .stats(stats)
                    .build();

        } finally {
                if (grabber != null) {
        try { grabber.stop(); } catch (Exception e) { log.warn("关闭grabber失败", e); }
        }
        if (recorder != null) {
        try { recorder.stop(); } catch (Exception e) { log.warn("关闭recorder失败", e); }
        }
        }
        }

/**
 * 初始化真正的OpenCV跟踪器
 */
private boolean initializeOpenCVTracker(TrackerInfo trackerInfo, Mat frame, Rect2d bbox, String trackerType) {
    try {
        log.debug("创建OpenCV {}跟踪器", trackerType);

        // 创建对应类型的OpenCV跟踪器
        Tracker tracker = null;
        switch (trackerType.toUpperCase()) {
            case "MIL":
                tracker = TrackerMIL.create();
                break;
            case "KCF":
                tracker = TrackerKCF.create();
                break;
            case "CSRT":
                tracker = TrackerCSRT.create();
                break;
            case "GOTURN":
                tracker = TrackerGOTURN.create();
                break;
            default:
                log.warn("不支持的跟踪器类型: {}, 使用默认MIL", trackerType);
                tracker = TrackerMIL.create();
                break;
        }

        if (tracker != null) {
            // 初始化跟踪器
            boolean success = tracker.init(frame, bbox);
            if (success) {
                trackerInfo.tracker = tracker;
                log.debug("OpenCV跟踪器 #{} ({}) 初始化成功", trackerInfo.id, trackerType);
                return true;
            } else {
                log.warn("OpenCV跟踪器 #{} ({}) 初始化失败", trackerInfo.id, trackerType);
                return false;
            }
        } else {
            log.error("无法创建OpenCV跟踪器: {}", trackerType);
            return false;
        }

    } catch (Exception e) {
        log.error("创建OpenCV跟踪器失败: {}", e.getMessage(), e);
        return false;
    }
}

/**
 * 使用真正的OpenCV跟踪器更新
 */
private void updateOpenCVTrackers(List<TrackerInfo> trackers, Mat frame, int currentFrame) {
    Iterator<TrackerInfo> iterator = trackers.iterator();

    while (iterator.hasNext()) {
        TrackerInfo trackerInfo = iterator.next();
        if (!trackerInfo.active || trackerInfo.tracker == null) continue;

        try {
            // 使用OpenCV跟踪器更新
            Rect2d bbox = new Rect2d();
            boolean success = trackerInfo.tracker.update(frame, bbox);

            if (success && isValidBbox(bbox, frame.cols(), frame.rows())) {
                // 跟踪成功，更新信息
                trackerInfo.lastBbox = new Rect2d(bbox.x(), bbox.y(), bbox.width(), bbox.height());
                trackerInfo.lostFrames = 0;
                trackerInfo.lastUpdateFrame = currentFrame;
                trackerInfo.confidence = Math.max(0.1, trackerInfo.confidence * 0.995);

                log.debug("跟踪器 #{} ({}) 更新成功: ({:.0f},{:.0f},{:.0f},{:.0f})",
                        trackerInfo.id, trackerInfo.trackerType,
                        bbox.x(), bbox.y(), bbox.width(), bbox.height());
            } else {
                // 跟踪失败
                trackerInfo.lostFrames++;
                trackerInfo.confidence *= 0.9;

                log.debug("跟踪器 #{} ({}) 跟踪失败, 丢失帧数: {}",
                        trackerInfo.id, trackerInfo.trackerType, trackerInfo.lostFrames);

                // 如果丢失帧数过多，标记为非活跃
                if (trackerInfo.lostFrames > 30) {
                    trackerInfo.active = false;
                    log.info("移除跟踪器 #{} ({}) (丢失{}帧)",
                            trackerInfo.id, trackerInfo.trackerType, trackerInfo.lostFrames);
                }
            }

        } catch (Exception e) {
            log.warn("跟踪器 #{} ({}) 更新异常: {}",
                    trackerInfo.id, trackerInfo.trackerType, e.getMessage());
            trackerInfo.lostFrames++;

            if (trackerInfo.lostFrames > 10) {
                trackerInfo.active = false;
                log.warn("移除异常跟踪器 #{} ({})", trackerInfo.id, trackerInfo.trackerType);
            }
        }
    }
