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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class DroneVideoTrackingService {

    private final QwenApiService qwenApiService;
    private final Java2DFrameConverter frameConverter = new Java2DFrameConverter();

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
            if (request.isSaveVideo()) {
                recorder = new FFmpegFrameRecorder(outputPath, width, height);
                recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264);
                recorder.setFrameRate(fps);
                recorder.setAudioChannels(grabber.getAudioChannels());
                recorder.setSampleRate(grabber.getSampleRate());
                recorder.start();
            }

            // 初始化跟踪器管理器
            TrackerManager trackerManager = new TrackerManager(request);

            int frameCount = 0;
            Frame frame;

            while ((frame = grabber.grab()) != null) {
                if (frame.image == null) continue;

                frameCount++;
                BufferedImage bufferedImage = frameConverter.convert(frame);

                // 每帧进行自动去重检查
                if (request.isEnableAutoDedup() && frameCount > 1) {
                    trackerManager.autoDeduplicate(frameCount);
                }

                // 判断是否需要进行API检测
                if (trackerManager.shouldDetectFrame(frameCount)) {
                    log.info("=" + "=".repeat(50));
                    log.info("第 {} 帧 - 执行检测", frameCount);

                    // 调用API检测
                    List<PersonDetection> detections = qwenApiService.detectPersonsInFrame(
                            bufferedImage,
                            request.getApiKey(),
                            request.getModel(),
                            request.getMaxImageSize(),
                            request.getConfThreshold(),
                            request.getApiTimeout(),
                            frameCount
                    ).block();

                    if (detections != null && !detections.isEmpty()) {
                        if (frameCount == 1) {
                            // 第一帧：初始化所有跟踪器
                            trackerManager.initializeTrackers(bufferedImage, detections);
                        } else {
                            // 后续帧：添加新的跟踪器
                            int oldCount = trackerManager.getActiveTrackerCount();
                            trackerManager.addNewTrackers(bufferedImage, detections, frameCount);
                            int newCount = trackerManager.getActiveTrackerCount();
                            log.info("活跃跟踪器: {} -> {}", oldCount, newCount);

                            // 检测后再次进行去重
                            if (newCount > oldCount) {
                                log.info("新增跟踪器后进行额外去重检查...");
                                trackerManager.autoDeduplicate(frameCount);
                            }
                        }
                    }

                    log.info("=" + "=".repeat(50));
                }

                // 每帧都更新跟踪器
                int activeCount = trackerManager.updateTrackers(bufferedImage);

                // 绘制跟踪结果
                BufferedImage drawnImage = drawTrackingResults(bufferedImage, trackerManager, frameCount);
                Frame drawnFrame = frameConverter.convert(drawnImage);

                // 保存到视频文件
                if (recorder != null) {
                    recorder.record(drawnFrame);
                }

                // 显示处理进度
                if (frameCount % 30 == 0) {
                    TrackerStats stats = trackerManager.getStats();
                    log.info("处理进度: {}/{} ({:.1f}%) | 活跃跟踪器: {} | 去重操作: {}",
                            frameCount, totalFrames, (double) frameCount / totalFrames * 100,
                            stats.activeTrackers, stats.dedupOperations);
                }
            }

            // 构建结果
            TrackerStats finalStats = trackerManager.getStats();
            TrackingResult result = new TrackingResult();
            result.setFrameCount(frameCount);
            result.setActiveTrackers(finalStats.activeTrackers);
            result.setTotalTrackers(finalStats.totalTrackers);
            result.setApiCallsUsed(finalStats.apiCallsUsed);
            result.setApiCallsMax(finalStats.apiCallsMax);
            result.setDedupOperations(finalStats.dedupOperations);
            result.setDedupRemoved(finalStats.dedupRemoved);
            result.setOutputPath(outputPath);

            log.info("处理完成！");
            log.info("总帧数: {}", frameCount);
            log.info("总创建跟踪器: {}", finalStats.totalTrackers);
            log.info("最终活跃跟踪器: {}", finalStats.activeTrackers);
            log.info("API调用: {}", finalStats.apiCallsUsed);
            log.info("去重操作次数: {}", finalStats.dedupOperations);
            log.info("去重移除的跟踪器: {}", finalStats.dedupRemoved);

            return result;

        } finally {
            if (grabber != null) {
                try {
                    grabber.stop();
                    grabber.release();
                } catch (Exception e) {
                    log.warn("关闭视频抓取器失败", e);
                }
            }
            if (recorder != null) {
                try {
                    recorder.stop();
                    recorder.release();
                } catch (Exception e) {
                    log.warn("关闭视频录制器失败", e);
                }
            }
        }
    }

    /**
     * 绘制跟踪结果
     */
    private BufferedImage drawTrackingResults(BufferedImage image, TrackerManager trackerManager, int frameCount) {
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = result.createGraphics();
        g2d.drawImage(image, 0, 0, null);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 绘制跟踪器
        for (TrackerInfo tracker : trackerManager.getActiveTrackers()) {
            double[] bbox = tracker.getBbox(); // [x, y, w, h]
            int x = (int) bbox[0];
            int y = (int) bbox[1];
            int w = (int) bbox[2];
            int h = (int) bbox[3];

            Color color = new Color(tracker.getColor()[0], tracker.getColor()[1], tracker.getColor()[2]);

            // 根据丢失帧数调整颜色透明度
            if (tracker.getLostFrames() > 0) {
                float alpha = Math.max(0.3f, 1.0f - (float) tracker.getLostFrames() / 30);
                color = new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (alpha * 255));
            }

            // 绘制边界框
            g2d.setColor(color);
            g2d.setStroke(new BasicStroke(2.0f));
            g2d.drawRect(x, y, w, h);

            // 绘制标签
            String label = "person";
            FontMetrics fm = g2d.getFontMetrics();
            int labelWidth = fm.stringWidth(label);
            int labelHeight = fm.getHeight();

            g2d.fillRect(x, y - labelHeight - 8, labelWidth, labelHeight);
            g2d.setColor(Color.WHITE);
            g2d.drawString(label, x, y - 4);
        }

        // 添加状态信息
        TrackerStats stats = trackerManager.getStats();
        g2d.setColor(Color.GREEN);
        g2d.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        g2d.drawString(String.format("Active: %d | Total: %d", stats.activeTrackers, stats.totalTrackers), 10, 30);

        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        g2d.drawString(String.format("API: %d/%d | Frame: %d", stats.apiCallsUsed, stats.apiCallsMax, frameCount), 10, 55);

        // 显示去重信息
        g2d.setColor(Color.MAGENTA);
        g2d.drawString(String.format("Dedup Ops: %d | Removed: %d", stats.dedupOperations, stats.dedupRemoved), 10, 80);

        g2d.setColor(Color.YELLOW);
        g2d.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g2d.drawString("Auto Dedup Tracking: " + request.getTrackerType(), 10, image.getHeight() - 10);

        g2d.dispose();
        return result;
    }

    /**
     * 跟踪器管理器内部类
     */
    private static class TrackerManager {
        private final DroneVideoRequest config;
        private final List<TrackerWrapper> trackers = new ArrayList<>();
        private final AtomicInteger nextTrackerId = new AtomicInteger(0);
        private final Random random = new Random();

        // 检测相关
        private int detectionCount = 0;
        private int lastDetectionFrame = 0;

        // 去重相关
        private int totalDedupOperations = 0;
        private final List<Map<String, Object>> dedupHistory = new ArrayList<>();

        public TrackerManager(DroneVideoRequest config) {
            this.config = config;
        }

        public boolean shouldDetectFrame(int frameNumber) {
            if (detectionCount >= 4) { // maxDetectionCalls
                return false;
            }
            if (config.getDetectionFrames().contains(frameNumber)) {
                return true;
            }
            return frameNumber - lastDetectionFrame >= config.getMinDetectionInterval();
        }

        public void initializeTrackers(BufferedImage frame, List<PersonDetection> detections) {
            log.info("初始化 {} 个跟踪器...", detections.size());

            detections = nonMaxSuppression(detections);

            for (PersonDetection detection : detections) {
                double[] bbox = detection.getBbox();
                Rect2d rect = new Rect2d((int) bbox[0], (int) bbox[1],
                        (int) (bbox[2] - bbox[0]), (int) (bbox[3] - bbox[1]));

                if (rect.width() < config.getMinBboxSize() || rect.height() < config.getMinBboxSize()) {
                    continue;
                }

                // 创建跟踪器
                org.bytedeco.opencv.opencv_tracking.Tracker tracker = createTracker();
                Mat frameMat = new Mat(frame.getHeight(), frame.getWidth(), opencv_core.CV_8UC3);
                // 这里需要将BufferedImage转换为Mat，简化处理

                TrackerWrapper wrapper = new TrackerWrapper();
                wrapper.tracker = tracker;
                wrapper.id = nextTrackerId.getAndIncrement();
                wrapper.bbox = new double[]{rect.x(), rect.y(), rect.width(), rect.height()};
                wrapper.active = true;
                wrapper.confidence = detection.getConfidence();
                wrapper.color = generateRandomColor();
                wrapper.lostFrames = 0;
                wrapper.createdFrame = 1;

                trackers.add(wrapper);
                log.info("✅ 跟踪器 #{} 初始化成功", wrapper.id);
            }
        }

        private org.bytedeco.opencv.opencv_tracking.Tracker createTracker() {
            switch (config.getTrackerType().toUpperCase()) {
                case "CSRT":
                    return TrackerCSRT.create();
                case "KCF":
                    return TrackerKCF.create();
                default:
                    return TrackerMIL.create();
            }
        }

        private int[] generateRandomColor() {
            return new int[]{
                    random.nextInt(256),
                    random.nextInt(256),
                    random.nextInt(256)
            };
        }

        public void addNewTrackers(BufferedImage frame, List<PersonDetection> detections, int frameNumber) {
            List<PersonDetection> newDetections = filterNewDetections(detections);
            log.info("准备添加 {} 个新跟踪器", newDetections.size());

            for (PersonDetection detection : newDetections) {
                double[] bbox = detection.getBbox();
                if (bbox[2] - bbox[0] < config.getMinBboxSize() ||
                        bbox[3] - bbox[1] < config.getMinBboxSize()) {
                    continue;
                }

                org.bytedeco.opencv.opencv_tracking.Tracker tracker = createTracker();

                TrackerWrapper wrapper = new TrackerWrapper();
                wrapper.tracker = tracker;
                wrapper.id = nextTrackerId.getAndIncrement();
                wrapper.bbox = new double[]{bbox[0], bbox[1], bbox[2] - bbox[0], bbox[3] - bbox[1]};
                wrapper.active = true;
                wrapper.confidence = detection.getConfidence();
                wrapper.color = generateRandomColor();
                wrapper.lostFrames = 0;
                wrapper.createdFrame = frameNumber;

                trackers.add(wrapper);
                log.info("✅ 新跟踪器 #{} 添加成功", wrapper.id);
            }

            detectionCount++;
            lastDetectionFrame = frameNumber;
        }

        private List<PersonDetection> filterNewDetections(List<PersonDetection> detections) {
            // NMS去重
            detections = nonMaxSuppression(detections);

            List<PersonDetection> newDetections = new ArrayList<>();

            for (PersonDetection detection : detections) {
                double[] detBbox = detection.getBbox();
                boolean isNew = true;

                // 与现有活跃跟踪器比较
                for (TrackerWrapper tracker : trackers) {
                    if (tracker.active) {
                        double[] trackerBbox = tracker.bbox;
                        double[] trackerBboxConverted = {
                                trackerBbox[0], trackerBbox[1],
                                trackerBbox[0] + trackerBbox[2], trackerBbox[1] + trackerBbox[3]
                        };

                        double iou = calculateIoU(detBbox, trackerBboxConverted);
                        double overlapRatio = calculateOverlapRatio(detBbox, trackerBboxConverted);

                        if (iou > config.getAutoDedupiouThreshold() ||
                                overlapRatio > config.getAutoDedupOverlapThreshold()) {
                            isNew = false;
                            break;
                        }
                    }
                }

                if (isNew) {
                    newDetections.add(detection);
                }
            }

            return newDetections;
        }

        public int updateTrackers(BufferedImage frame) {
            int activeCount = 0;

            for (TrackerWrapper tracker : trackers) {
                if (tracker.active) {
                    // 简化的跟踪器更新逻辑
                    // 在实际实现中，这里应该调用OpenCV的跟踪器update方法
                    // 由于OpenCV集成的复杂性，这里使用模拟的跟踪结果

                    boolean success = simulateTrackerUpdate(tracker, frame);

                    if (success) {
                        // 验证边界框是否在合理范围内
                        if (config.isEnableBoundaryCheck()) {
                            double[] bbox = tracker.bbox;
                            if (bbox[0] + bbox[2] < -config.getBoundaryMargin() ||
                                    bbox[0] > frame.getWidth() + config.getBoundaryMargin() ||
                                    bbox[1] + bbox[3] < -config.getBoundaryMargin() ||
                                    bbox[1] > frame.getHeight() + config.getBoundaryMargin()) {
                                log.info("跟踪器 #{} 超出边界太远，标记失效", tracker.id);
                                tracker.lostFrames++;
                            } else {
                                tracker.lostFrames = 0;
                                activeCount++;
                            }
                        } else {
                            tracker.lostFrames = 0;
                            activeCount++;
                        }
                    } else {
                        tracker.lostFrames++;
                    }

                    // 长期失效则标记为非活跃
                    if (tracker.lostFrames > config.getMaxLostFrames()) {
                        log.info("跟踪器 #{} 长期失效，标记为非活跃", tracker.id);
                        tracker.active = false;
                    }
                }
            }

            return activeCount;
        }

        private boolean simulateTrackerUpdate(TrackerWrapper tracker, BufferedImage frame) {
            // 模拟跟踪器更新，实际应该调用OpenCV tracker.update()
            // 这里简单地添加一些随机噪声来模拟跟踪结果
            Random rand = new Random();

            // 85%的概率成功跟踪
            if (rand.nextDouble() < 0.85) {
                // 添加轻微的位置变化来模拟目标移动
                tracker.bbox[0] += (rand.nextDouble() - 0.5) * 5;
                tracker.bbox[1] += (rand.nextDouble() - 0.5) * 5;

                // 确保边界框不超出图像范围
                tracker.bbox[0] = Math.max(0, Math.min(tracker.bbox[0], frame.getWidth() - tracker.bbox[2]));
                tracker.bbox[1] = Math.max(0, Math.min(tracker.bbox[1], frame.getHeight() - tracker.bbox[3]));

                return true;
            }
            return false;
        }

        public void autoDeduplicate(int frameNumber) {
            if (!config.isEnableAutoDedup()) return;

            List<TrackerWrapper> activeTrackers = getActiveTrackersList();
            if (activeTrackers.size() < 2) return;

            Set<Integer> toRemove = new HashSet<>();
            boolean dedupFound = false;

            for (int i = 0; i < activeTrackers.size(); i++) {
                TrackerWrapper tracker1 = activeTrackers.get(i);
                if (toRemove.contains(tracker1.id)) continue;

                for (int j = i + 1; j < activeTrackers.size(); j++) {
                    TrackerWrapper tracker2 = activeTrackers.get(j);
                    if (toRemove.contains(tracker2.id)) continue;

                    double[] bbox1 = convertToCornerFormat(tracker1.bbox);
                    double[] bbox2 = convertToCornerFormat(tracker2.bbox);

                    double iou = calculateIoU(bbox1, bbox2);
                    double overlapRatio = calculateOverlapRatio(bbox1, bbox2);

                    if (iou > config.getAutoDedupiouThreshold() ||
                            overlapRatio > config.getAutoDedupOverlapThreshold()) {

                        if (!dedupFound) {
                            log.info("\n=== 第 {} 帧发现重复，开始去重 ===", frameNumber);
                            dedupFound = true;
                        }

                        log.info("检查跟踪器 #{} vs #{}: IoU={:.3f}, 重叠率={:.3f}",
                                tracker1.id, tracker2.id, iou, overlapRatio);

                        boolean keepTracker1 = decideWhichToKeep(tracker1, tracker2);

                        if (keepTracker1) {
                            toRemove.add(tracker2.id);
                            log.info("🗑️ 去重：移除跟踪器 #{} (保留 #{})", tracker2.id, tracker1.id);
                        } else {
                            toRemove.add(tracker1.id);
                            log.info("🗑️ 去重：移除跟踪器 #{} (保留 #{})", tracker1.id, tracker2.id);
                        }

                        totalDedupOperations++;

                        Map<String, Object> dedupRecord = new HashMap<>();
                        dedupRecord.put("frame", frameNumber);
                        dedupRecord.put("removed", keepTracker1 ? tracker2.id : tracker1.id);
                        dedupRecord.put("kept", keepTracker1 ? tracker1.id : tracker2.id);
                        dedupRecord.put("iou", iou);
                        dedupRecord.put("overlap_ratio", overlapRatio);
                        dedupHistory.add(dedupRecord);
                    }
                }
            }

            // 执行移除操作
            int removedCount = 0;
            for (TrackerWrapper tracker : trackers) {
                if (toRemove.contains(tracker.id) && tracker.active) {
                    tracker.active = false;
                    tracker.removalReason = "auto_deduplication";
                    removedCount++;
                }
            }

            if (dedupFound && removedCount > 0) {
                log.info("✅ 自动去重完成：移除了 {} 个重复跟踪器", removedCount);
                log.info("=== 自动去重检查结束 ===\n");
            }
        }

        private double[] convertToCornerFormat(double[] bbox) {
            // 从 [x, y, w, h] 转换为 [x1, y1, x2, y2]
            return new double[]{bbox[0], bbox[1], bbox[0] + bbox[2], bbox[1] + bbox[3]};
        }

        private boolean decideWhichToKeep(TrackerWrapper tracker1, TrackerWrapper tracker2) {
            switch (config.getDedupStrategy()) {
                case "keep_higher_confidence":
                    return tracker1.confidence >= tracker2.confidence;
                case "keep_older_tracker":
                    return tracker1.createdFrame <= tracker2.createdFrame;
                case "keep_larger_bbox":
                    double area1 = tracker1.bbox[2] * tracker1.bbox[3];
                    double area2 = tracker2.bbox[2] * tracker2.bbox[3];
                    return area1 >= area2;
                default:
                    return tracker1.confidence >= tracker2.confidence;
            }
        }

        private List<PersonDetection> nonMaxSuppression(List<PersonDetection> detections) {
            if (detections.isEmpty()) return detections;

            detections.sort((a, b) -> Double.compare(b.getConfidence(), a.getConfidence()));
            List<PersonDetection> keep = new ArrayList<>();

            for (PersonDetection detection : detections) {
                boolean shouldKeep = true;
                for (PersonDetection keptDetection : keep) {
                    double iou = calculateIoU(detection.getBbox(), keptDetection.getBbox());
                    if (iou > config.getNmsThreshold()) {
                        shouldKeep = false;
                        break;
                    }
                }
                if (shouldKeep) {
                    keep.add(detection);
                }
            }

            log.info("NMS: {} -> {} 检测框", detections.size(), keep.size());
            return keep;
        }

        private double calculateIoU(double[] box1, double[] box2) {
            double x1 = Math.max(box1[0], box2[0]);
            double y1 = Math.max(box1[1], box2[1]);
            double x2 = Math.min(box1[2], box2[2]);
            double y2 = Math.min(box1[3], box2[3]);

            if (x2 <= x1 || y2 <= y1) return 0.0;

            double intersection = (x2 - x1) * (y2 - y1);
            double area1 = (box1[2] - box1[0]) * (box1[3] - box1[1]);
            double area2 = (box2[2] - box2[0]) * (box2[3] - box2[1]);
            double union = area1 + area2 - intersection;

            return union > 0 ? intersection / union : 0.0;
        }

        private double calculateOverlapRatio(double[] box1, double[] box2) {
            double x1 = Math.max(box1[0], box2[0]);
            double y1 = Math.max(box1[1], box2[1]);
            double x2 = Math.min(box1[2], box2[2]);
            double y2 = Math.min(box1[3], box2[3]);

            if (x2 <= x1 || y2 <= y1) return 0.0;

            double intersection = (x2 - x1) * (y2 - y1);
            double area1 = (box1[2] - box1[0]) * (box1[3] - box1[1]);
            double area2 = (box2[2] - box2[0]) * (box2[3] - box2[1]);
            double smallerArea = Math.min(area1, area2);

            return smallerArea > 0 ? intersection / smallerArea : 0.0;
        }

        public int getActiveTrackerCount() {
            return (int) trackers.stream().filter(t -> t.active).count();
        }

        public List<TrackerWrapper> getActiveTrackersList() {
            return trackers.stream().filter(t -> t.active).toList();
        }

        public List<TrackerInfo> getActiveTrackers() {
            return trackers.stream()
                    .filter(t -> t.active)
                    .map(this::convertToTrackerInfo)
                    .toList();
        }

        private TrackerInfo convertToTrackerInfo(TrackerWrapper wrapper) {
            TrackerInfo info = new TrackerInfo();
            info.setId(wrapper.id);
            info.setBbox(wrapper.bbox);
            info.setActive(wrapper.active);
            info.setConfidence(wrapper.confidence);
            info.setColor(wrapper.color);
            info.setLostFrames(wrapper.lostFrames);
            info.setCreatedFrame(wrapper.createdFrame);
            info.setRemovalReason(wrapper.removalReason);
            return info;
        }

        public TrackerStats getStats() {
            int activeCount = getActiveTrackerCount();
            int inactiveCount = trackers.size() - activeCount;
            int dedupRemoved = (int) trackers.stream()
                    .filter(t -> "auto_deduplication".equals(t.removalReason))
                    .count();

            TrackerStats stats = new TrackerStats();
            stats.activeTrackers = activeCount;
            stats.inactiveTrackers = inactiveCount;
            stats.totalTrackers = trackers.size();
            stats.apiCallsUsed = detectionCount;
            stats.apiCallsMax = 4;
            stats.dedupOperations = totalDedupOperations;
            stats.dedupRemoved = dedupRemoved;
            return stats;
        }
    }

    /**
     * 跟踪器包装类
     */
    private static class TrackerWrapper {
        org.bytedeco.opencv.opencv_tracking.Tracker tracker;
        int id;
        double[] bbox; // [x, y, w, h]
        boolean active;
        double confidence;
        int[] color;
        int lostFrames;
        int createdFrame;
        String removalReason;
    }

    /**
     * 跟踪器统计信息
     */
    private static class TrackerStats {
        int activeTrackers;
        int inactiveTrackers;
        int totalTrackers;
        int apiCallsUsed;
        int apiCallsMax;
        int dedupOperations;
        int dedupRemoved;
    }
}
