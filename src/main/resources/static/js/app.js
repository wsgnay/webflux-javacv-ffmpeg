// js/app.js - 无人机检测系统前端JavaScript

class DroneDetectionApp {
    constructor() {
        this.apiBaseUrl = '/api/drone';
        this.currentSection = 'dashboard';
        this.settings = this.loadSettingsFromStorage();
        this.currentImageFile = null;
        this.currentVideoFile = null;
        this.init();
    }

    init() {
        this.setupEventListeners();
        this.loadDashboard();
        this.loadSettings();

        // 定期刷新仪表板数据
        setInterval(() => {
            if (this.currentSection === 'dashboard') {
                this.loadDashboard();
            }
        }, 30000); // 30秒刷新一次
    }

    setupEventListeners() {
        // 文件上传事件
        this.setupFileUpload();

        // 检测按钮事件
        document.getElementById('startImageDetection').addEventListener('click', () => {
            this.startImageDetection();
        });

        document.getElementById('startVideoTracking').addEventListener('click', () => {
            this.startVideoTracking();
        });

        // 置信度滑块同步
        const imageConfidence = document.getElementById('imageConfidence');
        const imageConfidenceValue = document.getElementById('imageConfidenceValue');

        if (imageConfidence && imageConfidenceValue) {
            imageConfidence.addEventListener('input', (e) => {
                imageConfidenceValue.value = e.target.value;
            });

            imageConfidenceValue.addEventListener('input', (e) => {
                imageConfidence.value = e.target.value;
            });
        }

        // 设置页面今日日期
        const dateFilter = document.getElementById('dateFilter');
        if (dateFilter) {
            dateFilter.value = new Date().toISOString().split('T')[0];
        }
    }

    setupFileUpload() {
        // 图像上传
        const imageUploadArea = document.getElementById('imageUploadArea');
        const imageInput = document.getElementById('imageInput');

        if (imageUploadArea && imageInput) {
            ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
                imageUploadArea.addEventListener(eventName, this.preventDefaults, false);
            });

            ['dragenter', 'dragover'].forEach(eventName => {
                imageUploadArea.addEventListener(eventName, () => {
                    imageUploadArea.classList.add('dragover');
                });
            });

            ['dragleave', 'drop'].forEach(eventName => {
                imageUploadArea.addEventListener(eventName, () => {
                    imageUploadArea.classList.remove('dragover');
                });
            });

            imageUploadArea.addEventListener('drop', (e) => {
                const files = e.dataTransfer.files;
                if (files.length > 0 && files[0].type.startsWith('image/')) {
                    this.handleImageFile(files[0]);
                }
            });

            imageInput.addEventListener('change', (e) => {
                if (e.target.files.length > 0) {
                    this.handleImageFile(e.target.files[0]);
                }
            });
        }

        // 视频上传
        const videoUploadArea = document.getElementById('videoUploadArea');
        const videoInput = document.getElementById('videoInput');

        if (videoUploadArea && videoInput) {
            ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
                videoUploadArea.addEventListener(eventName, this.preventDefaults, false);
            });

            ['dragenter', 'dragover'].forEach(eventName => {
                videoUploadArea.addEventListener(eventName, () => {
                    videoUploadArea.classList.add('dragover');
                });
            });

            ['dragleave', 'drop'].forEach(eventName => {
                videoUploadArea.addEventListener(eventName, () => {
                    videoUploadArea.classList.remove('dragover');
                });
            });

            videoUploadArea.addEventListener('drop', (e) => {
                const files = e.dataTransfer.files;
                if (files.length > 0 && files[0].type.startsWith('video/')) {
                    this.handleVideoFile(files[0]);
                }
            });

            videoInput.addEventListener('change', (e) => {
                if (e.target.files.length > 0) {
                    this.handleVideoFile(e.target.files[0]);
                }
            });
        }
    }

    preventDefaults(e) {
        e.preventDefault();
        e.stopPropagation();
    }

    handleImageFile(file) {
        const maxSize = 50 * 1024 * 1024; // 50MB
        if (file.size > maxSize) {
            this.showAlert('文件太大，请选择小于50MB的图像文件', 'warning');
            return;
        }

        const reader = new FileReader();
        reader.onload = (e) => {
            const preview = `
                <div class="text-center">
                    <img src="${e.target.result}" class="result-image mb-3" style="max-height: 200px;">
                    <div class="d-flex justify-content-between align-items-center">
                        <span class="badge bg-primary">${file.name}</span>
                        <span class="text-muted">${this.formatFileSize(file.size)}</span>
                    </div>
                </div>
            `;
            const imageResult = document.getElementById('imageResult');
            if (imageResult) {
                imageResult.innerHTML = preview;
            }

            const startButton = document.getElementById('startImageDetection');
            if (startButton) {
                startButton.disabled = false;
            }
        };
        reader.readAsDataURL(file);
        this.currentImageFile = file;
    }

    handleVideoFile(file) {
        const maxSize = 500 * 1024 * 1024; // 500MB
        if (file.size > maxSize) {
            this.showAlert('文件太大，请选择小于500MB的视频文件', 'warning');
            return;
        }

        const preview = `
            <div class="text-center">
                <i class="bi bi-camera-video-fill fs-1 text-primary mb-3"></i>
                <div class="d-flex justify-content-between align-items-center">
                    <span class="badge bg-primary">${file.name}</span>
                    <span class="text-muted">${this.formatFileSize(file.size)}</span>
                </div>
                <div class="mt-2">
                    <small class="text-muted">视频已准备就绪</small>
                </div>
            </div>
        `;
        const videoResult = document.getElementById('videoResult');
        if (videoResult) {
            videoResult.innerHTML = preview;
        }

        const startButton = document.getElementById('startVideoTracking');
        if (startButton) {
            startButton.disabled = false;
        }
        this.currentVideoFile = file;
    }

    async startImageDetection() {
        if (!this.currentImageFile) {
            this.showAlert('请先选择图像文件', 'warning');
            return;
        }

        if (!this.settings.apiKey) {
            this.showAlert('请先在设置中配置API Key', 'warning');
            this.showSection('settings');
            return;
        }

        const formData = new FormData();
        formData.append('file', this.currentImageFile);

        const request = {
            imagePath: this.currentImageFile.name,
            confThreshold: parseFloat(document.getElementById('imageConfidenceValue')?.value || 0.3),
            maxImageSize: parseInt(this.settings.maxImageSize)
        };

        try {
            this.showProgress('image', true);
            this.updateProgress('image', 10);

            // 模拟上传进度
            this.simulateUploadProgress('image', 10, 50, 2000);

            // 首先上传文件
            const uploadResponse = await fetch('/api/drone/upload/image', {
                method: 'POST',
                body: formData
            });

            if (!uploadResponse.ok) {
                throw new Error(`文件上传失败: ${uploadResponse.status}`);
            }

            const uploadResult = await uploadResponse.json();
            request.imagePath = uploadResult.filePath;

            this.updateProgress('image', 60);

            // 然后进行检测
            const response = await fetch(`${this.apiBaseUrl}/image/detect`, {
                method: 'POST',
                headers: {
                    'Authorization': `Bearer ${this.settings.apiKey}`,
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(request)
            });

            this.updateProgress('image', 80);

            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }

            const result = await response.json();
            this.updateProgress('image', 100);

            setTimeout(() => {
                this.showProgress('image', false);
                this.displayImageResult(result);
                this.addLog(`图像检测完成: 检测到 ${result.totalPersons || 0} 个人物`, 'success');
            }, 500);

        } catch (error) {
            this.showProgress('image', false);
            this.showAlert(`检测失败: ${error.message}`, 'danger');
            this.addLog(`图像检测失败: ${error.message}`, 'error');
        }
    }

    async startVideoTracking() {
        if (!this.currentVideoFile) {
            this.showAlert('请先选择视频文件', 'warning');
            return;
        }

        if (!this.settings.apiKey) {
            this.showAlert('请先在设置中配置API Key', 'warning');
            this.showSection('settings');
            return;
        }

        // 创建FormData用于文件上传
        const formData = new FormData();
        formData.append('file', this.currentVideoFile);

        const request = {
            videoSource: this.currentVideoFile.name,
            apiKey: this.settings.apiKey,
            confThreshold: parseFloat(document.getElementById('videoConfidence')?.value || 0.5),
            trackerType: document.getElementById('trackerType')?.value || 'MIL',
            enableAutoDedup: document.getElementById('autoDedup')?.checked || true
        };

        try {
            this.showProgress('video', true);
            this.updateProgress('video', 5);
            this.addLog('开始视频上传和处理...', 'info');

            // 首先上传视频文件
            const uploadResponse = await fetch('/api/drone/upload/video', {
                method: 'POST',
                body: formData
            });

            if (!uploadResponse.ok) {
                throw new Error(`视频上传失败: ${uploadResponse.status}`);
            }

            const uploadResult = await uploadResponse.json();
            request.videoSource = uploadResult.filePath;

            this.updateProgress('video', 20);
            this.addLog('视频上传完成，开始跟踪处理...', 'info');

            // 模拟长时间处理
            this.simulateVideoProcessing();

            const response = await fetch(`${this.apiBaseUrl}/video/track`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(request)
            });

            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }

            const result = await response.json();
            this.updateProgress('video', 100);

            setTimeout(() => {
                this.showProgress('video', false);
                this.displayVideoResult(result);
                this.addLog('视频跟踪处理完成', 'success');
            }, 1000);

        } catch (error) {
            this.showProgress('video', false);
            this.showAlert(`跟踪失败: ${error.message}`, 'danger');
            this.addLog(`视频跟踪失败: ${error.message}`, 'error');
        }
    }

    displayImageResult(result) {
        const imageResult = document.getElementById('imageResult');
        if (!imageResult) return;

        if (result.success) {
            const detections = result.detections || [];
            let html = `
                <div class="alert alert-success">
                    <i class="bi bi-check-circle me-2"></i>
                    检测完成！发现 <strong>${result.totalPersons || 0}</strong> 个人物
                </div>
            `;

            if (detections.length > 0) {
                html += '<div class="detection-result">';
                detections.forEach((detection, index) => {
                    html += `
                        <div class="detection-item">
                            <div class="d-flex justify-content-between align-items-center">
                                <span><i class="bi bi-person-fill me-2"></i>人物 ${index + 1}</span>
                                <span class="badge bg-success">${(detection.confidence * 100).toFixed(1)}%</span>
                            </div>
                            <small class="text-muted">
                                位置: [${detection.bbox.map(b => b.toFixed(0)).join(', ')}]
                            </small>
                        </div>
                    `;
                });
                html += '</div>';
            }

            // 添加输出图片显示
            if (result.outputImagePath) {
                html += `
                    <div class="mt-3">
                        <h6>检测结果:</h6>
                        <img src="${result.outputImagePath}" class="img-fluid rounded" alt="检测结果">
                    </div>
                `;
            }

            imageResult.innerHTML = html;
        } else {
            this.showAlert(result.error || '检测失败', 'danger');
        }
    }

    displayVideoResult(result) {
        const videoResult = document.getElementById('videoResult');
        if (!videoResult) return;

        if (result.success && result.result) {
            const data = result.result;
            const html = `
                <div class="alert alert-success">
                    <i class="bi bi-check-circle me-2"></i>
                    跟踪完成！
                </div>
                <div class="row text-center">
                    <div class="col-6 mb-3">
                        <div class="bg-light p-3 rounded">
                            <h4 class="text-primary mb-1">${data.frameCount || 0}</h4>
                            <small class="text-muted">处理帧数</small>
                        </div>
                    </div>
                    <div class="col-6 mb-3">
                        <div class="bg-light p-3 rounded">
                            <h4 class="text-success mb-1">${data.activeTrackers || 0}</h4>
                            <small class="text-muted">活跃跟踪器</small>
                        </div>
                    </div>
                    <div class="col-6">
                        <div class="bg-light p-3 rounded">
                            <h4 class="text-warning mb-1">${data.apiCallsUsed || 0}</h4>
                            <small class="text-muted">API调用</small>
                        </div>
                    </div>
                    <div class="col-6">
                        <div class="bg-light p-3 rounded">
                            <h4 class="text-info mb-1">${data.dedupOperations || 0}</h4>
                            <small class="text-muted">去重操作</small>
                        </div>
                    </div>
                </div>
                ${data.outputVideoPath ? `
                    <div class="mt-3">
                        <h6>输出视频:</h6>
                        <video controls class="w-100 rounded">
                            <source src="${data.outputVideoPath}" type="video/mp4">
                            您的浏览器不支持视频播放。
                        </video>
                    </div>
                ` : ''}
            `;
            videoResult.innerHTML = html;
        } else {
            this.showAlert(result.error || '跟踪失败', 'danger');
        }
    }

    simulateUploadProgress(type, start, end, duration) {
        const startTime = Date.now();
        const update = () => {
            const elapsed = Date.now() - startTime;
            const progress = Math.min(start + (end - start) * (elapsed / duration), end);
            this.updateProgress(type, progress);

            if (progress < end) {
                requestAnimationFrame(update);
            }
        };
        update();
    }

    simulateVideoProcessing() {
        let progress = 20;
        const steps = [
            { progress: 25, message: '初始化视频处理...', delay: 1000 },
            { progress: 35, message: '分析视频帧...', delay: 2000 },
            { progress: 50, message: '调用AI检测API...', delay: 3000 },
            { progress: 65, message: '初始化跟踪器...', delay: 2000 },
            { progress: 80, message: '处理跟踪数据...', delay: 2500 },
            { progress: 90, message: '生成结果视频...', delay: 2000 },
            { progress: 95, message: '保存处理结果...', delay: 1000 }
        ];

        let stepIndex = 0;
        const processStep = () => {
            if (stepIndex < steps.length) {
                const step = steps[stepIndex];
                setTimeout(() => {
                    this.updateProgress('video', step.progress);
                    this.addLog(step.message, 'info');
                    stepIndex++;
                    processStep();
                }, step.delay);
            }
        };
        processStep();
    }

    showProgress(type, show) {
        const progressElement = document.getElementById(`${type}Progress`);
        if (progressElement) {
            if (show) {
                progressElement.style.display = 'block';
                this.updateProgress(type, 0);
            } else {
                progressElement.style.display = 'none';
            }
        }
    }

    updateProgress(type, percent) {
        const circle = document.getElementById(`${type}ProgressCircle`);
        const text = document.getElementById(`${type}ProgressText`);

        if (circle && text) {
            const circumference = 2 * Math.PI * 26;
            const offset = circumference - (percent / 100 * circumference);

            circle.style.strokeDashoffset = offset;
            text.textContent = Math.round(percent) + '%';
        }
    }

    addLog(message, type = 'info') {
        const logContainer = document.getElementById('logContainer');
        if (!logContainer) return;

        const timestamp = new Date().toLocaleTimeString();
        const logClass = type === 'success' ? 'log-success' :
            type === 'error' ? 'log-error' :
                type === 'warning' ? 'log-warning' : '';

        const logEntry = document.createElement('div');
        logEntry.className = `log-entry ${logClass}`;
        logEntry.textContent = `[${timestamp}] ${message}`;

        logContainer.appendChild(logEntry);
        logContainer.scrollTop = logContainer.scrollHeight;

        // 限制日志条数
        if (logContainer.children.length > 100) {
            logContainer.removeChild(logContainer.firstChild);
        }
    }

    async loadDashboard() {
        try {
            // 尝试从API加载真实数据
            const response = await fetch(`${this.apiBaseUrl}/data/dashboard/stats`);
            let stats;

            if (response.ok) {
                stats = await response.json();
            } else {
                // 如果API不可用，使用模拟数据
                stats = await this.fetchDashboardStats();
            }

            const totalImages = document.getElementById('total-images');
            const totalVideos = document.getElementById('total-videos');
            const totalPersons = document.getElementById('total-persons');
            const apiCalls = document.getElementById('api-calls');

            if (totalImages) totalImages.textContent = stats.totalImages || 0;
            if (totalVideos) totalVideos.textContent = stats.totalVideos || 0;
            if (totalPersons) totalPersons.textContent = stats.totalPersons || 0;
            if (apiCalls) apiCalls.textContent = stats.apiCalls || 0;

            this.loadRecentActivities(stats.recentActivities || []);

        } catch (error) {
            console.error('加载仪表板数据失败:', error);
            // 使用模拟数据
            const stats = await this.fetchDashboardStats();
            this.loadRecentActivities(stats.recentActivities || []);
        }
    }

    async fetchDashboardStats() {
        // 模拟API调用
        return new Promise(resolve => {
            setTimeout(() => {
                resolve({
                    totalImages: Math.floor(Math.random() * 100) + 50,
                    totalVideos: Math.floor(Math.random() * 20) + 10,
                    totalPersons: Math.floor(Math.random() * 500) + 200,
                    apiCalls: Math.floor(Math.random() * 1000) + 500,
                    recentActivities: [
                        {
                            type: 'image',
                            name: 'drone_image_001.jpg',
                            persons: 3,
                            time: '2分钟前',
                            status: 'success'
                        },
                        {
                            type: 'video',
                            name: 'surveillance_video.mp4',
                            persons: 5,
                            time: '10分钟前',
                            status: 'success'
                        },
                        {
                            type: 'image',
                            name: 'aerial_shot.png',
                            persons: 1,
                            time: '1小时前',
                            status: 'success'
                        }
                    ]
                });
            }, 500);
        });
    }

    loadRecentActivities(activities) {
        const recentActivities = document.getElementById('recent-activities');
        if (!recentActivities) return;

        if (activities.length === 0) {
            recentActivities.innerHTML = `
                <div class="text-center text-muted py-4">
                    <i class="bi bi-inbox fs-1 mb-2"></i>
                    <p>暂无检测记录</p>
                </div>
            `;
            return;
        }

        let html = '';
        activities.forEach(activity => {
            const icon = activity.type === 'image' ? 'bi-image' : 'bi-camera-video';
            const badge = activity.status === 'success' ? 'bg-success' : 'bg-danger';

            html += `
                <div class="d-flex align-items-center mb-3 p-2 bg-light rounded">
                    <div class="me-3">
                        <i class="bi ${icon} fs-4 text-primary"></i>
                    </div>
                    <div class="flex-grow-1">
                        <div class="fw-bold">${activity.name}</div>
                        <small class="text-muted">检测到 ${activity.persons} 个人物 • ${activity.time}</small>
                    </div>
                    <span class="badge ${badge}">
                        ${activity.status === 'success' ? '成功' : '失败'}
                    </span>
                </div>
            `;
        });

        recentActivities.innerHTML = html;
    }

    async loadHistory() {
        const filter = document.getElementById('historyFilter')?.value || 'all';
        const status = document.getElementById('statusFilter')?.value || 'all';
        const date = document.getElementById('dateFilter')?.value || '';

        try {
            // 尝试从API加载真实数据
            const response = await fetch(`${this.apiBaseUrl}/data/history?filter=${filter}&status=${status}&date=${date}`);
            let history;

            if (response.ok) {
                history = await response.json();
            } else {
                // 如果API不可用，使用模拟数据
                history = await this.fetchHistory(filter, status, date);
            }

            this.displayHistory(history);
        } catch (error) {
            console.error('加载历史记录失败:', error);
            // 使用模拟数据
            const history = await this.fetchHistory(filter, status, date);
            this.displayHistory(history);
        }
    }

    async fetchHistory(filter, status, date) {
        // 模拟API调用
        return new Promise(resolve => {
            setTimeout(() => {
                const mockData = [
                    {
                        id: 1,
                        type: 'image',
                        fileName: 'drone_capture_001.jpg',
                        personCount: 3,
                        processingTime: 2.5,
                        status: 'success',
                        createdAt: '2024-01-15 14:30:22'
                    },
                    {
                        id: 2,
                        type: 'video',
                        fileName: 'surveillance_cam.mp4',
                        personCount: 5,
                        processingTime: 45.2,
                        status: 'success',
                        createdAt: '2024-01-15 13:15:10'
                    },
                    {
                        id: 3,
                        type: 'image',
                        fileName: 'aerial_view.png',
                        personCount: 0,
                        processingTime: 1.8,
                        status: 'failed',
                        createdAt: '2024-01-15 12:45:33'
                    }
                ];
                resolve(mockData);
            }, 300);
        });
    }

    displayHistory(history) {
        const tbody = document.getElementById('historyTable');
        if (!tbody) return;

        if (history.length === 0) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="7" class="text-center text-muted py-4">
                        <i class="bi bi-inbox fs-1 mb-2"></i>
                        <div>没有找到匹配的记录</div>
                    </td>
                </tr>
            `;
            return;
        }

        let html = '';
        history.forEach(item => {
            const typeIcon = item.type === 'image' ? 'bi-image' : 'bi-camera-video';
            const typeName = item.type === 'image' ? '图像检测' : '视频跟踪';
            const statusClass = item.status === 'success' ? 'status-success' :
                item.status === 'processing' ? 'status-processing' : 'status-failed';
            const statusText = item.status === 'success' ? '成功' :
                item.status === 'processing' ? '处理中' : '失败';

            html += `
                <tr>
                    <td>
                        <i class="bi ${typeIcon} me-2"></i>
                        ${typeName}
                    </td>
                    <td>${item.fileName}</td>
                    <td>
                        <span class="badge bg-primary">${item.personCount} 人</span>
                    </td>
                    <td>${item.processingTime}s</td>
                    <td>
                        <span class="status-badge ${statusClass}">${statusText}</span>
                    </td>
                    <td>${item.createdAt}</td>
                    <td>
                        <button class="btn btn-sm btn-outline-primary me-1" onclick="app.viewDetails(${item.id})">
                            <i class="bi bi-eye"></i>
                        </button>
                        <button class="btn btn-sm btn-outline-danger" onclick="app.deleteRecord(${item.id})">
                            <i class="bi bi-trash"></i>
                        </button>
                    </td>
                </tr>
            `;
        });

        tbody.innerHTML = html;
    }

    viewDetails(id) {
        this.showAlert(`查看记录 ${id} 的详细信息`, 'info');
    }

    deleteRecord(id) {
        if (confirm('确定要删除这条记录吗？')) {
            this.showAlert(`记录 ${id} 已删除`, 'success');
            this.loadHistory();
        }
    }

    loadSettings() {
        const elements = {
            apiKey: document.getElementById('apiKey'),
            modelName: document.getElementById('modelName'),
            apiTimeout: document.getElementById('apiTimeout'),
            defaultConfidence: document.getElementById('defaultConfidence'),
            maxImageSize: document.getElementById('maxImageSize'),
            defaultTracker: document.getElementById('defaultTracker')
        };

        if (elements.apiKey) elements.apiKey.value = this.settings.apiKey || '';
        if (elements.modelName) elements.modelName.value = this.settings.modelName || 'qwen2.5-vl-72b-instruct';
        if (elements.apiTimeout) elements.apiTimeout.value = this.settings.apiTimeout || 120;
        if (elements.defaultConfidence) elements.defaultConfidence.value = this.settings.defaultConfidence || 0.3;
        if (elements.maxImageSize) elements.maxImageSize.value = this.settings.maxImageSize || 1024;
        if (elements.defaultTracker) elements.defaultTracker.value = this.settings.defaultTracker || 'MIL';
    }

    saveSettings() {
        const elements = {
            apiKey: document.getElementById('apiKey'),
            modelName: document.getElementById('modelName'),
            apiTimeout: document.getElementById('apiTimeout'),
            defaultConfidence: document.getElementById('defaultConfidence'),
            maxImageSize: document.getElementById('maxImageSize'),
            defaultTracker: document.getElementById('defaultTracker')
        };

        this.settings = {
            apiKey: elements.apiKey?.value || '',
            modelName: elements.modelName?.value || 'qwen2.5-vl-72b-instruct',
            apiTimeout: parseInt(elements.apiTimeout?.value || 120),
            defaultConfidence: parseFloat(elements.defaultConfidence?.value || 0.3),
            maxImageSize: parseInt(elements.maxImageSize?.value || 1024),
            defaultTracker: elements.defaultTracker?.value || 'MIL'
        };

        localStorage.setItem('droneDetectionSettings', JSON.stringify(this.settings));
        this.showAlert('设置已保存', 'success');
    }

    loadSettingsFromStorage() {
        const saved = localStorage.getItem('droneDetectionSettings');
        if (saved) {
            try {
                return JSON.parse(saved);
            } catch (e) {
                console.error('解析设置失败:', e);
            }
        }
        return {
            apiKey: '',
            modelName: 'qwen2.5-vl-72b-instruct',
            apiTimeout: 120,
            defaultConfidence: 0.3,
            maxImageSize: 1024,
            defaultTracker: 'MIL'
        };
    }

    async testApiConnection() {
        const apiKey = document.getElementById('apiKey')?.value;
        if (!apiKey) {
            this.showAlert('请输入API Key', 'warning');
            return;
        }

        try {
            const response = await fetch(`${this.apiBaseUrl}/test`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    apiKey: apiKey,
                    model: document.getElementById('modelName')?.value || 'qwen2.5-vl-72b-instruct'
                })
            });

            const result = await response.json();

            if (result.success) {
                this.showAlert('API连接测试成功！', 'success');
            } else {
                this.showAlert(`API连接失败: ${result.error}`, 'danger');
            }
        } catch (error) {
            this.showAlert(`连接测试失败: ${error.message}`, 'danger');
        }
    }

    showSection(sectionName) {
        // 隐藏所有部分
        document.querySelectorAll('.section-content').forEach(section => {
            section.style.display = 'none';
        });

        // 显示目标部分
        const targetSection = document.getElementById(`${sectionName}-section`);
        if (targetSection) {
            targetSection.style.display = 'block';
        }
        this.currentSection = sectionName;

        // 更新导航栏活跃状态
        document.querySelectorAll('.navbar-nav .nav-link').forEach(link => {
            link.classList.remove('active');
        });
        const activeLink = document.querySelector(`[href="#${sectionName}"]`);
        if (activeLink) {
            activeLink.classList.add('active');
        }

        // 根据部分加载相应数据
        switch(sectionName) {
            case 'dashboard':
                this.loadDashboard();
                break;
            case 'history':
                this.loadHistory();
                break;
            case 'settings':
                this.loadSettings();
                break;
        }
    }

    showAlert(message, type = 'info') {
        const alertContainer = document.createElement('div');
        alertContainer.className = `alert alert-${type} alert-dismissible fade show position-fixed`;
        alertContainer.style.cssText = 'top: 20px; right: 20px; z-index: 9999; min-width: 300px;';
        alertContainer.innerHTML = `
            ${message}
            <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
        `;

        document.body.appendChild(alertContainer);

        // 5秒后自动关闭
        setTimeout(() => {
            if (alertContainer.parentNode) {
                alertContainer.remove();
            }
        }, 5000);
    }

    formatFileSize(bytes) {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }
}

// 全局函数
function showSection(sectionName) {
    if (window.app) {
        window.app.showSection(sectionName);
    }
}

function toggleApiKeyVisibility() {
    const apiKeyInput = document.getElementById('apiKey');
    const toggleIcon = document.getElementById('apiKeyToggle');

    if (apiKeyInput && toggleIcon) {
        if (apiKeyInput.type === 'password') {
            apiKeyInput.type = 'text';
            toggleIcon.className = 'bi bi-eye-slash';
        } else {
            apiKeyInput.type = 'password';
            toggleIcon.className = 'bi bi-eye';
        }
    }
}

function testApiConnection() {
    if (window.app) {
        window.app.testApiConnection();
    }
}

function saveSettings() {
    if (window.app) {
        window.app.saveSettings();
    }
}

function loadHistory() {
    if (window.app) {
        window.app.loadHistory();
    }
}

// 初始化应用
document.addEventListener('DOMContentLoaded', function() {
    window.app = new DroneDetectionApp();
});
