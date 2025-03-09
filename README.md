# Java FFmpeg 视频处理服务

这是一个基于 Spring Boot 和 JavaCV 的视频处理服务，提供视频信息获取、转码、剪辑、分割、合并和缩略图生成功能。

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

3. 视频剪辑功能：
   - 普通剪辑（支持所有格式）
   - 无损剪辑（支持H.264编码的MP4/MOV/M4V）
   - 视频分割
   - 视频合并
   - 可选转场效果
   - 关键帧提取和分析

4. 视频缩略图功能：
   - 支持从视频任意时间点截取帧
   - 自定义输出图片尺寸
   - 支持 JPEG 格式输出
   - 自动创建输出目录

## API 文档

### 1. 获取媒体信息

```http
GET http://localhost:8080/api/media/info?filePath={filePath}
```

参数说明：
- `filePath`: 媒体文件的完整路径（必填）

返回参数：
- `duration`: 视频时长（秒）
- `resolution`: 视频分辨率（如 "1280x720"）
- `format`: 视频容器格式
- `bitrate`: 视频比特率（bps）
- `audioCodec`: 音频编码格式
- `videoCodec`: 视频编码格式

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

返回参数：
- `success`: 转码是否成功
- `outputPath`: 输出文件路径
- `error`: 错误信息（如果失败）

### 3. 视频剪辑

#### 3.1 普通剪辑
```http
POST http://localhost:8080/api/media/clip/cut
Content-Type: application/json

{
    "inputPath": "/path/to/input.mp4",
    "outputPath": "/path/to/output.mp4",
    "startTime": 10.0,
    "duration": 30.0,
    "preserveQuality": true,
    "videoCodec": "h264",
    "audioCodec": "aac"
}
```

参数说明：
- `inputPath`: 输入视频文件路径（必填）
- `outputPath`: 输出视频文件路径（必填）
- `startTime`: 开始时间点，单位秒（必填）
- `duration`: 剪辑时长，单位秒（必填）
- `preserveQuality`: 是否保持原视频质量（可选，默认false）
- `videoCodec`: 视频编码（可选，默认与原视频相同）
- `audioCodec`: 音频编码（可选，默认与原视频相同）

返回参数：
- `success`: 剪辑是否成功
- `outputPath`: 输出文件路径
- `error`: 错误信息（如果失败）

#### 3.2 无损剪辑
```http
POST http://localhost:8080/api/media/clip/lossless
Content-Type: application/json

{
    "inputPath": "/path/to/input.mp4",
    "outputPath": "/path/to/output.mp4",
    "startTime": 10.0,
    "duration": 30.0
}
```

参数说明：
- `inputPath`: 输入视频文件路径（必填，必须是H.264编码的MP4/MOV/M4V格式）
- `outputPath`: 输出视频文件路径（必填）
- `startTime`: 开始时间点，单位秒（必填）
- `duration`: 剪辑时长，单位秒（必填）

返回参数：
- `success`: 剪辑是否成功
- `outputPath`: 输出文件路径
- `error`: 错误信息（如果失败）

#### 3.3 视频分割
```http
POST http://localhost:8080/api/media/clip/split
Content-Type: application/json

{
    "inputPath": "/path/to/input.mp4",
    "outputPattern": "/path/to/segment_%d.mp4",
    "segmentDuration": 5.0
}
```

参数说明：
- `inputPath`: 输入视频文件路径（必填）
- `outputPattern`: 输出文件名模式，使用%d作为序号占位符（必填）
- `segmentDuration`: 每个片段的时长，单位秒（必填）
- `preserveQuality`: 是否保持原视频质量（可选，默认false）

返回参数：
- `success`: 分割是否成功
- `segments`: 分割后的视频片段路径列表
- `error`: 错误信息（如果失败）

#### 3.4 视频合并
```http
POST http://localhost:8080/api/media/clip/merge
Content-Type: application/json

{
    "inputPaths": [
        "/path/to/segment_1.mp4",
        "/path/to/segment_2.mp4"
    ],
    "outputPath": "/path/to/merged.mp4",
    "transition": "fade"
}
```

参数说明：
- `inputPaths`: 要合并的视频文件路径列表（必填）
- `outputPath`: 合并后的输出文件路径（必填）
- `transition`: 转场效果（可选，支持 "fade"/"dissolve"/"none"，默认"none"）
- `transitionDuration`: 转场持续时间，单位秒（可选，默认1.0）

返回参数：
- `success`: 合并是否成功
- `outputPath`: 输出文件路径
- `error`: 错误信息（如果失败）

#### 3.5 获取视频关键帧
```http
POST http://localhost:8080/api/media/clip/keyframes
Content-Type: application/json

{
    "inputPath": "/path/to/input.mp4",
    "outputDir": "/path/to/keyframes/",
    "extractImages": true,
    "imageFormat": "jpg",
    "imageQuality": 95
}
```

参数说明：
- `inputPath`: 输入视频文件路径（必填）
- `outputDir`: 关键帧图片输出目录（可选，仅当extractImages为true时必填）
- `extractImages`: 是否提取关键帧图片（可选，默认false）
- `imageFormat`: 图片格式（可选，支持 "jpg"/"png"，默认"jpg"）
- `imageQuality`: 图片质量，1-100（可选，默认95）

返回参数：
- `success`: 是否成功
- `keyframes`: 关键帧信息列表，每个元素包含：
  - `timestamp`: 关键帧时间戳（秒）
  - `frameNumber`: 帧序号
  - `type`: 帧类型（I帧）
  - `imagePath`: 图片路径（仅当extractImages为true时返回）
- `error`: 错误信息（如果失败）

使用示例：
```bash
# 只获取关键帧信息
curl -X POST "http://localhost:8080/api/media/clip/keyframes" \
  -H "Content-Type: application/json" \
  -d '{
    "inputPath": "/path/to/video.mp4",
    "extractImages": false
  }'

# 获取关键帧信息并保存图片
curl -X POST "http://localhost:8080/api/media/clip/keyframes" \
  -H "Content-Type: application/json" \
  -d '{
    "inputPath": "/path/to/video.mp4",
    "outputDir": "/path/to/keyframes/",
    "extractImages": true,
    "imageFormat": "jpg",
    "imageQuality": 95
  }'
```

返回示例：
```json
{
    "success": true,
    "keyframes": [
        {
            "timestamp": 0.0,
            "frameNumber": 0,
            "type": "I",
            "imagePath": "/path/to/keyframes/keyframe_0.jpg"
        },
        {
            "timestamp": 8.333333,
            "frameNumber": 250,
            "type": "I",
            "imagePath": "/path/to/keyframes/keyframe_250.jpg"
        }
    ]
}
```

### 4. 生成视频缩略图

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
- `format`: 输出图片格式（可选，支持 "jpg"/"png"，默认"jpg"）
- `quality`: 图片质量，1-100（可选，默认95）

返回参数：
- `success`: 生成是否成功
- `thumbnailPath`: 缩略图文件路径
- `error`: 错误信息（如果失败）

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

## 视频剪辑功能说明

### 普通剪辑
- 支持所有视频格式
- 可以调整视频参数（编码、比特率等）
- 支持添加特效和转场
- 需要重新编码，处理时间较长
- 可能会有轻微的质量损失

### 无损剪辑
- 仅支持H.264编码的MP4/MOV/M4V格式
- 不需要重新编码，速度快
- 保持原始视频质量
- 剪辑点会自动调整到最近的关键帧
- 建议在剪辑前检查视频格式和编码

### 视频分割
- 按指定时长分割视频
- 自动创建输出目录
- 返回所有分段文件路径
- 支持保持原始视频质量
- 适合处理大型视频文件

### 视频合并
- 支持多个视频文件合并
- 自动处理编码格式
- 可选添加转场效果
- 建议使用相同格式和编码的视频
- 注意合并大文件时的内存使用

### 关键帧提取
- 支持提取视频中的所有关键帧（I帧）
- 可选择是否保存关键帧图片
- 支持JPG和PNG格式输出
- 提供关键帧的精确时间戳和帧号
- 适用于视频分析和预览
- 可用于优化无损剪辑的切分点选择
- 自动创建输出目录（当需要保存图片时）
- 支持自定义图片质量参数

## 注意事项

1. 视频处理相关：
   - 确保提供正确的文件路径权限
   - 支持大多数常见视频格式
   - 转码和剪辑时请确保有足够的磁盘空间
   - 建议在服务器环境使用时增加适当的访问控制

2. 无损剪辑限制：
   - 仅支持H.264编码的MP4/MOV/M4V格式
   - 剪辑点会自动调整到最近的关键帧
   - 建议在剪辑前检查视频格式和编码

3. 性能优化建议：
   - 优先使用无损剪辑（如果格式支持）
   - 批量处理时注意内存和磁盘使用
   - 可以通过参数调整编码质量和速度

## 错误处理

常见错误响应格式：
```json
{
    "success": false,
    "error": "错误信息"
}
```

## 贡献指南

欢迎提交Issue和Pull Request。

## 许可证

[MIT License](LICENSE)

