package com.example.ffmpeg;

import org.junit.Test;
import static org.junit.Assert.*;

public class FFmpegUtilTest {
    
    @Test
    public void testGetVideoInfo() {
        try {
            // 请将此路径替换为实际的视频文件路径
            String videoPath = "/Users/weisanju/Downloads/47.mp4";
            FFmpegUtil.VideoInfo info = FFmpegUtil.getVideoInfo(videoPath);
            
            System.out.println("视频信息：");
            System.out.println("时长：" + info.getDuration() + " 秒");
            System.out.println("分辨率：" + info.getWidth() + "x" + info.getHeight());
            System.out.println("格式：" + info.getFormat());
            System.out.println("帧率：" + info.getFrameRate());
            System.out.println("视频编解码器：" + info.getVideoCodec());
            System.out.println("音频编解码器：" + info.getAudioCodec());
            System.out.println("比特率：" + info.getBitRate());
            
            // 基本断言
            assertTrue("视频时长应大于0", info.getDuration() > 0);
            assertTrue("视频宽度应大于0", info.getWidth() > 0);
            assertTrue("视频高度应大于0", info.getHeight() > 0);
            assertNotNull("视频格式不应为空", info.getFormat());
            
        } catch (Exception e) {
            e.printStackTrace();
            fail("测试失败：" + e.getMessage());
        }
    }
} 