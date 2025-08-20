package com.example.ffmpeg.service.impl;

import com.example.ffmpeg.dto.DroneImageRequest;
import com.example.ffmpeg.dto.PersonDetection;
import com.example.ffmpeg.service.DatabaseService;
import com.example.ffmpeg.service.DroneImageDetectionService;
import com.example.ffmpeg.service.QwenApiService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DroneImageDetectionServiceImpl implements DroneImageDetectionService {

    private final QwenApiService qwenApiService;
    private final DatabaseService databaseService;

    @Override
    public Mono<Map<String, Object>> detectAndVisualizePersons(DroneImageRequest request, String apiKey) {
        long startTime = System.currentTimeMillis();

        return Mono.fromCallable(() -> {
                    log.info("开始检测无人机图片中的人物: {}", request.getImagePath());

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
                            result,
                            detections,
                            processingTime,
                            request.getConfThreshold()
                    ).map(savedDetection -> {
                        result.put("detectionId", savedDetection.getId());
                        return result;
                    });
                });
    }

    private Mono<Map<String, Object>> drawDetections(DroneImageRequest request, List<PersonDetection> detections) {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = new HashMap<>();

            try {
                BufferedImage image = ImageIO.read(new File(request.getImagePath()));
                if (image == null) {
                    throw new RuntimeException("无法读取图片: " + request.getImagePath());
                }

                // 绘制检测结果
                Graphics2D g2d = image.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                Color[] colors = {Color.GREEN, Color.BLUE, Color.RED, Color.CYAN, Color.MAGENTA, Color.YELLOW};

                for (int i = 0; i < detections.size(); i++) {
                    PersonDetection detection = detections.get(i);
                    double[] bbox = detection.getBbox();

                    int x1 = Math.max(0, Math.min((int) bbox[0], image.getWidth() - 1));
                    int y1 = Math.max(0, Math.min((int) bbox[1], image.getHeight() - 1));
                    int x2 = Math.max(x1 + 1, Math.min((int) bbox[2], image.getWidth()));
                    int y2 = Math.max(y1 + 1, Math.min((int) bbox[3], image.getHeight()));

                    Color color = colors[i % colors.length];

                    // 绘制边界框
                    g2d.setColor(color);
                    g2d.setStroke(new BasicStroke(3.0f));
                    g2d.drawRect(x1, y1, x2 - x1, y2 - y1);

                    // 绘制标签
                    String label = String.format("Person #%d (%.2f)", i + 1, detection.getConfidence());
                    FontMetrics fm = g2d.getFontMetrics();
                    int labelWidth = fm.stringWidth(label);
                    int labelHeight = fm.getHeight();

                    g2d.fillRect(x1, y1 - labelHeight - 10, labelWidth, labelHeight + 10);
                    g2d.setColor(Color.WHITE);
                    g2d.drawString(label, x1, y1 - 5);

                    // 绘制中心点
                    int centerX = (x1 + x2) / 2;
                    int centerY = (y1 + y2) / 2;
                    g2d.setColor(color);
                    g2d.fillOval(centerX - 4, centerY - 4, 8, 8);
                }

                // 添加统计信息
                g2d.setColor(Color.GREEN);
                g2d.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
                g2d.drawString(String.format("检测到 %d 个人物 (无人机视角)", detections.size()), 10, 40);

                g2d.dispose();

                // 保存结果图片
                if (request.getOutputPath() != null) {
                    Path outputPath = Paths.get(request.getOutputPath());
                    Files.createDirectories(outputPath.getParent());
                    ImageIO.write(image, "png", new File(request.getOutputPath()));
                }

                result.put("success", true);
                result.put("detections", detections);
                result.put("totalPersons", detections.size());
                result.put("outputPath", request.getOutputPath());
                result.put("confidenceThreshold", request.getConfThreshold());

            } catch (Exception e) {
                log.error("绘制检测结果错误: {}", e.getMessage(), e);
                result.put("success", false);
                result.put("error", e.getMessage());
            }

            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
