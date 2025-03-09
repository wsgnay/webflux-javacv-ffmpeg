package com.example.ffmpeg.service.impl;

import com.example.ffmpeg.service.ClipService;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.springframework.stereotype.Service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class ClipServiceImpl implements ClipService {

    @Override
    public String clipVideo(String inputPath, String outputPath, double startTime, double duration,
                          boolean preserveQuality, String videoCodec, String audioCodec) throws Exception {
        // 验证输入文件
        if (!Files.exists(Paths.get(inputPath))) {
            throw new IllegalArgumentException("输入视频文件不存在: " + inputPath);
        }

        // 创建输出目录
        Path outputDir = Paths.get(outputPath).getParent();
        if (outputDir != null && !Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputPath)) {
            grabber.start();

            // 设置起始位置
            grabber.setTimestamp((long) (startTime * 1000000L)); // 转换为微秒

            // 创建录制器
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputPath,
                    grabber.getImageWidth(),
                    grabber.getImageHeight(),
                    grabber.getAudioChannels());

            // 配置录制器
            configureRecorder(recorder, grabber, preserveQuality, videoCodec, audioCodec);
            recorder.start();

            // 计算结束时间戳
            long endTimestamp = (long) ((startTime + duration) * 1000000L);
            Frame frame;

            // 逐帧处理
            while ((frame = grabber.grab()) != null) {
                if (grabber.getTimestamp() > endTimestamp) {
                    break;
                }
                recorder.record(frame);
            }

            // 关闭录制器
            recorder.stop();
            recorder.release();

            return outputPath;
        }
    }

    @Override
    public List<String> splitVideo(String inputPath, String outputPattern, double segmentDuration) throws Exception {
        List<String> outputFiles = new ArrayList<>();
        
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputPath)) {
            grabber.start();
            
            double totalDuration = grabber.getLengthInTime() / 1000000.0; // 转换为秒
            int segments = (int) Math.ceil(totalDuration / segmentDuration);
            
            for (int i = 0; i < segments; i++) {
                String outputPath = String.format(outputPattern, i + 1);
                double startTime = i * segmentDuration;
                double duration = Math.min(segmentDuration, totalDuration - startTime);
                
                clipVideo(inputPath, outputPath, startTime, duration, true, null, null);
                outputFiles.add(outputPath);
            }
        }
        
        return outputFiles;
    }

    @Override
    public String mergeVideos(List<String> inputPaths, String outputPath, String transition) throws Exception {
        if (inputPaths.isEmpty()) {
            throw new IllegalArgumentException("输入视频列表为空");
        }

        // 获取第一个视频的信息作为基准
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputPaths.get(0))) {
            grabber.start();
            
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputPath,
                    grabber.getImageWidth(),
                    grabber.getImageHeight(),
                    grabber.getAudioChannels());
            
            // 配置录制器
            configureRecorder(recorder, grabber, true, null, null);
            recorder.start();

            // 处理每个输入视频
            for (int i = 0; i < inputPaths.size(); i++) {
                try (FFmpegFrameGrabber videoGrabber = new FFmpegFrameGrabber(inputPaths.get(i))) {
                    videoGrabber.start();
                    Frame frame;
                    
                    while ((frame = videoGrabber.grab()) != null) {
                        recorder.record(frame);
                    }
                    
                    // TODO: 在这里添加转场效果的处理
                }
            }

            recorder.stop();
            recorder.release();
        }

        return outputPath;
    }

    @Override
    public String losslessClip(String inputPath, String outputPath, double startTime, double duration) throws Exception {
        // 验证输入文件
        if (!Files.exists(Paths.get(inputPath))) {
            throw new IllegalArgumentException("输入视频文件不存在: " + inputPath);
        }

        // 创建输出目录
        Path outputDir = Paths.get(outputPath).getParent();
        if (outputDir != null && !Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputPath)) {
            grabber.start();

            // 检查是否支持无损剪辑
            if (!isSupportLosslessClip(grabber)) {
                throw new UnsupportedOperationException("当前视频不支持无损剪辑，需要H.264编码的MP4/MOV/M4V格式视频");
            }

            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputPath,
                    grabber.getImageWidth(),
                    grabber.getImageHeight(),
                    grabber.getAudioChannels());

            // 配置无损剪辑的参数
            recorder.setFormat(grabber.getFormat());
            recorder.setVideoCodec(grabber.getVideoCodec());
            recorder.setAudioCodec(grabber.getAudioCodec());
            recorder.setVideoOptions(grabber.getVideoOptions());
            recorder.setAudioOptions(grabber.getAudioOptions());
            
            // 设置起始位置
            grabber.setTimestamp((long) (startTime * 1000000L));
            
            recorder.start();

            // 计算结束时间戳
            long endTimestamp = (long) ((startTime + duration) * 1000000L);
            Frame frame;

            while ((frame = grabber.grab()) != null) {
                if (grabber.getTimestamp() > endTimestamp) {
                    break;
                }
                recorder.record(frame);
            }

            recorder.stop();
            recorder.release();

            return outputPath;
        }
    }

    private void configureRecorder(FFmpegFrameRecorder recorder, FFmpegFrameGrabber grabber,
                                 boolean preserveQuality, String videoCodec, String audioCodec) {
        // 设置基本参数
        recorder.setFormat(grabber.getFormat());
        
        // 设置视频相关参数
        if (preserveQuality) {
            recorder.setVideoCodec(grabber.getVideoCodec());
            recorder.setVideoBitrate(grabber.getVideoBitrate());
            recorder.setVideoOptions(grabber.getVideoOptions());
        } else {
            recorder.setVideoCodec(videoCodec != null ? 
                getVideoCodecId(videoCodec) : grabber.getVideoCodec());
        }
        
        // 设置音频相关参数
        if (preserveQuality) {
            recorder.setAudioCodec(grabber.getAudioCodec());
            recorder.setAudioBitrate(grabber.getAudioBitrate());
            recorder.setAudioOptions(grabber.getAudioOptions());
        } else {
            recorder.setAudioCodec(audioCodec != null ? 
                getAudioCodecId(audioCodec) : grabber.getAudioCodec());
        }
        
        // 设置其他参数
        recorder.setFrameRate(grabber.getFrameRate());
        recorder.setSampleRate(grabber.getSampleRate());
        recorder.setAspectRatio(grabber.getAspectRatio());
    }

    private int getVideoCodecId(String codecName) {
        // 这里可以添加更多编解码器的映射
        switch (codecName.toLowerCase()) {
            case "h264": return 27; // AV_CODEC_ID_H264
            case "h265": return 173; // AV_CODEC_ID_HEVC
            default: return 27; // 默认使用H.264
        }
    }

    private int getAudioCodecId(String codecName) {
        // 这里可以添加更多编解码器的映射
        switch (codecName.toLowerCase()) {
            case "aac": return 86018; // AV_CODEC_ID_AAC
            case "mp3": return 86017; // AV_CODEC_ID_MP3
            default: return 86018; // 默认使用AAC
        }
    }

    private boolean isSupportLosslessClip(FFmpegFrameGrabber grabber) {
        // 检查容器格式
        String format = grabber.getFormat();
        if (format == null) {
            return false;
        }

        boolean isSupportedContainer = false;
        String[] formats = format.split(",");
        for (String fmt : formats) {
            fmt = fmt.trim().toLowerCase();
            if (fmt.equals("mp4") || fmt.equals("mov") || fmt.equals("m4v")) {
                isSupportedContainer = true;
                break;
            }
        }

        if (!isSupportedContainer) {
            return false;
        }

        // 检查视频编码
        String videoCodecName = grabber.getVideoCodecName();
        return videoCodecName != null && (
            videoCodecName.toLowerCase().contains("h264") || 
            videoCodecName.toLowerCase().contains("avc")
        );
    }
} 