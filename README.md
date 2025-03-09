# Java FFmpeg 视频处理服务

这是一个基于 Spring Boot 和 JavaCV 的视频处理服务，提供视频信息获取、转码和缩略图生成功能。

## 环境要求

- Java 17
- Maven 3.6 或更高版本
- FFmpeg（通过 JavaCV 自动管理）

## 项目结构

```
src/
├── main/java/
│   └── com/example/ffmpeg/
│       ├── controller/          # REST API 控制器
│       ├── service/            # 业务逻辑服务
│       ├── dto/               # 数据传输对象
│       └── util/
│           └── FFmpegUtil.java # FFmpeg 工具类
└── test/java/
    └── com/example/ffmpeg/
        └── FFmpegUtilTest.java # 测试类
```

## 功能特性

1. 获取媒体文件信息：
   - 时长
   - 分辨率
   - 格式
   - 比特率
   - 视频编解码器
   - 音频编解码器

2. 视频转码功能：
   - 支持 H.264/H.265 视频编码
   - 支持 AAC/MP3 音频编码
   - 自定义输出分辨率
   - 自定义输出比特率
   - 自动保持音频质量

3. 视频缩略图功能：
   - 支持从视频任意时间点截取帧
   - 自定义输出图片尺寸
   - 支持 JPEG 格式输出
   - 自动创建输出目录

## API 文档

### 1. 获取媒体信息

```http
GET http://localhost:8080/api/media/info?filePath={filePath}
```

参数：
- `filePath`: 媒体文件的完整路径

返回示例：
```json
{
    "duration": "65.13",
    "resolution": "1280x720",
    "format": "mov,mp4,m4a,3gp,3g2,mj2",
    "bitrate": "1982111",
    "audioCodec": "aac",
    "videoCodec": "h264"
}
```

### 2. 视频转码

```http
POST http://localhost:8080/api/media/transcode
Content-Type: application/json

{
    "inputPath": "/path/to/input.mp4",
    "outputPath": "/path/to/output.mp4",
    "videoCodec": "h264",
    "audioCodec": "aac",
    "resolution": "1280x720",
    "bitrate": "2M"
}
```

参数说明：
- `inputPath`: 输入视频文件路径（必填）
- `outputPath`: 输出视频文件路径（必填）
- `videoCodec`: 视频编码（可选，支持 h264/h265，默认 h264）
- `audioCodec`: 音频编码（可选，支持 aac/mp3，默认 aac）
- `resolution`: 目标分辨率（可选，格式如 "1280x720"）
- `bitrate`: 目标比特率（可选，支持如 "2M" 或 "2000k"）

### 3. 生成视频缩略图

```http
POST http://localhost:8080/api/media/thumbnail
Content-Type: application/json

{
    "videoPath": "/path/to/video.mp4",
    "outputPath": "/path/to/thumbnail.jpg",
    "timestamp": 5.0,
    "width": 320,
    "height": 240
}
```

参数说明：
- `videoPath`: 输入视频文件路径（必填）
- `outputPath`: 输出缩略图路径（必填）
- `timestamp`: 截取时间点，单位秒（必填）
- `width`: 缩略图宽度（可选，默认保持原视频宽度）
- `height`: 缩略图高度（可选，默认保持原视频高度）

返回示例：
```json
{
    "success": true,
    "thumbnailPath": "/path/to/thumbnail.jpg"
}
```

## 使用方法

1. 克隆项目到本地

2. 配置 Java 17（推荐使用 .mavenrc）：
   ```bash
   echo "JAVA_HOME=/opt/homebrew/opt/openjdk@17" > ~/.mavenrc
   ```

3. 编译并运行项目：
   ```bash
   mvn clean package
   java -jar target/ffmpeg-jni-1.0-SNAPSHOT.jar
   ```

4. 使用 API 示例：
   ```bash
   # 获取视频信息
   curl -X GET "http://localhost:8080/api/media/info?filePath=/path/to/video.mp4"

   # 转码视频
   curl -X POST "http://localhost:8080/api/media/transcode" \
     -H "Content-Type: application/json" \
     -d '{
       "inputPath": "/path/to/input.mp4",
       "outputPath": "/path/to/output.mp4",
       "videoCodec": "h264",
       "audioCodec": "aac",
       "resolution": "1280x720",
       "bitrate": "2M"
     }'

   # 生成缩略图
   curl -X POST "http://localhost:8080/api/media/thumbnail" \
     -H "Content-Type: application/json" \
     -d '{
       "videoPath": "/path/to/video.mp4",
       "outputPath": "/path/to/thumbnail.jpg",
       "timestamp": 5.0,
       "width": 320,
       "height": 240
     }'
   ```

## 注意事项

- 确保提供正确的文件路径权限
- 支持大多数常见视频格式（mp4, avi, mkv等）
- 转码时请确保有足够的磁盘空间
- 建议在服务器环境使用时增加适当的访问控制
- 生成缩略图时，timestamp不应超过视频总时长
- 建议使用绝对路径以避免路径解析问题

