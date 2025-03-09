package com.example.ffmpeg.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.ffmpeg.global.avcodec;

import java.io.File;

@Slf4j
public class FFmpegUtil {

    @Data
    public static class MediaMetadata {
        private String duration;
        private String resolution;
        private String format;
        private String bitrate;
        private String audioCodec;
        private String videoCodec;
    }

    public static MediaMetadata getMediaInfo(String filePath) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(filePath)) {
            grabber.start();
            
            MediaMetadata metadata = new MediaMetadata();
            metadata.setDuration(String.format("%.2f", grabber.getLengthInTime() / 1000000.0));
            metadata.setResolution(grabber.getImageWidth() + "x" + grabber.getImageHeight());
            metadata.setFormat(grabber.getFormat());
            metadata.setBitrate(String.valueOf(grabber.getVideoBitrate()));
            metadata.setAudioCodec(grabber.getAudioCodecName());
            metadata.setVideoCodec(grabber.getVideoCodecName());
            
            return metadata;
        } catch (Exception e) {
            log.error("获取媒体信息失败", e);
            throw new RuntimeException("获取媒体信息失败: " + e.getMessage());
        }
    }

    public static void transcodeVideo(String inputPath, String outputPath, String videoCodec, 
                                    String audioCodec, String resolution, String bitrate) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputPath)) {
            grabber.start();
            
            // 解析分辨率
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            if (resolution != null && !resolution.isEmpty()) {
                String[] dims = resolution.split("x");
                width = Integer.parseInt(dims[0]);
                height = Integer.parseInt(dims[1]);
            }
            
            // 创建输出目录
            File outputFile = new File(outputPath);
            outputFile.getParentFile().mkdirs();
            
            // 配置转码器
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputPath, width, height);
            
            // 复制音频参数
            recorder.setAudioChannels(grabber.getAudioChannels());
            recorder.setSampleRate(grabber.getSampleRate());
            
            // 设置视频编解码器
            if (videoCodec != null && !videoCodec.isEmpty()) {
                if ("h264".equalsIgnoreCase(videoCodec)) {
                    recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
                } else if ("h265".equalsIgnoreCase(videoCodec)) {
                    recorder.setVideoCodec(avcodec.AV_CODEC_ID_HEVC);
                } else {
                    throw new IllegalArgumentException("不支持的视频编码格式: " + videoCodec);
                }
            } else {
                recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            }
            
            // 设置音频编解码器
            if (audioCodec != null && !audioCodec.isEmpty()) {
                if ("aac".equalsIgnoreCase(audioCodec)) {
                    recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                } else if ("mp3".equalsIgnoreCase(audioCodec)) {
                    recorder.setAudioCodec(avcodec.AV_CODEC_ID_MP3);
                } else {
                    throw new IllegalArgumentException("不支持的音频编码格式: " + audioCodec);
                }
            } else {
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
            }
            
            if (bitrate != null && !bitrate.isEmpty()) {
                recorder.setVideoBitrate(parseBitrate(bitrate));
            }
            
            recorder.setFormat("mp4");
            recorder.start();
            
            // 转码过程
            while (true) {
                var frame = grabber.grab();
                if (frame == null) {
                    break;
                }
                recorder.record(frame);
            }
            
            recorder.stop();
            recorder.release();
            
        } catch (Exception e) {
            log.error("视频转码失败", e);
            throw new RuntimeException("视频转码失败: " + e.getMessage());
        }
    }
    
    private static int parseBitrate(String bitrate) {
        bitrate = bitrate.toLowerCase();
        int multiplier = 1;
        
        if (bitrate.endsWith("k")) {
            multiplier = 1000;
            bitrate = bitrate.substring(0, bitrate.length() - 1);
        } else if (bitrate.endsWith("m")) {
            multiplier = 1000000;
            bitrate = bitrate.substring(0, bitrate.length() - 1);
        }
        
        return (int) (Double.parseDouble(bitrate) * multiplier);
    }
} 