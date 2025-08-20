package com.example.ffmpeg.dto;

import lombok.Data;
import java.util.List;

@Data
public class DroneVideoRequest {
    private String videoSource;
    private String outputPath;
    private String apiKey;
    private String model = "qwen2.5-vl-72b-instruct";
    private int apiTimeout = 120;
    private double confThreshold = 0.5;
    private boolean showVideo = true;
    private boolean saveVideo = true;
    private int maxImageSize = 640;
    private String trackerType = "MIL";
    private List<Integer> detectionFrames = List.of(1, 60, 150, 300);
    private int minDetectionInterval = 90;

    // 自动去重配置
    private double autoDedupiouThreshold = 0.05;
    private double autoDedupOverlapThreshold = 0.4;
    private double nmsThreshold = 0.3;
    private int minBboxSize = 5;

    // 连续跟踪配置
    private int maxLostFrames = 30;
    private boolean enableBoundaryCheck = true;
    private int boundaryMargin = 20;
    private boolean enableConfidenceDecay = false;
    private int trackerMemoryFrames = 10;

    // 去重策略配置
    private String dedupStrategy = "keep_higher_confidence";
    private boolean enableAutoDedup = true;
}
