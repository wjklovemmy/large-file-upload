/**
 * Web Worker 自定义实现的线程池，完全兼容IE11
 */
var WorkerPool = (function () {
    function WorkerPool(workerScriptPath, maxWorkers) {
        if (maxWorkers === void 0) {
            maxWorkers = 4;
        }
        this.workerScriptPath = workerScriptPath;
        this.maxWorkers = maxWorkers;
        this.workers = [];
        this.idleWorkers = [];
        this.taskQueue = [];
        this._init();
    }

    WorkerPool.prototype._init = function () {
        var _this = this;
        for (var i = 0; i < this.maxWorkers; i++) {
            var worker = new Worker(this.workerScriptPath);
            worker.onmessage = function (e) {
                _this._handleMessage(worker, e);
            };
            worker.onerror = function (e) {
                _this._handleError(worker, e);
            };
            this.workers.push(worker);
            this.idleWorkers.push(worker);
        }
    };

    WorkerPool.prototype.exec = function (data) {
        var _this = this;
        return new Promise(function (resolve, reject) {
            var task = {data: data, resolve: resolve, reject: reject};
            if (_this.idleWorkers.length > 0) {
                var worker = _this.idleWorkers.pop();
                _this._dispatchTask(worker, task);
            } else {
                _this.taskQueue.push(task);
            }
        });
    };

    WorkerPool.prototype._dispatchTask = function (worker, task) {
        worker.postMessage(task.data);
        worker._currentTask = task;
    }

    WorkerPool.prototype._handleMessage = function (worker, event) {
        var task = worker._currentTask;
        if (!task) return;
        worker._currentTask = null;
        if (event.data.type === 'error') {
            task.reject(new Error(event.data.msg || 'Unknown error'));
        } else {
            task.resolve(event.data);
        }
        this._recycleWorker(worker);
    };

    WorkerPool.prototype._handleError = function (worker, error) {
        var task = worker._currentTask;
        if (task) {
            task.reject(error);
            worker._currentTask = null;
        }
        this._recycleWorker(worker);
    };

    WorkerPool.prototype._recycleWorker = function (worker) {
        this.idleWorkers.push(worker);
        if (this.taskQueue.length > 0 && this.idleWorkers.length > 0) {
            var nextTask = this.taskQueue.shift();
            var nextWorker = this.idleWorkers.pop();
            this._dispatchTask(nextWorker, nextTask);
        }
    };

    WorkerPool.prototype.terminate = function () {
        for (var i = 0; i < this.workers.length; i++) {
            this.workers[i].terminate();
        }
        this.workers = [];
        this.idleWorkers = [];
        this.taskQueue = [];
    };

    return WorkerPool;
})();