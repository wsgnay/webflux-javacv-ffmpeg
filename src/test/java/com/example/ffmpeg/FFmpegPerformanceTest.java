package com.example.ffmpeg;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.junit.Test;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, warmups = 1)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 2)
public class FFmpegPerformanceTest {
    
    private static final String VIDEO_PATH = "/Users/weisanju/Downloads/47.mp4";
    
    @Test
    public void runBenchmarks() throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(this.getClass().getSimpleName())
                .resultFormat(ResultFormatType.TEXT)
                .build();
        new Runner(opt).run();
    }
    
    @Benchmark
    public void testJNI() throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(VIDEO_PATH)) {
            grabber.start();
            FFmpegUtil.VideoInfo info = new FFmpegUtil.VideoInfo();
            info.setDuration(grabber.getLengthInTime() / 1000000.0);
            info.setWidth(grabber.getImageWidth());
            info.setHeight(grabber.getImageHeight());
            info.setFormat(grabber.getFormat());
            info.setFrameRate(grabber.getVideoFrameRate());
            info.setVideoCodec(grabber.getVideoCodec());
            info.setAudioCodec(grabber.getAudioCodec());
            info.setBitRate(grabber.getVideoBitrate());
        }
    }
    
    @Benchmark
    public void testCLI() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "ffprobe", 
            "-v", "quiet",
            "-print_format", "json",
            "-show_format",
            "-show_streams",
            VIDEO_PATH
        );
        
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        process.waitFor();
    }
} 