// src/main/java/com/example/ffmpeg/dto/DroneImageRequest.java
package com.example.ffmpeg.dto;

import lombok.Data;

@Data
public class DroneImageRequest {
    private String imagePath;
    private String outputPath;
    private boolean showResult = true;
    private double confThreshold = 0.3;
    private int maxImageSize = 1024;
}
