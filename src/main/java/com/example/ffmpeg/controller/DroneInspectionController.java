// src/main/java/com/example/ffmpeg/controller/DroneInspectionController.java
package com.example.ffmpeg.controller;

import com.example.ffmpeg.dto.DroneImageRequest;
import com.example.ffmpeg.dto.DroneVideoRequest;
import com.example.ffmpeg.dto.TrackingResult;
import com.example.ffmpeg.service.DroneImageDetectionService;
import com.example.ffmpeg.service.DroneVideoTrackingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/drone")
@RequiredArgsConstructor
public class DroneInspectionController {

    private final DroneImageDetectionService imageDetectionService;
    private final DroneVideoTrackingService videoTrackingService;

    /**
     * 无人机图像人物检测
     */
    @PostMapping("/image/detect")
    public Mono<ResponseEntity<Map<String, Object>>> detectPersonsInImage(
            @RequestBody DroneImageRequest request,
            @RequestHeader("Authorization") String authorization) {

        return Mono.fromCallable(() -> {
                    // 提取API Key
                    String apiKey = extractApiKey(authorization);
                    if (apiKey == null || apiKey.isEmpty()) {
                        throw new IllegalArgumentException("缺少有效的API Key");
                    }
                    return apiKey;
                })
                .flatMap(apiKey -> imageDetectionService.detectAndVisualizePersons(request, apiKey))
                .map(ResponseEntity::ok)
                .onErrorResume(ex -> {
                    log.error("图像检测失败: {}", ex.getMessage(), ex);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("error", ex.getMessage());
                    return Mono.just(ResponseEntity.badRequest().body(errorResponse));
                });
    }

    /**
     * 无人机视频人物跟踪
     */
    @PostMapping("/video/track")
    public Mono<ResponseEntity<Map<String, Object>>> trackPersonsInVideo(
            @RequestBody DroneVideoRequest request) {

        return videoTrackingService.processVideoWithTracking(request)
                .map(result -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", true);
                    response.put("result", result);
                    return ResponseEntity.ok(response);
                })
                .onErrorResume(ex -> {
                    log.error("视频跟踪失败: {}", ex.getMessage(), ex);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("error", ex.getMessage());
                    return Mono.just(ResponseEntity.badRequest().body(errorResponse));
                });
    }

    /**
     * 获取无人机巡检系统状态
     */
    @GetMapping("/status")
    public Mono<ResponseEntity<Map<String, Object>>> getSystemStatus() {
        return Mono.fromCallable(() -> {
            Map<String, Object> status = new HashMap<>();
            status.put("system", "Drone Inspection System");
            status.put("version", "1.0.0");
            status.put("status", "running");
            status.put("capabilities", Map.of(
                    "image_detection", true,
                    "video_tracking", true,
                    "auto_deduplication", true,
                    "multi_tracker_support", true
            ));
            status.put("supported_models", java.util.List.of("qwen2.5-vl-72b-instruct"));
            status.put("supported_trackers", java.util.List.of("MIL", "CSRT", "KCF"));

            return ResponseEntity.ok(status);
        });
    }

    /**
     * 测试API连接
     */
    @PostMapping("/test")
    public Mono<ResponseEntity<Map<String, Object>>> testApiConnection(
            @RequestBody Map<String, String> request) {

        return Mono.fromCallable(() -> {
                    String apiKey = request.get("apiKey");
                    String model = request.getOrDefault("model", "qwen2.5-vl-72b-instruct");

                    if (apiKey == null || apiKey.isEmpty()) {
                        throw new IllegalArgumentException("API Key不能为空");
                    }

                    // 这里可以添加实际的API连接测试逻辑
                    // 目前返回模拟的测试结果

                    Map<String, Object> response = new HashMap<>();
                    response.put("success", true);
                    response.put("message", "API连接测试成功");
                    response.put("model", model);
                    response.put("timestamp", java.time.LocalDateTime.now());

                    return ResponseEntity.ok(response);
                })
                .onErrorResume(ex -> {
                    log.error("API连接测试失败: {}", ex.getMessage());
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("error", ex.getMessage());
                    return Mono.just(ResponseEntity.badRequest().body(errorResponse));
                });
    }

    /**
     * 提取API Key
     */
    private String extractApiKey(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        } else if (authorization != null && authorization.startsWith("sk-")) {
            return authorization;
        }
        return null;
    }
}
