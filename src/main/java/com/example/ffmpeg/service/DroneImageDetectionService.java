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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DroneImageDetectionService {

    private final QwenApiService qwenApiService;

    private static final Color[] COLORS = {
            Color.GREEN, Color.BLUE, Color.RED, Color.CYAN, Color.MAGENTA, Color.YELLOW
    };

    /**
     * 检测并可视化无人机图像中的人物
     */
    public Mono<Map<String, Object>> detectAndVisualizePersons(DroneImageRequest request, String apiKey) {
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
                .flatMap(detections -> drawDetections(request, detections))
                .onErrorResume(ex -> {
                    log.error("图像检测失败: {}", ex.getMessage(), ex);
                    Map<String, Object> errorResult = new HashMap<>();
                    errorResult.put("success", false);
                    errorResult.put("error", ex.getMessage());
                    return Mono.just(errorResult);
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
                BufferedImage image = ImageIO.read(new File(request.getImagePath()));
                if (image == null) {
                    throw new RuntimeException("无法读取图片: " + request.getImagePath());
                }

                // 创建Graphics2D对象用于绘制
                Graphics2D g2d = image.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // 绘制每个检测结果
                for (int i = 0; i < detections.size(); i++) {
                    PersonDetection detection = detections.get(i);
                    double[] bbox = detection.getBbox();
                    double confidence = detection.getConfidence();
                    String description = detection.getDescription();

                    // 确保坐标在图片范围内
                    int width = image.getWidth();
                    int height = image.getHeight();
                    int x1 = Math.max(0, Math.min((int) bbox[0], width - 1));
                    int y1 = Math.max(0, Math.min((int) bbox[1], height - 1));
                    int x2 = Math.max(x1 + 1, Math.min((int) bbox[2], width));
                    int y2 = Math.max(y1 + 1, Math.min((int) bbox[3], height));

                    // 选择颜色
                    Color color = COLORS[i % COLORS.length];

                    // 绘制边界框（加粗便于在航拍图中看清）
                    g2d.setColor(color);
                    g2d.setStroke(new BasicStroke(3.0f));
                    g2d.drawRect(x1, y1, x2 - x1, y2 - y1);

                    // 绘制标签
                    String label = String.format("Person #%d (%.2f)", i + 1, confidence);
                    FontMetrics fm = g2d.getFontMetrics();
                    int labelWidth = fm.stringWidth(label);
                    int labelHeight = fm.getHeight();

                    // 绘制标签背景
                    g2d.fillRect(x1, y1 - labelHeight - 10, labelWidth, labelHeight + 10);

                    // 绘制标签文字
                    g2d.setColor(Color.WHITE);
                    g2d.drawString(label, x1, y1 - 5);

                    // 在人物中心画一个小圆点，更容易看到
                    int centerX = (x1 + x2) / 2;
                    int centerY = (y1 + y2) / 2;
                    g2d.setColor(color);
                    g2d.fillOval(centerX - 4, centerY - 4, 8, 8);
                }

                // 添加统计信息
                String infoText = String.format("检测到 %d 个人物 (无人机视角)", detections.size());
                g2d.setColor(Color.GREEN);
                g2d.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
                g2d.drawString(infoText, 10, 40);

                // 添加置信度阈值信息
                String thresholdText = String.format("置信度阈值: %.2f", request.getConfThreshold());
                g2d.setColor(Color.WHITE);
                g2d.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
                g2d.drawString(thresholdText, 10, 80);

                g2d.dispose();

                // 保存结果图片
                if (request.getOutputPath() != null) {
                    // 创建输出目录
                    Path outputPath = Paths.get(request.getOutputPath());
                    Files.createDirectories(outputPath.getParent());

                    ImageIO.write(image, "png", new File(request.getOutputPath()));
                    log.info("检测结果已保存: {}", request.getOutputPath());
                }

                // 构建结果
                result.put("success", true);
                result.put("detections", detections);
                result.put("totalPersons", detections.size());
                result.put("outputPath", request.getOutputPath());
                result.put("confidenceThreshold", request.getConfThreshold());

                // 详细检测信息
                for (int i = 0; i < detections.size(); i++) {
                    PersonDetection detection = detections.get(i);
                    double[] bbox = detection.getBbox();
                    double width = bbox[2] - bbox[0];
                    double height = bbox[3] - bbox[1];
                    log.info("人物 {}: 位置[{:.0f}, {:.0f}, {:.0f}, {:.0f}] 尺寸[{:.0f}x{:.0f}] 置信度: {:.2f} 描述: {}",
                            i + 1, bbox[0], bbox[1], bbox[2], bbox[3], width, height,
                            detection.getConfidence(), detection.getDescription());
                }

            } catch (Exception e) {
                log.error("绘制检测结果错误: {}", e.getMessage(), e);
                result.put("success", false);
                result.put("error", e.getMessage());
            }

            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
