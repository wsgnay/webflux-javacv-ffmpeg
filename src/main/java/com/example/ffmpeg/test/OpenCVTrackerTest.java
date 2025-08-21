package com.example.ffmpeg.test;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_tracking;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_tracking.*;
import org.springframework.stereotype.Component;

/**
 * OpenCV跟踪器可用性测试
 */
@Slf4j
@Component
public class OpenCVTrackerTest {

    /**
     * 测试OpenCV跟踪器是否可用
     */
    public void testTrackersAvailability() {
        log.info("=== OpenCV跟踪器可用性测试 ===");

        // 测试MIL跟踪器
        testTracker("MIL", () -> {
            try {
                return TrackerMIL.create();
            } catch (Exception e) {
                log.error("MIL跟踪器不可用: {}", e.getMessage());
                return null;
            }
        });

        // 测试KCF跟踪器
        testTracker("KCF", () -> {
            try {
                return TrackerKCF.create();
            } catch (Exception e) {
                log.error("KCF跟踪器不可用: {}", e.getMessage());
                return null;
            }
        });

        // 测试CSRT跟踪器
        testTracker("CSRT", () -> {
            try {
                return TrackerCSRT.create();
            } catch (Exception e) {
                log.error("CSRT跟踪器不可用: {}", e.getMessage());
                return null;
            }
        });

        // 测试GOTURN跟踪器（可能需要额外的模型文件）
        testTracker("GOTURN", () -> {
            try {
                return TrackerGOTURN.create();
            } catch (Exception e) {
                log.warn("GOTURN跟踪器不可用（可能需要模型文件）: {}", e.getMessage());
                return null;
            }
        });

        log.info("=== 跟踪器测试完成 ===");
    }

    /**
     * 测试单个跟踪器
     */
    private void testTracker(String name, TrackerFactory factory) {
        try {
            Tracker tracker = factory.create();
            if (tracker != null) {
                log.info("✅ {} 跟踪器创建成功", name);

                // 创建测试图像和边界框
                Mat testImage = new Mat(480, 640, opencv_core.CV_8UC3);
                Rect2d testBbox = new Rect2d(100, 100, 50, 50);

                // 测试初始化
                boolean initSuccess = tracker.init(testImage, testBbox);
                if (initSuccess) {
                    log.info("✅ {} 跟踪器初始化成功", name);
                } else {
                    log.warn("⚠️ {} 跟踪器初始化失败", name);
                }

                // 测试更新
                Rect2d updateBbox = new Rect2d();
                boolean updateSuccess = tracker.update(testImage, updateBbox);
                if (updateSuccess) {
                    log.info("✅ {} 跟踪器更新成功", name);
                } else {
                    log.warn("⚠️ {} 跟踪器更新失败（这在测试图像上是正常的）", name);
                }

                // 清理
                testImage.close();

            } else {
                log.error("❌ {} 跟踪器创建失败", name);
            }
        } catch (Exception e) {
            log.error("❌ {} 跟踪器测试异常: {}", name, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface TrackerFactory {
        Tracker create() throws Exception;
    }

    /**
     * 获取推荐的跟踪器类型
     */
    public String getRecommendedTracker() {
        // 按性能和稳定性排序的推荐列表
        String[] trackerPriority = {"MIL", "KCF", "CSRT"};

        for (String trackerType : trackerPriority) {
            if (isTrackerAvailable(trackerType)) {
                log.info("推荐使用跟踪器: {}", trackerType);
                return trackerType;
            }
        }

        log.warn("没有可用的跟踪器，将使用fallback实现");
        return "FALLBACK";
    }

    /**
     * 检查指定跟踪器是否可用
     */
    public boolean isTrackerAvailable(String trackerType) {
        try {
            Tracker tracker = null;
            switch (trackerType.toUpperCase()) {
                case "MIL":
                    tracker = TrackerMIL.create();
                    break;
                case "KCF":
                    tracker = TrackerKCF.create();
                    break;
                case "CSRT":
                    tracker = TrackerCSRT.create();
                    break;
                case "GOTURN":
                    tracker = TrackerGOTURN.create();
                    break;
                default:
                    return false;
            }

            if (tracker != null) {
                // 简单测试
                Mat testImage = new Mat(100, 100, opencv_core.CV_8UC3);
                Rect2d testBbox = new Rect2d(10, 10, 20, 20);
                boolean success = tracker.init(testImage, testBbox);
                testImage.close();
                return success;
            }
            return false;
        } catch (Exception e) {
            log.debug("跟踪器 {} 不可用: {}", trackerType, e.getMessage());
            return false;
        }
    }
}
