// src/main/java/com/example/ffmpeg/service/DroneImageDetectionService.java
package com.example.ffmpeg.service;

import com.example.ffmpeg.dto.DroneImageRequest;
import com.example.ffmpeg.dto.PersonDetection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DroneImageDetectionService {

    private final QwenApiService qwenApiService;
    private final DatabaseService databaseService;

    private static final Color[] DETECTION_COLORS = {
            Color.GREEN, Color.BLUE, Color.RED, Color.CYAN,
            Color.MAGENTA, Color.YELLOW, Color.ORANGE, Color.PINK
    };

    /**
     * 检测并可视化无人机图像中的人物
     */
    public Mono<Map<String, Object>> detectAndVisualizePersons(DroneImageRequest request, String apiKey) {
        long startTime = System.currentTimeMillis();

        return Mono.fromCallable(() -> {
                    log.info("开始检测无人机图片中的人物: {}", request.getImagePath());

                    // 检查文件是否存在
                    Path imagePath = Paths.get(request.getImagePath());
                    if (!Files.exists(imagePath)) {
                        throw new IllegalArgumentException("图片文件不存在: " + request.getImagePath());
                    }

                    return request;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(req -> qwenApiService.detectPersonsInImage(
                        req.getImagePath(),
                        apiKey,
                        "qwen2.5-vl-72b-instruct",
                        req.getMaxImageSize(),
                        req.getConfThreshold(),
                        120
                ))
                .flatMap(detections -> {
                    long processingTime = System.currentTimeMillis() - startTime;
                    return processDetectionResult(request, detections, processingTime);
                })
                .onErrorResume(ex -> {
                    log.error("图像检测失败: {}", ex.getMessage(), ex);
                    Map<String, Object> errorResult = new HashMap<>();
                    errorResult.put("success", false);
                    errorResult.put("error", ex.getMessage());
                    return Mono.just(errorResult);
                });
    }

    private Mono<Map<String, Object>> processDetectionResult(DroneImageRequest request,
                                                             List<PersonDetection> detections,
                                                             long processingTime) {
        return drawDetections(request, detections)
                .flatMap(result -> {
                    // 保存到数据库
                    String imageName = Paths.get(request.getImagePath()).getFileName().toString();
                    return databaseService.saveImageDetection(
                            request.getImagePath(),
                            imageName,
                            (String) result.get("outputImagePath"),
                            detections,
                            processingTime,
                            request.getConfThreshold()
                    ).map(savedDetection -> {
                        result.put("detectionId", savedDetection.getId());
                        return result;
                    });
                });
    }

    /**
     * 在图像上绘制检测结果
     */
    private Mono<Map<String, Object>> drawDetections(DroneImageRequest request, List<PersonDetection> detections) {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = new HashMap<>();

            try {
                // 读取原始图片
                BufferedImage originalImage = ImageIO.read(new File(request.getImagePath()));
                if (originalImage == null) {
                    throw new RuntimeException("无法读取图片: " + request.getImagePath());
                }

                // 创建副本用于绘制
                BufferedImage resultImage = new BufferedImage(
                        originalImage.getWidth(),
                        originalImage.getHeight(),
                        BufferedImage.TYPE_INT_RGB
                );
                Graphics2D g2d = resultImage.createGraphics();

                // 启用抗锯齿
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                // 绘制原始图片
                g2d.drawImage(originalImage, 0, 0, null);

                int validDetections = 0;

                // 绘制每个检测结果
                for (int i = 0; i < detections.size(); i++) {
                    PersonDetection detection = detections.get(i);
                    List<Double> bbox = detection.getBbox();
                    double confidence = detection.getConfidence();

                    if (bbox == null || bbox.size() != 4) {
                        log.warn("无效的边界框数据: {}", bbox);
                        continue;
                    }

                    // 确保坐标在图片范围内并且有效
                    int width = originalImage.getWidth();
                    int height = originalImage.getHeight();

                    int x1 = Math.max(0, Math.min(bbox.get(0).intValue(), width - 1));
                    int y1 = Math.max(0, Math.min(bbox.get(1).intValue(), height - 1));
                    int x2 = Math.max(x1 + 1, Math.min(bbox.get(2).intValue(), width));
                    int y2 = Math.max(y1 + 1, Math.min(bbox.get(3).intValue(), height));

                    int rectWidth = x2 - x1;
                    int rectHeight = y2 - y1;

                    // 过滤太小的检测框
                    if (rectWidth < 5 || rectHeight < 5) {
                        log.debug("跳过过小的检测框: {}x{}", rectWidth, rectHeight);
                        continue;
                    }

                    validDetections++;
                    Color detectionColor = DETECTION_COLORS[i % DETECTION_COLORS.length];

                    // 绘制边界框
                    g2d.setColor(detectionColor);
                    g2d.setStroke(new BasicStroke(3.0f));
                    g2d.drawRect(x1, y1, rectWidth, rectHeight);

                    // 准备标签文本
                    String label = String.format("Person %d (%.1f%%)", i + 1, confidence * 100);
                    Font font = new Font("Arial", Font.BOLD, Math.max(12, Math.min(width / 50, 20)));
                    g2d.setFont(font);

                    FontMetrics fm = g2d.getFontMetrics();
                    int labelWidth = fm.stringWidth(label);
                    int labelHeight = fm.getHeight();
                    int labelPadding = 4;

                    // 计算标签位置（确保不超出图片边界）
                    int labelX = x1;
                    int labelY = Math.max(labelHeight, y1 - 2);

                    if (labelX + labelWidth + labelPadding * 2 > width) {
                        labelX = width - labelWidth - labelPadding * 2;
                    }

                    // 绘制标签背景
                    g2d.setColor(new Color(detectionColor.getRed(), detectionColor.getGreen(),
                            detectionColor.getBlue(), 200)); // 半透明背景
                    g2d.fillRect(labelX, labelY - labelHeight + fm.getDescent(),
                            labelWidth + labelPadding * 2, labelHeight);

                    // 绘制标签文字
                    g2d.setColor(Color.WHITE);
                    g2d.drawString(label, labelX + labelPadding, labelY - fm.getDescent());

                    // 可选：绘制置信度条
                    if (confidence > 0) {
                        int barWidth = 60;
                        int barHeight = 6;
                        int barX = x1;
                        int barY = y2 + 5;

                        if (barY + barHeight <= height && barX + barWidth <= width) {
                            // 背景条
                            g2d.setColor(Color.GRAY);
                            g2d.fillRect(barX, barY, barWidth, barHeight);

                            // 置信度条
                            g2d.setColor(detectionColor);
                            g2d.fillRect(barX, barY, (int)(barWidth * confidence), barHeight);
                        }
                    }

                    log.debug("绘制检测结果 {}: 位置[{},{},{},{}], 置信度: {:.3f}",
                            i + 1, x1, y1, x2, y2, confidence);
                }

                g2d.dispose();

                // 生成输出文件路径
                String outputPath = generateOutputPath(request);

                // 保存结果图片
                File outputFile = new File(outputPath);
                File outputDir = outputFile.getParentFile();
                if (outputDir != null && !outputDir.exists()) {
                    outputDir.mkdirs();
                }

                ImageIO.write(resultImage, "JPEG", outputFile);
                log.info("检测结果图片已保存: {}", outputPath);

                // 构建返回结果
                result.put("success", true);
                result.put("totalPersons", validDetections);
                result.put("detections", detections.subList(0, Math.min(validDetections, detections.size())));
                result.put("outputImagePath", outputPath);
                result.put("originalImagePath", request.getImagePath());
                result.put("imageWidth", originalImage.getWidth());
                result.put("imageHeight", originalImage.getHeight());
                result.put("confThreshold", request.getConfThreshold());

                log.info("图像检测完成: 检测到 {} 个有效人物目标", validDetections);

            } catch (Exception e) {
                log.error("绘制检测结果失败", e);
                result.put("success", false);
                result.put("error", "绘制检测结果失败: " + e.getMessage());
            }

            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 生成输出文件路径
     */
    private String generateOutputPath(DroneImageRequest request) {
        if (request.getOutputPath() != null && !request.getOutputPath().isEmpty()) {
            return request.getOutputPath();
        }

        // 自动生成输出路径
        Path inputPath = Paths.get(request.getImagePath());
        String filename = inputPath.getFileName().toString();
        String nameWithoutExt = filename.contains(".") ?
                filename.substring(0, filename.lastIndexOf('.')) : filename;

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputFilename = String.format("%s_detected_%s.jpg", nameWithoutExt, timestamp);

        return Paths.get("output", "images", outputFilename).toString();
    }

    /**
     * 验证检测结果
     */
    private boolean isValidDetection(PersonDetection detection, int imageWidth, int imageHeight) {
        List<Double> bbox = detection.getBbox();
        if (bbox == null || bbox.size() != 4) {
            return false;
        }

        double x1 = bbox.get(0);
        double y1 = bbox.get(1);
        double x2 = bbox.get(2);
        double y2 = bbox.get(3);

        // 检查坐标有效性
        if (x1 < 0 || y1 < 0 || x2 <= x1 || y2 <= y1) {
            return false;
        }

        // 检查是否在图片范围内
        if (x2 > imageWidth || y2 > imageHeight) {
            return false;
        }

        // 检查尺寸是否合理
        double width = x2 - x1;
        double height = y2 - y1;
        if (width < 5 || height < 5) {
            return false;
        }

        return true;
    }
}
