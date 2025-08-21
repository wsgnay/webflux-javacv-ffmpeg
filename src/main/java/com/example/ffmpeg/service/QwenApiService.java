// src/main/java/com/example/ffmpeg/service/QwenApiService.java
package com.example.ffmpeg.service;

import com.example.ffmpeg.dto.PersonDetection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class QwenApiService {

    private static final String BASE_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    private static final String DETECTION_PROMPT = """
        这是一张无人机航拍视频帧，请检测图像中的所有人物。
        由于是航拍视角，人物可能会显得很小，请仔细观察：
        1. 注意人的形状特征：头部、身体轮廓
        2. 区分人物和其他小物体（如车辆、动物、垃圾桶等）
        3. 即使人物很小也要标注出来
        4. 特别注意可能新出现的人物或之前遗漏的人物
        5. 仔细检查画面边缘和阴影区域

        请按以下JSON格式返回结果：
        {
            "persons": [
                {
                    "id": 1,
                    "bbox": [x1, y1, x2, y2],
                    "confidence": 0.85,
                    "type": "person"
                }
            ]
        }

        其中bbox格式为[左上角x, 左上角y, 右下角x, 右下角y]，坐标为像素值。
        """;

    private static final String IMAGE_DETECTION_PROMPT = """
        这是一张无人机航拍图片，请检测图像中的所有人物。
        由于是航拍视角，人物可能会显得很小，请仔细观察：
        1. 注意人的形状特征：头部、身体轮廓
        2. 区分人物和其他小物体（如车辆、动物等）
        3. 即使人物很小也要标注出来

        请按以下JSON格式返回结果：
        {
            "persons": [
                {
                    "id": 1,
                    "bbox": [x1, y1, x2, y2],
                    "confidence": 0.85,
                    "description": "人物描述"
                }
            ]
        }

        其中bbox格式为[左上角x, 左上角y, 右下角x, 右下角y]，坐标为像素值。
        """;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public QwenApiService() {
        this.webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 检测图像中的人物
     */
    public Mono<List<PersonDetection>> detectPersonsInImage(String imagePath, String apiKey,
                                                            String model, int maxImageSize,
                                                            double confThreshold, int timeout) {
        return Mono.fromCallable(() -> imageToBase64(imagePath, maxImageSize))
                .flatMap(base64Data -> {
                    Map<String, Object> requestBody = buildImageRequestBody(base64Data.base64, model, IMAGE_DETECTION_PROMPT);

                    return webClient.post()
                            .header("Authorization", "Bearer " + apiKey)
                            .bodyValue(requestBody)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(timeout))
                            .map(response -> parseDetectionResult(response, base64Data.scaleFactor, confThreshold))
                            .onErrorResume(ex -> {
                                log.error("Qwen API调用失败: {}", ex.getMessage());
                                return Mono.just(new ArrayList<>());
                            });
                });
    }

    /**
     * 检测视频帧中的人物
     */
    public Mono<List<PersonDetection>> detectPersonsInFrame(BufferedImage frame, String apiKey,
                                                            String model, int maxImageSize,
                                                            double confThreshold, int timeout,
                                                            int frameNumber) {
        return Mono.fromCallable(() -> frameToBase64(frame, maxImageSize))
                .flatMap(base64Data -> {
                    Map<String, Object> requestBody = buildImageRequestBody(base64Data.base64, model, DETECTION_PROMPT);

                    return webClient.post()
                            .header("Authorization", "Bearer " + apiKey)
                            .bodyValue(requestBody)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(timeout))
                            .map(response -> parseDetectionResult(response, base64Data.scaleFactor, confThreshold))
                            .doOnSuccess(detections ->
                                    log.info("第 {} 帧检测到 {} 个人物", frameNumber, detections.size()))
                            .onErrorResume(ex -> {
                                log.error("第 {} 帧Qwen API调用失败: {}", frameNumber, ex.getMessage());
                                return Mono.just(new ArrayList<>());
                            });
                });
    }

    /**
     * 图像转Base64
     */
    private Base64Data imageToBase64(String imagePath, int maxImageSize) throws IOException {
        BufferedImage originalImage = ImageIO.read(new File(imagePath));
        if (originalImage == null) {
            throw new IOException("无法读取图像: " + imagePath);
        }

        return processImageToBase64(originalImage, maxImageSize);
    }

    /**
     * 视频帧转Base64
     */
    private Base64Data frameToBase64(BufferedImage frame, int maxImageSize) throws IOException {
        return processImageToBase64(frame, maxImageSize);
    }

    /**
     * 处理图像并转换为Base64
     */
    private Base64Data processImageToBase64(BufferedImage originalImage, int maxImageSize) throws IOException {
        int originalWidth = originalImage.getWidth();
        int originalHeight = originalImage.getHeight();

        // 计算缩放比例
        double scale = Math.min((double) maxImageSize / originalWidth, (double) maxImageSize / originalHeight);

        BufferedImage processedImage = originalImage;
        if (scale < 1.0) {
            int newWidth = (int) (originalWidth * scale);
            int newHeight = (int) (originalHeight * scale);
            processedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            processedImage.getGraphics().drawImage(originalImage.getScaledInstance(newWidth, newHeight,
                    java.awt.Image.SCALE_SMOOTH), 0, 0, null);
            log.info("图像已缩放: {}x{} -> {}x{}", originalWidth, originalHeight, newWidth, newHeight);
        } else {
            log.info("图像尺寸: {}x{}", originalWidth, originalHeight);
        }

        // 转换为Base64
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(processedImage, "JPEG", baos);
        String base64 = Base64.encodeBase64String(baos.toByteArray());

        return new Base64Data(base64, scale);
    }

    /**
     * 简化的视频帧检测方法（用于DroneVideoTrackingService）
     */
    public Mono<List<PersonDetection>> detectPersonsInFrame(BufferedImage frame, String apiKey,
                                                            double confThreshold, int timeout) {
        return detectPersonsInFrame(frame, apiKey, "qwen2.5-vl-72b-instruct",
                1024, confThreshold, timeout, 0);
    }

    /**
     * 构建API请求体
     */
    private Map<String, Object> buildImageRequestBody(String base64Image, String model, String prompt) {
        Map<String, Object> content1 = new HashMap<>();
        content1.put("image", "data:image/jpeg;base64," + base64Image);

        Map<String, Object> content2 = new HashMap<>();
        content2.put("text", prompt);

        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", List.of(content1, content2));

        Map<String, Object> input = new HashMap<>();
        input.put("messages", List.of(message));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("result_format", "message");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", input);
        requestBody.put("parameters", parameters);

        return requestBody;
    }

    // 在 QwenApiService.java 类中添加以下方法
// 添加到类的末尾，在其他公共方法之后

    /**
     * 测试API连接
     */
    public Mono<Boolean> testConnection(String apiKey, String model) {
        log.info("开始测试Qwen API连接，模型: {}", model);

        // 创建一个简单的测试请求 - 使用文本模式而不是视觉模式
        Map<String, Object> testInput = new HashMap<>();
        testInput.put("prompt", "你好，请回复'测试成功'");

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("result_format", "message");
        parameters.put("max_tokens", 10);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", testInput);
        requestBody.put("parameters", parameters);

        return webClient.post()
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .map(response -> {
                    try {
                        log.debug("API测试响应: {}", response);
                        // 尝试解析响应以验证格式正确性
                        JsonNode root = objectMapper.readTree(response);

                        // 检查是否有错误
                        if (root.has("code")) {
                            String errorCode = root.get("code").asText();
                            String errorMessage = root.path("message").asText("未知错误");
                            log.warn("API测试失败，错误代码: {}, 错误信息: {}", errorCode, errorMessage);
                            return false;
                        }

                        // 检查是否有正常的输出结构
                        if (root.has("output")) {
                            JsonNode outputNode = root.get("output");
                            if (outputNode.has("choices")) {
                                log.info("API连接测试成功");
                                return true;
                            }
                        }

                        log.warn("API响应格式异常，没有找到预期的结构");
                        return false;

                    } catch (JsonProcessingException e) {
                        log.error("解析API测试响应失败: {}", e.getMessage());
                        return false;
                    }
                })
                .onErrorResume(throwable -> {
                    log.error("API连接测试失败: {}", throwable.getMessage());

                    // 分析具体的错误类型
                    String errorMessage = throwable.getMessage();
                    if (errorMessage != null) {
                        if (errorMessage.contains("401") || errorMessage.contains("Unauthorized")) {
                            log.error("API Key无效或已过期");
                        } else if (errorMessage.contains("timeout") || errorMessage.contains("TimeoutException")) {
                            log.error("API请求超时，可能是网络问题");
                        } else if (errorMessage.contains("403") || errorMessage.contains("Forbidden")) {
                            log.error("API访问被禁止，请检查权限");
                        } else if (errorMessage.contains("429") || errorMessage.contains("Too Many Requests")) {
                            log.error("API请求频率过高，请稍后重试");
                        } else if (errorMessage.contains("500") || errorMessage.contains("Internal Server Error")) {
                            log.error("API服务器内部错误");
                        }
                    }

                    return Mono.just(false);
                });
    }

    /**
     * 解析检测结果
     */
    private List<PersonDetection> parseDetectionResult(String responseText, double scaleFactor, double confThreshold) {
        List<PersonDetection> detections = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(responseText);
            JsonNode outputNode = root.path("output");
            JsonNode choicesNode = outputNode.path("choices");

            if (choicesNode.isArray() && choicesNode.size() > 0) {
                JsonNode messageNode = choicesNode.get(0).path("message");
                String content = messageNode.path("content").asText();

                // 如果content是数组，取第一个元素的text
                if (messageNode.path("content").isArray()) {
                    JsonNode contentArray = messageNode.path("content");
                    if (contentArray.size() > 0) {
                        content = contentArray.get(0).path("text").asText();
                    }
                }

                detections = parseJsonFromText(content, scaleFactor, confThreshold);
            }
        } catch (JsonProcessingException e) {
            log.error("解析API响应失败: {}", e.getMessage());
            // 尝试正则表达式解析
            detections = parseWithRegex(responseText, scaleFactor, confThreshold);
        }

        log.info("最终检测结果: {} 个人物", detections.size());
        return detections;
    }

    /**
     * 从文本中解析JSON
     */
    private List<PersonDetection> parseJsonFromText(String text, double scaleFactor, double confThreshold) {
        List<PersonDetection> detections = new ArrayList<>();

        try {
            // 提取JSON代码块
            Pattern jsonPattern = Pattern.compile("```json\\s*(.*?)\\s*```", Pattern.DOTALL);
            Matcher matcher = jsonPattern.matcher(text);

            String jsonStr;
            if (matcher.find()) {
                jsonStr = matcher.group(1).trim();
            } else {
                // 如果没有代码块，尝试直接提取JSON
                Pattern directJsonPattern = Pattern.compile("\\{.*\\}", Pattern.DOTALL);
                Matcher directMatcher = directJsonPattern.matcher(text);
                if (directMatcher.find()) {
                    jsonStr = directMatcher.group();
                } else {
                    log.warn("未找到有效的JSON格式");
                    return detections;
                }
            }

            JsonNode jsonNode = objectMapper.readTree(jsonStr);
            JsonNode personsNode = jsonNode.has("persons") ? jsonNode.get("persons") : jsonNode.get("people");

            if (personsNode != null && personsNode.isArray()) {
                for (JsonNode personNode : personsNode) {
                    JsonNode bboxNode = personNode.get("bbox");
                    if (bboxNode != null && bboxNode.isArray() && bboxNode.size() == 4) {
                        double[] bbox = new double[4];
                        for (int i = 0; i < 4; i++) {
                            bbox[i] = bboxNode.get(i).asDouble();
                        }

                        // 验证坐标有效性
                        if (bbox[0] >= 0 && bbox[1] >= 0 && bbox[2] > bbox[0] && bbox[3] > bbox[1]) {
                            double confidence = personNode.path("confidence").asDouble(0.8);
                            String description = personNode.path("description").asText("person");

                            // 过滤低置信度检测
                            if (confidence >= confThreshold) {
                                // 坐标缩放回原始尺寸
                                if (scaleFactor != 1.0) {
                                    for (int i = 0; i < 4; i++) {
                                        bbox[i] /= scaleFactor;
                                    }
                                }

                                PersonDetection detection = new PersonDetection(bbox, confidence, description);
                                detections.add(detection);
                                log.info("检测到人物: {}, 置信度: {}, 描述: {}",
                                        java.util.Arrays.toString(bbox), confidence, description);
                            } else {
                                log.info("置信度过低，跳过: {}, 置信度: {}",
                                        java.util.Arrays.toString(bbox), confidence);
                            }
                        } else {
                            log.warn("无效的边界框: {}", java.util.Arrays.toString(bbox));
                        }
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.error("JSON解析错误: {}", e.getMessage());
            // 回退到正则表达式解析
            return parseWithRegex(text, scaleFactor, confThreshold);
        }

        return detections;
    }

    /**
     * 使用正则表达式解析坐标
     */
    private List<PersonDetection> parseWithRegex(String text, double scaleFactor, double confThreshold) {
        List<PersonDetection> detections = new ArrayList<>();

        Pattern coordPattern = Pattern.compile("\\[(\\d+),\\s*(\\d+),\\s*(\\d+),\\s*(\\d+)\\]");
        Matcher matcher = coordPattern.matcher(text);

        while (matcher.find()) {
            double[] bbox = new double[4];
            for (int i = 0; i < 4; i++) {
                bbox[i] = Double.parseDouble(matcher.group(i + 1));
            }

            if (bbox[0] >= 0 && bbox[1] >= 0 && bbox[2] > bbox[0] && bbox[3] > bbox[1]) {
                // 坐标缩放回原始尺寸
                if (scaleFactor != 1.0) {
                    for (int i = 0; i < 4; i++) {
                        bbox[i] /= scaleFactor;
                    }
                }

                PersonDetection detection = new PersonDetection(bbox, 0.6, "person");
                detections.add(detection);
            }
        }

        return detections;
    }

    /**
     * Base64数据包装类
     */
    private static class Base64Data {
        final String base64;
        final double scaleFactor;

        Base64Data(String base64, double scaleFactor) {
            this.base64 = base64;
            this.scaleFactor = scaleFactor;
        }
    }
}
