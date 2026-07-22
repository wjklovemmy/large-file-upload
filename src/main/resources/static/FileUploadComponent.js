/**
 * FileUploadComponent.js是大文件上传的前端组件，实现文件合法性校验、文件分片、并发上传，断点计算与续传等功能，兼容IE11
 * FileUploadComponent.js会调用WorkerPool.js、worker.js
 */
const FileUploadComponent = (function () {
    // 文件传输兼容默认配置，可以根据实际情况修改
    const defaultConfig = {
        apiBase: 'http://localhost:8080/api/upload', // 服务器地址
        chunkSize: 2 * 1024 * 1024, // 文件分片大小，单位字节
        maxWorkers: 4, // 处理线程池中工作线程的数量
        defaultConcurrency: 3, // 分片同时传输的数量
        maxAllowFileSize: 5 * 1024 * 1024 * 1024, // 一次最大可允许上传的文件大小，单位字节，5 * 1024 * 1024 * 1024表示5G
        allowedTypes: [], // 允许上传的文件MIME类型，可以使text/plain、image/jpeg等
        retryConfig: {  // 单个分片上传失败时的重试配置
            maxRetries: 3,  // 单个分片上传失败重试三次，结合重试时间按间隔和指数退避算法因子，三次重试的等待时间间隔分别是1秒、2秒、4秒
            retryDelay: 1000,  // 重试时间间隔，单位毫秒
            backoffFactor: 2   // 指数退避算法因子
        },
        chunkUploadTimeout: 30000 // 单个文件分片的上传超时时间，单位毫秒，30000表示3秒
    };

    function FileUploadComponent(config) {
        if (config === void 0) {
            config = {};
        }
        // 合并自定义配置和默认配置，兼容IE11
        this.config = {};
        for (const key in defaultConfig) {
            if (defaultConfig.hasOwnProperty(key)) {
                this.config[key] = defaultConfig[key];
            }
        }
        for (const customKey in config) {
            if (config.hasOwnProperty(customKey)) {
                this.config[customKey] = config[customKey];
            }
        }

        // 上传任务状态管理，IE11模拟弱Map兼容
        this.uploadingFiles = {};
        this._events = {};
    }

    FileUploadComponent.prototype.validateFile = function (file, config) {
        // 1.大小校验
        if (config.maxAllowFileSize !== -1 && file.size > config.maxAllowFileSize) {
            const errorMsg = '文件' + file.name + '大小超出系统限制的最大允许值，系统最大可上传文件大小为' + this.formatSize(this.config.maxAllowFileSize);
            return {valid: false, type: 'size', message: errorMsg};
        }

        // 2.不允许上传exe文件
        const fileNameLower = file.name.toLowerCase();
        if (fileNameLower.indexOf('.exe', fileNameLower.length - 4) !== -1) {
            return {valid: false, type: 'type', message: '不允许上传exe类型的可执行文件'};
        }

        // 3.MIME类型白名单校验，修复原逻辑BUG
        if (config.allowedTypes && config.allowedTypes.length > 0) {
            const isValidType = config.allowedTypes.some(function (type) {
                if (type.indexOf('/*', type.length -2) !== -1) {
                    const prefix = type.split('/');
                    return file.type.indexOf(prefix + '/') === 0;
                }
                return file.type === type;
            });

            if (!isValidType) {
                return {valid: false, type: 'type', message: '文件类型不支持'};
            }
        }

        return {valid: true, message: ''}
    }

    // 添加上传任务
    FileUploadComponent.prototype.addUploadTask = function (file) {
        // 先进行文件合法性校验
        const result = this.validateFile(file, this.config);
        const _this = this;

        // 如果文件校验不通过，直接返回
        if (!result.valid) {
            const eventName = result.type === 'size' ? 'fileSizeExceeded' : 'fileTypeInvalid';
            this._emit(eventName, {fileName: file.name, message: result.message});
            return null;
        }

        // 文件校验通过，执行后续处理流程
        const fileId = this.generateFileId(file); // 生成文件ID
        const task = {
            id: fileId, // 文件ID
            file: file, // 具体文件内容
            totalChunks: Math.ceil(file.size / this.config.chunkSize), // 文件总分片数量
            uploadedChunks: {}, // 已经上传的文件分片集合
            status: 'pending', // 文件传输状态
            progress: 0, // 文件传输百分比
            md5: null, // 文件的MD5值
            startTime: null   // 文件传输的开始时间
        };

        this.uploadingFiles[fileId] = task;
        this._emit('taskCreated', task);

        // IE11兼容异步逻辑
        this.startUpload(task).then(function () {
        })['catch'](function (err) {
            task.status = 'error';
            _this._emit('uploadError', {task: task, error: err});
        });

        return fileId;
    };

    // IE11兼容自定义UUID生成
    FileUploadComponent.prototype.generateFileId = function (file) {
        const randomPart = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
            const r = Math.random() *16 | 0, v = c === 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
        });
        return file.name + '-' + file.size + '-' + file.lastModified + '-' + randomPart;
    };

    // 上传主流程，Promise链式调用
    FileUploadComponent.prototype.startUpload = function (task) {
        const _this = this;
        task.status = 'checking';
        task.startTime = Date.now();
        this._emit('statusUpdate', {taskId: task.id, status: '检测中...'});

        return new Promise(function (resolve, reject) {
            const pool = new WorkerPool('worker.js', _this.config.maxWorkers);
            _this._emit('statusUpdate', {taskId: task.id, status: '文件的MD5值计算中...'});
            pool.exec({file: task.file, chunkSize: _this.config.chunkSize})
                .then(function (fileMD5) {
                    task.md5 = fileMD5.md5;
                    pool.terminate();
                    // 秒传检测请求
                    return fetch(_this.config.apiBase + '/check', {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({
                            md5: task.md5,
                            fileName: task.file.name,
                            fileMimeType: task.file.type,
                            totalChunks: task.totalChunks,
                            fileSize: task.file.size
                        })
                    });
                })
                .then(function (checkRes) {
                    return checkRes.json();
                })
                .then(function (checkData) {
                    // 秒传成功逻辑
                    if (checkData.code === 200 && checkData.data && checkData.data.exists) {
                        task.status = 'completed';
                        task.progress = 100;
                        const endTime = Date.now();
                        _this._emit('progressUpdate', {taskId: task.id, progress: 100});
                        _this._emit('statusUpdate', {
                            taskId: task.id,
                            status: '秒传成功，传输时间：' + _this.formatClockTime(task.startTime) + ',传输结束时间：' + _this.formatClockTime(endTime) + ',传输耗时：' + _this.getFormattedDuration(task.startTime, endTime)
                        });
                        _this._emit('uploadSuccess', task);
                        resolve();
                        return;
                    }
                    // 断点续传获取已上传分片
                    task.uploadedChunks = {};
                    (checkData.data.uploadedChunks || []).forEach(function (idx) {
                        task.uploadedChunks[idx] = true;
                    });
                    task.status = 'uploading';
                    _this._emit('statusUpdate', {taskId: task.id, status: '上传中...'});
                    return _this.uploadChunks(task);
                })
                .then(function () {
                    resolve();
                })
                ['catch'](function (error) {
                console.error(error);
                task.status = 'error';
                _this._emit('statusUpdate', {taskId: task.id, status: '错误 '});
                _this._emit('uploadError', {task: task, error: error});
                reject(error);
            });
        });
    };

    // 并行调度所有待上传分片
    FileUploadComponent.prototype.uploadedChunks = function (task, concurrency) {
        const uploadConcurrency = concurrency || this.config.defaultConcurrency;
        const chunksToUpload = [];
        const _this = this;

        for (let i = 0; i < task.totalChunks; i++) {
            if (!task.uploadedChunks[i]) {
                chunksToUpload.push(i)
            }
        }

        if (chunksToUpload.length === 0) {
            return this.mergeChunks(task);
        }

        let activeUploads = 0
        let nextChunkIndex = 0;
        let hasFatalError = false;

        return new Promise(function (resolve, reject) {
            const runNext = function () {
                if (task.status === 'paused' || hasFatalError) {
                    return;
                }
                if (nextChunkIndex >= chunksToUpload.length && activeUploads === 0) {
                    _this.mergeChunks(task).then(resolve)['catch'](reject);
                    return;
                }

                while (activeUploads < uploadConcurrency && nextChunkIndex < chunksToUpload.length) {
                    const chunkIndex = chunksToUpload[nextChunkIndex++];
                    activeUploads++;
                    _this.uploadSingleChunkWithRetry(task, chunkIndex, 0)
                        .then(function () {
                            activeUploads--;
                            runNext();
                        })
                        ['catch'](function (err) {
                        activeUploads--;
                        if (task.status !== 'paused') {
                            task.status = 'error';
                            hasFatalError = true;
                            _this._emit('statusUpdate', {taskId: task.id, status: '上传失败'});
                            reject(new Error('分片' + chunkIndex + ' 上传失败：' + err.message));
                            _this._emit('uploadError', {task: task, error: err});
                        }
                    });
                }
            };
            runNext();
        });
    };

    // IE11兼容带重试的单分片上传
    FileUploadComponent.prototype.uploadSingleChunkWithRetry = function (task, chunkIndex, retryCount) {
        const _this = this;
        const start = chunkIndex * this.config.chunkSize;
        const end = Math.min(start + this.config.chunkSize, task.file.size);
        const chunk = task.file.slice(start, end);
        const formData = new FormData();
        formData.append('file', chunk);
        formData.append('md5', task.md5);
        formData.append('fileName', task.file.name);
        formData.append('chunkIndex', chunkIndex);
        formData.append('totalChunks', task.totalChunks);

        return new Promise(function (resolve, reject) {
            let isTimeout = false;
            const timeoutId = setTimeout(function () {
                isTimeout = true;
                reject(new Error('分片上传超时'));
            }, _this.config.chunkUploadTimeout);

            fetch(_this.config.apiBase + '/chunk', {
                method: 'POST',
                body: formData
            }).then(function (response) {
                clearTimeout(timeoutId);
                if (isTimeout) {
                    return;
                }
                if (!response.ok) {
                    throw new Error('HTTP ' + response.status + ': ' + response.statusText);
                }
                return response.json();
            }).then(function (result) {
                if (isTimeout) return;
                if (result.code !== 200) {
                    throw new Error(result.message || '服务器返回上传失败');
                }
                task.uploadedChunks[chunkIndex] = true;
                let uploadedCount = 0;
                for (const k in task.uploadedChunks) uploadedCount++;
                const currentProgress = Math.round(uploadedCount / task.totalChunks * 100);
                task.progress = currentProgress;
                _this._emit('progressUpdate', {taskId: task.id, progress: currentProgress});
                resolve(result);
            })['catch'](function (error) {
                clearTimeout(timeoutId);
                if (isTimeout) return;
                if (retryCount < _this.config.retryConfig.maxRetries) {
                    const delay = _this.config.retryConfig.retryDelay * Math.pow(_this.config.retryConfig.backoffFactor, retryCount);
                    _this.sleep(delay).then(function () {
                        _this.uploadSingleChunkWithRetry(task, chunkIndex, retryCount + 1).then(resolve)['catch'](reject);
                    });
                } else {
                    reject(error);
                }
            });
        });
    };

    // 请求后端合并所有已上传分片
    FileUploadComponent.prototype.mergeChunks = function (task) {
        const _this = this;
        this._emit('statusUpdate', {taskId: task.id, status: '合并中...'});
        return new Promise(function (resolve, reject) {
            fetch(_this.config.apiBase + '/merge', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({
                    md5: task.md5,
                    fileName: task.file.name,
                    fileSize: task.file.size,
                    totalChunks: task.totalChunks
                })
            }).then(function (res) {
                return res.json();
            }).then(function (data){
                if (data.code === 200) {
                    task.status = 'completed';
                    task.progress = 100;
                    _this._emit('progessUpdate', {taskId: task.id, progress: 100});
                    const endTime = Date.now();
                    _this._emit('statusUpdate', {
                        taskId: task.id,
                        status: '完成，传输开始时间：' + _this.formatClockTime(task.startTime) + ',传输结束时间：' + _this.formatClockTime(endTime) + ',传输时间：' + _this.getFormattedDuration(task.startTime, endTime)
                    });
                    _this._emit('uploadSuccess', task);
                    resolve();
                } else {
                    throw new Error(data.message);
                }
            })['catch'](function (e) {
                task.status = 'error';
                _this._emit('statusUpdate', {taskId: task.id, status: '合并失败'});
                _this._emit('mergeError', {task: task, error: e});
                reject(e);
            });
        });
    };


    /***********************通用工具方法***********************************/
    // 格式化文件大小输出函数
    FileUploadComponent.prototype.formatSize = function (bytes) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    };

    // 格式化时间输出函数
    FileUploadComponent.prototype.formatClockTime = function (timestamp) {
        const date = new Date(timestamp);
        const hours = ('0' + date.getHours()).slice(-2);
        const minutes = ('0' + date.getMinutes()).slice(-2);
        const seconds = ('0' + date.getSeconds()).slice(-2);
        const ms = ('00' + date.getMilliseconds()).slice(-3);
        return hours + ":" + minutes + ":" + seconds + ":" + ms;
    }

    // 计算传输时间间隔并格式化输出函数
    FileUploadComponent.prototype.getFormattedDuration = function (startMs, endMs) {
        const diff = endMs -startMs;
        const ms = diff % 1000;
        const seconds = Math.floor((diff / 1000) % 60);
        const minutes = Math.floor((diff / (1000 * 60)) % 60);
        const hours = Math.floor((diff / (1000 * 60 * 60)) % 24);
        const pad = function (num) {return ('0' + num).slice(-2);};
        const padMs = function (num) {return ('00' + num).slice(-3);};
        return pad(hours) + ":" + pad(minutes) + ":" + pad(seconds) + "." + padMs(ms);
    }

    // 休眠函数，分片上传失败重试时调用
    FileUploadComponent.prototype.sleep = function (ms) {
        return new Promise(function (resolve) {
            setTimeout(resolve, ms)
        });
    };

    // 时间调度器
    FileUploadComponent.prototype.on = function (eventName, callback) {
        if (!this._events[eventName]) {
            this._events[eventName] = [];
        }
        this._events[eventName].push(callback);
    };

    FileUploadComponent.prototype._emit = function (eventName, data) {
        if (this._events[eventName]) {
            this._events[eventName].forEach(function (cb) {
                cb(data);
            });
        }
    };

    return FileUploadComponent;
})();