package com.example.ffmpeg.test;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.global.opencv_core;
// --- 修复点 1: 移除多余和错误的 global import ---
// import org.bytedeco.opencv.global.opencv_tracking;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_tracking.*; // 导入主 tracking 模块
import org.bytedeco.opencv.opencv_legacy.*; // --- 修复点 2: 添加 legacy 模块的导入 (最关键) ---
// import org.bytedeco.opencv.opencv_video.Tracker; // --- 修复点 3: 移除错误的 Tracker import ---
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
        testTracker("MIL", TrackerMIL::create);

        // 测试KCF跟踪器
        testTracker("KCF", TrackerKCF::create);

        // 测试CSRT跟踪器
        testTracker("CSRT", TrackerCSRT::create);

        // 测试Boosting跟踪器
        testTracker("BOOSTING", TrackerBoosting::create);

        // 测试TLD跟踪器
        testTracker("TLD", TrackerTLD::create);

        // 测试MedianFlow跟踪器
        testTracker("MEDIANFLOW", TrackerMedianFlow::create);

        // 测试MOSSE跟踪器
        testTracker("MOSSE", TrackerMOSSE::create);

        log.info("=== 跟踪器测试完成 ===");
    }

    /**
     * 测试单个跟踪器
     */
    private void testTracker(String name, TrackerFactory factory) {
        try (
                Tracker tracker = factory.create();
                Mat testImage = new Mat(480, 640, opencv_core.CV_8UC3)
        ){
            if (tracker != null) {
                log.info("✅ {} 跟踪器创建成功", name);

                // 创建测试图像和边界框
                Rect2d testBbox2d = new Rect2d(100, 100, 50, 50);
                Rect testBbox = new Rect((int)testBbox2d.x(), (int)testBbox2d.y(), (int)testBbox2d.width(), (int)testBbox2d.height());

                // 测试初始化
                tracker.init(testImage, testBbox);
                log.info("✅ {} 跟踪器初始化成功", name);

                // 测试更新
                Rect updateBbox = new Rect();
                // --- 修复点 4: 修正注释，update() 返回 boolean ---
                boolean success = tracker.update(testImage, updateBbox);
                log.info("✅ {} 跟踪器更新成功 (状态: {})", name, success);

            } else {
                log.error("❌ {} 跟踪器创建失败", name);
            }
        } catch (UnsatisfiedLinkError ule) {
            log.error("❌ {} 跟踪器测试失败 (本地库未找到或模块不匹配): {}", name, ule.getMessage());
        }
        catch (Exception e) {
            log.error("❌ {} 跟踪器测试异常: {}", name, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface TrackerFactory {
        Tracker create();
    }

    /**
     * 获取推荐的跟踪器类型
     */
    public String getRecommendedTracker() {
        String[] trackerPriority = {"MIL", "KCF", "CSRT", "BOOSTING", "MEDIANFLOW", "TLD", "MOSSE"};

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
        try (
                Tracker tracker = createTrackerByType(trackerType);
                Mat testImage = new Mat(100, 100, opencv_core.CV_8UC3)
        ){
            if (tracker != null) {
                Rect testBbox = new Rect(10, 10, 20, 20);
                tracker.init(testImage, testBbox);
                return true;
            }
            return false;
        } catch (Exception | UnsatisfiedLinkError e) {
            log.debug("跟踪器 {} 不可用: {}", trackerType, e.getMessage());
            return false;
        }
    }

    private Tracker createTrackerByType(String trackerType) {
        switch (trackerType.toUpperCase()) {
            case "MIL": return TrackerMIL.create();
            case "KCF": return TrackerKCF.create();
            case "CSRT": return TrackerCSRT.create();
            case "BOOSTING": return TrackerBoosting.create();
            case "TLD": return TrackerTLD.create();
            case "MEDIANFLOW": return TrackerMedianFlow.create();
            case "MOSSE": return TrackerMOSSE.create();
            default: return null;
        }
    }

    /**
     * 获取OpenCV版本信息
     */
    public String getOpenCVVersion() {
        try {
            return opencv_core.CV_VERSION;
        } catch (Exception e) {
            return "Unknown";
        }
    }

    /**
     * 获取所有可用的跟踪器列表
     */
    public java.util.List<String> getAvailableTrackers() {
        java.util.List<String> available = new java.util.ArrayList<>();
        String[] allTrackers = {"MIL", "KCF", "CSRT", "BOOSTING", "TLD", "MEDIANFLOW", "MOSSE"};

        for (String tracker : allTrackers) {
            if (isTrackerAvailable(tracker)) {
                available.add(tracker);
            }
        }
        return available;
    }

    // ... 其他方法 getTrackerRecommendations 和 performCompleteTest 保持不变 ...
    /**
     * 获取跟踪器性能建议
     */
    public java.util.Map<String, String> getTrackerRecommendations() {
        java.util.Map<String, String> recommendations = new java.util.HashMap<>();

        recommendations.put("MIL", "平衡性能，适合大多数场景");
        recommendations.put("KCF", "快速跟踪，适合实时应用");
        recommendations.put("CSRT", "高精度跟踪，适合精确场景");
        recommendations.put("BOOSTING", "传统算法，稳定性好");
        recommendations.put("TLD", "长期跟踪，处理遮挡好");
        recommendations.put("MEDIANFLOW", "快速移动目标跟踪");
        recommendations.put("MOSSE", "最快的跟踪器，适合简单场景");

        return recommendations;
    }

    /**
     * 执行完整的跟踪器性能测试
     */
    public java.util.Map<String, Object> performCompleteTest() {
        java.util.Map<String, Object> testResults = new java.util.HashMap<>();

        try {
            log.info("🔬 开始完整跟踪器性能测试...");

            // 基本信息
            testResults.put("opencvVersion", getOpenCVVersion());
            testResults.put("testTime", java.time.LocalDateTime.now());

            // 可用跟踪器
            java.util.List<String> availableTrackers = getAvailableTrackers();
            testResults.put("availableTrackers", availableTrackers);
            testResults.put("availableCount", availableTrackers.size());

            // 推荐跟踪器
            String recommended = getRecommendedTracker();
            testResults.put("recommendedTracker", recommended);

            // 性能建议
            testResults.put("recommendations", getTrackerRecommendations());

            // 详细测试结果
            java.util.Map<String, Boolean> detailedResults = new java.util.HashMap<>();
            String[] allTrackers = {"MIL", "KCF", "CSRT", "BOOSTING", "TLD", "MEDIANFLOW", "MOSSE"};

            for (String tracker : allTrackers) {
                detailedResults.put(tracker, isTrackerAvailable(tracker));
            }

            testResults.put("detailedResults", detailedResults);
            testResults.put("success", true);

            log.info("✅ 完整跟踪器性能测试完成");

        } catch (Exception e) {
            log.error("❌ 跟踪器性能测试失败", e);
            testResults.put("success", false);
            testResults.put("error", e.getMessage());
        }

        return testResults;
    }
}
