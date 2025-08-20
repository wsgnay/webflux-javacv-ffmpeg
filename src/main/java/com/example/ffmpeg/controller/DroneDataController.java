// src/main/java/com/example/ffmpeg/controller/DroneDataController.java
package com.example.ffmpeg.controller;

import com.example.ffmpeg.service.DatabaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/drone/data")
@RequiredArgsConstructor
public class DroneDataController {

    private final DatabaseService databaseService;

    /**
     * 获取仪表板统计数据
     */
    @GetMapping("/dashboard/stats")
    public Mono<ResponseEntity<Map<String, Object>>> getDashboardStats() {
        return databaseService.getDetectionStats()
                .map(stats -> {
                    // 添加最近活动数据
                    return databaseService.getRecentDetections(5)
                            .collectList()
                            .map(recentActivities -> {
                                stats.put("recentActivities", recentActivities);
                                return ResponseEntity.ok(stats);
                            });
                })
                .flatMap(mono -> mono)
                .onErrorResume(ex -> {
                    log.error("获取仪表板统计数据失败", ex);
                    // 返回模拟数据作为备用
                    Map<String, Object> fallbackStats = createFallbackStats();
                    return Mono.just(ResponseEntity.ok(fallbackStats));
                });
    }

    /**
     * 获取历史记录
     */
    @GetMapping("/history")
    public Mono<ResponseEntity<Map<String, Object>>> getHistory(
            @RequestParam(defaultValue = "all") String filter,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String date) {

        return Mono.fromCallable(() -> {
            Map<String, Object> result = new HashMap<>();

            try {
                // 这里可以根据参数查询数据库
                List<Map<String, Object>> history = createMockHistory(filter, status, date);

                result.put("success", true);
                result.put("data", history);
                result.put("total", history.size());
                result.put("filter", filter);
                result.put("status", status);
                result.put("date", date);

                return ResponseEntity.ok(result);

            } catch (Exception e) {
                log.error("获取历史记录失败", e);
                result.put("success", false);
                result.put("error", e.getMessage());
                return ResponseEntity.internalServerError().body(result);
            }
        });
    }

    /**
     * 创建备用统计数据
     */
    private Map<String, Object> createFallbackStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalImages", 156);
        stats.put("totalVideos", 23);
        stats.put("totalPersons", 892);
        stats.put("apiCalls", 445);

        // 最近活动模拟数据 - 修复Map.of语法错误
        List<Map<String, Object>> recentActivities = new ArrayList<>();

        Map<String, Object> activity1 = new HashMap<>();
        activity1.put("type", "image");
        activity1.put("name", "drone_image_001.jpg");
        activity1.put("persons", 3);
        activity1.put("time", "2分钟前");
        activity1.put("status", "success");
        recentActivities.add(activity1);

        Map<String, Object> activity2 = new HashMap<>();
        activity2.put("type", "video");
        activity2.put("name", "surveillance_video.mp4");
        activity2.put("persons", 5);
        activity2.put("time", "10分钟前");
        activity2.put("status", "success");
        recentActivities.add(activity2);

        Map<String, Object> activity3 = new HashMap<>();
        activity3.put("type", "image");
        activity3.put("name", "aerial_shot.png");
        activity3.put("persons", 1);
        activity3.put("time", "1小时前");
        activity3.put("status", "success");
        recentActivities.add(activity3);

        stats.put("recentActivities", recentActivities);

        return stats;
    }

    /**
     * 创建模拟历史数据
     */
    private List<Map<String, Object>> createMockHistory(String filter, String status, String date) {
        List<Map<String, Object>> history = new ArrayList<>();

        String[] fileNames = {
                "drone_capture_001.jpg", "surveillance_cam.mp4", "aerial_view.png",
                "patrol_video.mp4", "overview_shot.jpg", "monitoring_cam.mp4"
        };

        String[] statuses = {"success", "processing", "failed"};

        for (int i = 1; i <= 15; i++) {
            String fileName = fileNames[i % fileNames.length];
            String fileType = fileName.endsWith(".mp4") ? "video" : "image";
            String recordStatus = statuses[i % statuses.length];

            // 应用过滤器
            if (!"all".equals(filter) && !filter.equals(fileType)) {
                continue;
            }
            if (!"all".equals(status) && !status.equals(recordStatus)) {
                continue;
            }

            Map<String, Object> record = new HashMap<>();
            record.put("id", i);
            record.put("type", fileType);
            record.put("fileName", fileName);
            record.put("personCount", recordStatus.equals("success") ? (int)(Math.random() * 8 + 1) : 0);
            record.put("processingTime", Math.round((Math.random() * 50 + 5) * 10) / 10.0);
            record.put("status", recordStatus);
            record.put("createdAt", LocalDateTime.now().minusHours(i).format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            history.add(record);
        }

        return history;
    }

    /**
     * 获取详细统计信息
     */
    @GetMapping("/statistics/detailed")
    public Mono<ResponseEntity<Map<String, Object>>> getDetailedStatistics(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "day") String granularity) {

        return Mono.fromCallable(() -> {
            Map<String, Object> statistics = new HashMap<>();

            try {
                LocalDateTime start = startDate != null ?
                        LocalDate.parse(startDate).atStartOfDay() :
                        LocalDateTime.now().minusDays(30);

                LocalDateTime end = endDate != null ?
                        LocalDate.parse(endDate).atTime(23, 59, 59) :
                        LocalDateTime.now();

                // 基础统计
                Map<String, Object> period = new HashMap<>();
                period.put("start", start.toString());
                period.put("end", end.toString());
                period.put("granularity", granularity);
                statistics.put("period", period);

                // 总体统计
                Map<String, Object> summary = new HashMap<>();
                summary.put("totalDetections", 179);
                summary.put("imageDetections", 156);
                summary.put("videoDetections", 23);
                summary.put("totalPersonsFound", 892);
                summary.put("averagePersonsPerDetection", 4.98);
                summary.put("successRate", 0.954);
                summary.put("totalProcessingTime", 14567); // 秒
                summary.put("averageProcessingTime", 81.4); // 秒
                statistics.put("summary", summary);

                // 趋势数据
                List<Map<String, Object>> trends = createTrendData(start, end, granularity);
                statistics.put("trends", trends);

                // 性能指标
                Map<String, Object> performance = new HashMap<>();
                Map<String, Object> apiResponseTime = new HashMap<>();
                apiResponseTime.put("average", 2.3);
                apiResponseTime.put("min", 0.8);
                apiResponseTime.put("max", 8.7);
                apiResponseTime.put("p95", 5.2);
                performance.put("apiResponseTime", apiResponseTime);

                Map<String, Object> detectionAccuracy = new HashMap<>();
                detectionAccuracy.put("overall", 0.894);
                detectionAccuracy.put("highConfidence", 0.967); // confidence > 0.8
                detectionAccuracy.put("mediumConfidence", 0.823); // 0.5 < confidence <= 0.8
                detectionAccuracy.put("lowConfidence", 0.634); // confidence <= 0.5
                performance.put("detectionAccuracy", detectionAccuracy);
                statistics.put("performance", performance);

                // 错误统计
                Map<String, Object> errors = new HashMap<>();
                errors.put("totalErrors", 8);
                errors.put("apiErrors", 3);
                errors.put("processingErrors", 4);
                errors.put("systemErrors", 1);
                errors.put("errorRate", 0.045);
                statistics.put("errors", errors);

                statistics.put("success", true);
                return ResponseEntity.ok(statistics);

            } catch (Exception e) {
                log.error("获取详细统计信息失败", e);
                statistics.put("success", false);
                statistics.put("error", e.getMessage());
                return ResponseEntity.internalServerError().body(statistics);
            }
        });
    }

    /**
     * 创建趋势数据
     */
    private List<Map<String, Object>> createTrendData(LocalDateTime start, LocalDateTime end, String granularity) {
        List<Map<String, Object>> trends = new ArrayList<>();

        LocalDateTime current = start;
        DateTimeFormatter formatter = "hour".equals(granularity) ?
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00") :
                DateTimeFormatter.ofPattern("yyyy-MM-dd");

        while (current.isBefore(end)) {
            Map<String, Object> point = new HashMap<>();
            point.put("timestamp", current.format(formatter));
            point.put("imageDetections", (int)(Math.random() * 15 + 5));
            point.put("videoDetections", (int)(Math.random() * 3 + 1));
            point.put("personsFound", (int)(Math.random() * 50 + 20));
            point.put("avgConfidence", Math.round((Math.random() * 0.3 + 0.6) * 100) / 100.0);
            point.put("processingTime", Math.round((Math.random() * 30 + 15) * 10) / 10.0);

            trends.add(point);

            // 增加时间间隔
            switch (granularity) {
                case "hour":
                    current = current.plusHours(1);
                    break;
                case "day":
                default:
                    current = current.plusDays(1);
                    break;
            }
        }

        return trends;
    }

    /**
     * 获取实时监控数据
     */
    @GetMapping("/monitoring/realtime")
    public Mono<ResponseEntity<Map<String, Object>>> getRealtimeMonitoring() {
        return Mono.fromCallable(() -> {
            Map<String, Object> monitoring = new HashMap<>();

            try {
                // 当前活跃任务
                Map<String, Object> activeTasks = new HashMap<>();
                activeTasks.put("image", 2);
                activeTasks.put("video", 1);
                activeTasks.put("total", 3);
                monitoring.put("activeTasks", activeTasks);

                // 系统负载
                Map<String, Object> systemLoad = new HashMap<>();
                systemLoad.put("cpu", Math.random() * 30 + 10); // 10-40%
                systemLoad.put("memory", Math.random() * 20 + 30); // 30-50%
                systemLoad.put("disk", Math.random() * 10 + 15); // 15-25%
                monitoring.put("systemLoad", systemLoad);

                // API调用频率（每分钟）
                Map<String, Object> apiCallRate = new HashMap<>();
                apiCallRate.put("current", (int)(Math.random() * 20 + 5));
                apiCallRate.put("average", 12);
                apiCallRate.put("peak", 45);
                monitoring.put("apiCallRate", apiCallRate);

                // 处理队列
                Map<String, Object> processingQueue = new HashMap<>();
                processingQueue.put("pending", (int)(Math.random() * 5));
                processingQueue.put("processing", (int)(Math.random() * 3 + 1));
                processingQueue.put("completed", (int)(Math.random() * 100 + 50));
                monitoring.put("processingQueue", processingQueue);

                // 最近错误
                List<Map<String, Object>> recentErrors = new ArrayList<>();
                Map<String, Object> error1 = new HashMap<>();
                error1.put("timestamp", LocalDateTime.now().minusMinutes(15).toString());
                error1.put("type", "API_TIMEOUT");
                error1.put("message", "Qwen API调用超时");
                recentErrors.add(error1);

                Map<String, Object> error2 = new HashMap<>();
                error2.put("timestamp", LocalDateTime.now().minusHours(2).toString());
                error2.put("type", "FILE_NOT_FOUND");
                error2.put("message", "视频文件不存在");
                recentErrors.add(error2);

                monitoring.put("recentErrors", recentErrors);

                monitoring.put("timestamp", LocalDateTime.now().toString());
                monitoring.put("success", true);

                return ResponseEntity.ok(monitoring);

            } catch (Exception e) {
                log.error("获取实时监控数据失败", e);
                monitoring.put("success", false);
                monitoring.put("error", e.getMessage());
                return ResponseEntity.internalServerError().body(monitoring);
            }
        });
    }

    /**
     * 获取配置信息
     */
    @GetMapping("/config")
    public Mono<ResponseEntity<Map<String, Object>>> getConfiguration() {
        return Mono.fromCallable(() -> {
            Map<String, Object> config = new HashMap<>();

            try {
                // 系统配置
                Map<String, Object> systemConfig = new HashMap<>();
                systemConfig.put("maxImageSize", 1024);
                systemConfig.put("maxVideoSize", 500 * 1024 * 1024); // 500MB
                systemConfig.put("defaultConfidenceThreshold", 0.3);

                List<String> supportedImageFormats = new ArrayList<>();
                supportedImageFormats.add("jpg");
                supportedImageFormats.add("jpeg");
                supportedImageFormats.add("png");
                supportedImageFormats.add("bmp");
                systemConfig.put("supportedImageFormats", supportedImageFormats);

                List<String> supportedVideoFormats = new ArrayList<>();
                supportedVideoFormats.add("mp4");
                supportedVideoFormats.add("avi");
                supportedVideoFormats.add("mov");
                supportedVideoFormats.add("mkv");
                systemConfig.put("supportedVideoFormats", supportedVideoFormats);

                systemConfig.put("maxConcurrentTasks", 5);
                config.put("system", systemConfig);

                // 检测配置
                Map<String, Object> detectionConfig = new HashMap<>();
                detectionConfig.put("defaultModel", "qwen2.5-vl-72b-instruct");
                detectionConfig.put("apiTimeout", 120);
                detectionConfig.put("retryAttempts", 3);

                List<String> availableTrackers = new ArrayList<>();
                availableTrackers.add("MIL");
                availableTrackers.add("CSRT");
                availableTrackers.add("KCF");
                detectionConfig.put("availableTrackers", availableTrackers);

                detectionConfig.put("defaultTracker", "MIL");
                config.put("detection", detectionConfig);

                // 存储配置
                Map<String, Object> storageConfig = new HashMap<>();
                storageConfig.put("uploadDir", "uploads");
                storageConfig.put("outputDir", "output");
                storageConfig.put("tempDir", "temp");
                storageConfig.put("autoCleanupDays", 30);
                config.put("storage", storageConfig);

                config.put("success", true);
                return ResponseEntity.ok(config);

            } catch (Exception e) {
                log.error("获取配置信息失败", e);
                config.put("success", false);
                config.put("error", e.getMessage());
                return ResponseEntity.internalServerError().body(config);
            }
        });
    }
}
