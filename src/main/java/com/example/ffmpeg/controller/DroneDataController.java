package com.example.ffmpeg.controller;

import com.example.ffmpeg.service.DatabaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
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
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    /**
     * 获取最近的检测活动
     */
    @GetMapping("/dashboard/recent")
    public Mono<ResponseEntity<Map<String, Object>>> getRecentActivities(
            @RequestParam(defaultValue = "10") int limit) {

        return databaseService.getRecentDetections(limit)
                .collectList()
                .map(activities -> Map.of("recentActivities", activities))
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    /**
     * 获取历史记录
     */
    @GetMapping("/history")
    public Mono<ResponseEntity<Map<String, Object>>> getHistory(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // 这里可以根据参数查询不同的历史记录
        return databaseService.getRecentDetections(size)
                .collectList()
                .map(history -> Map.of(
                        "content", history,
                        "page", page,
                        "size", size,
                        "totalElements", history.size()
                ))
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.badRequest().build());
    }
}
