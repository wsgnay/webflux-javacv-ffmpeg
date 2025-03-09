# Java FFmpeg JNI 测试项目

这是一个使用Java通过JNI调用FFmpeg的测试项目，用于获取音视频文件的基本信息。

## 环境要求

- Java 11 或更高版本
- Maven 3.6 或更高版本

## 项目结构

```
src/
├── main/java/
│   └── com/example/ffmpeg/
│       └── FFmpegUtil.java      # FFmpeg工具类
└── test/java/
    └── com/example/ffmpeg/
        └── FFmpegUtilTest.java  # 测试类
```

## 功能特性

- 获取视频文件的基本信息：
  - 时长
  - 分辨率
  - 格式
  - 帧率
  - 视频编解码器
  - 音频编解码器
  - 比特率

## 使用方法

1. 克隆项目到本地
2. 使用Maven编译项目：
   ```bash
   mvn clean package
   ```
3. 运行测试（需要将测试类中的视频文件路径改为实际文件路径）：
   ```bash
   mvn test
   ```

## 示例代码

```java
String videoPath = "你的视频文件路径.mp4";
FFmpegUtil.VideoInfo info = FFmpegUtil.getVideoInfo(videoPath);
System.out.println(info);
```

## 注意事项

- 请确保提供正确的视频文件路径
- 支持大多数常见视频格式（mp4, avi, mkv等）
- 使用了JavaCV库，它会自动下载所需的本地FFmpeg库

