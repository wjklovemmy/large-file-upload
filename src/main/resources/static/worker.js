/**
 * MD5计算Worker组件 完全兼容IE11
 */
importScripts("./spark-md5.min.js")

self.onmessage = function (e) {
    var file = e.data.file;
    var chunkSize = e.data.chunkSize || 2 * 1024 * 1024;

    if (!file) {
        self.postMessage({ type : 'error', msg: 'No file provided'});
        return;
    }

    var spark = new SparkMD5.ArrayBuffer();
    var offset = 0;
    var fileSize = file.size;
    var reader = new FileReader();

    function processNextChunk() {
        if (offset >= fileSize) {
            var md5 = spark.end();
            self.postMessage({type: 'succss', md5: md5})
            return;
        }
        var slice = file.slice(offset, offset + chunkSize);
        reader.readAsArrayBuffer(slice);
    }

    reader.onload = function (event) {
        spark.append(event.target.result);
        offset += chunkSize;
        processNextChunk();
    };

    reader.onerror = function (err) {
        self.postMessage({type: 'error', msg: err.message});
    };

    processNextChunk();
 }