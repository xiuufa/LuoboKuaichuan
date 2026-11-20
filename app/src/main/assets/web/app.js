/**
 * 萝卜快传 · 网页端主逻辑
 *
 * 全部接口由手机端的局域网 HTTP 服务提供（本页面就是该服务托管的静态资源）：
 *   GET    /api/info                         本机信息
 *   GET    /api/list                         传输板列表（轮询）
 *   POST   /api/text                         发送文本
 *   POST   /api/upload/init                  初始化文件上传
 *   PUT    /api/upload/{id}?offset=N         分片上传原始字节
 *   POST   /api/upload/{id}/complete         完成上传（服务端收满会自动完成，此步幂等）
 *   DELETE /api/upload/{id}                  取消上传
 *   DELETE /api/item/{id}                    删除条目
 *   POST   /api/clear                        清空传输板
 *   GET    /api/file/{id}?download=1         下载文件（支持 Range）
 *   GET    /api/qr.svg?data=...              二维码
 *
 * 无任何外网依赖。
 */
(function () {
  'use strict';

  // ------------------------------------------------------------------ 常量
  var BROWSER_SENDER = '网页访客';
  var SENDER_ID = (function () {
    try {
      var key = 'luobo_sender_id';
      var id = localStorage.getItem(key);
      if (!id) {
        id = 'web-' + Math.random().toString(36).slice(2, 10);
        localStorage.setItem(key, id);
      }
      return id;
    } catch (e) {
      return 'web-anonymous';
    }
  })();

  var POLL_LIST_MS = 1500;
  var POLL_INFO_MS = 15000;

  var state = {
    info: null,
    items: [],
    version: -1,
    boardSig: '',
    tasks: [],
    taskSeq: 0,
    connected: false,
    pumping: false,
    lastBoardAt: 0,
    toastTimer: null,
    confirmResolve: null,
    shutdown: false,
    timers: []
  };

  // ------------------------------------------------------------------ 工具
  function el(id) {
    return document.getElementById(id);
  }

  function formatSize(bytes) {
    if (bytes === null || bytes === undefined || isNaN(bytes)) return '未知大小';
    if (bytes < 1024) return bytes + ' B';
    var units = ['KB', 'MB', 'GB', 'TB'];
    var value = bytes / 1024;
    var i = 0;
    while (value >= 1024 && i < units.length - 1) {
      value /= 1024;
      i++;
    }
    return (value >= 100 ? value.toFixed(0) : value.toFixed(1)) + ' ' + units[i];
  }

  function formatTime(ts) {
    if (!ts) return '';
    var d = new Date(ts);
    if (isNaN(d.getTime())) return '';
    var now = new Date();
    var sameDay = d.toDateString() === now.toDateString();
    var hh = String(d.getHours()).padStart(2, '0');
    var mm = String(d.getMinutes()).padStart(2, '0');
    if (sameDay) return hh + ':' + mm;
    return (d.getMonth() + 1) + '-' + d.getDate() + ' ' + hh + ':' + mm;
  }

  function extOf(name) {
    var idx = String(name || '').lastIndexOf('.');
    if (idx <= 0 || idx === name.length - 1) return '';
    return name.slice(idx + 1).toUpperCase();
  }

  function toast(message) {
    var node = el('toast');
    node.textContent = message;
    node.classList.remove('hidden');
    // 强制 reflow 以便重复触发动画
    void node.offsetWidth;
    node.classList.add('show');
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(function () {
      node.classList.remove('show');
      setTimeout(function () { node.classList.add('hidden'); }, 220);
    }, 2400);
  }

  function copyText(text) {
    if (!text) return Promise.resolve(false);
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).then(function () {
        toast('已复制');
        return true;
      }).catch(function () { return legacyCopy(text); });
    }
    return Promise.resolve(legacyCopy(text));
  }

  function legacyCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '-1000px';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    ta.setSelectionRange(0, ta.value.length);
    var ok = false;
    try {
      ok = document.execCommand('copy');
    } catch (e) {
      ok = false;
    }
    document.body.removeChild(ta);
    toast(ok ? '已复制' : '复制失败，请长按手动复制');
    return ok;
  }

  // ------------------------------------------------------------------ HTTP
  function api(method, path, body) {
    var options = { method: method, headers: {} };
    if (body !== undefined && body !== null) {
      options.headers['Content-Type'] = 'application/json; charset=utf-8';
      options.body = JSON.stringify(body);
    }
    return fetch(path, options).then(function (response) {
      return response.text().then(function (text) {
        var data = null;
        try {
          data = text ? JSON.parse(text) : null;
        } catch (e) {
          data = null;
        }
        if (!response.ok || (data && data.ok === false)) {
          var error = new Error((data && data.error) || ('HTTP ' + response.status));
          error.status = response.status;
          error.data = data;
          throw error;
        }
        return data;
      });
    });
  }

  // ------------------------------------------------------------------ 确认弹窗
  function confirmDialog(title, message, confirmText) {
    return new Promise(function (resolve) {
      state.confirmResolve = resolve;
      el('modalTitle').textContent = title;
      el('modalMessage').textContent = message;
      el('modalConfirm').textContent = confirmText || '确定';
      el('modal').classList.remove('hidden');
    });
  }

  function closeConfirm(result) {
    el('modal').classList.add('hidden');
    var resolve = state.confirmResolve;
    state.confirmResolve = null;
    if (resolve) resolve(result);
  }

  // ------------------------------------------------------------------ 优雅关闭
  // 页面关闭 / 刷新 / 服务端退出时停止轮询、取消在途分片请求；重复调用直接跳过。
  function gracefulClose() {
    if (state.shutdown) return;
    state.shutdown = true;
    state.timers.forEach(function (id) { clearInterval(id); });
    state.timers = [];
    clearTimeout(state.toastTimer);
    state.tasks.forEach(function (task) {
      if (!task.xhr) return;
      try { task.xhr.abort(); } catch (e) { /* 忽略 */ }
      task.xhr = null;
    });
    if (state.confirmResolve) closeConfirm(false);
  }

  // ------------------------------------------------------------------ 信息
  function refreshInfo() {
    if (state.shutdown) return Promise.resolve();
    return api('GET', '/api/info').then(function (info) {
      state.info = info;
      state.connected = true;
      renderConnection(true);
      var name = info.deviceName || '本机';
      el('deviceName').textContent = name;
      el('brandSub').textContent = '局域网共享传输板 · ' + name;

      var url = info.url || (location.origin || '');
      el('serverUrl').textContent = url || location.origin;
      el('qrImage').src = '/api/qr.svg?data=' + encodeURIComponent(url || location.origin);

      var maxBytes = info.maxUploadBytes || 0;
      el('storageHint').textContent = info.storagePath ? ('保存目录：' + info.storagePath) : '';

      // 桌面端（exe / AppImage）支持退出服务；手机端没有这个能力，保持隐藏
      var canShutdown = (info.features || []).indexOf('shutdown') >= 0;
      el('btnShutdown').classList.toggle('hidden', !canShutdown);
    }).catch(function (error) {
      state.connected = false;
      renderConnection(false, error.message);
    });
  }

  function renderConnection(ok, detail) {
    var pill = el('connState');
    pill.classList.remove('ok', 'err');
    if (ok) {
      pill.classList.add('ok');
      pill.textContent = '已连接';
    } else {
      pill.classList.add('err');
      pill.textContent = detail ? '连接断开' : '连接中…';
    }
  }

  // ------------------------------------------------------------------ 传输板
  function refreshBoard(force) {
    if (state.shutdown) return Promise.resolve();
    return api('GET', '/api/list').then(function (data) {
      var items = data.items || [];
      state.version = typeof data.version === 'number' ? data.version : -1;
      state.items = items;
      var sig = state.version + '|' + items.map(function (item) {
        return item.id + ':' + item.state + ':' + item.received + ':' + item.size;
      }).join(',');
      if (force || sig !== state.boardSig) {
        state.boardSig = sig;
        renderBoard();
      }
      state.lastBoardAt = Date.now();
      renderConnection(true);
    }).catch(function (error) {
      state.connected = false;
      renderConnection(false, error.message);
    });
  }

  function renderBoard() {
    var container = el('boardList');
    var empty = el('boardEmpty');
    var items = state.items || [];
    // 正在上传/暂停/失败的本机任务并入传输板，单独的上传队列模块已移除
    var tasks = (state.tasks || []).filter(function (task) { return task.state !== 'done'; });
    // 本机任务对应的服务端条目（接收中）不再重复展示，避免同一文件出现两行
    var localIds = {};
    tasks.forEach(function (task) {
      if (task.uploadId) localIds[task.uploadId] = true;
      if (task.itemId) localIds[task.itemId] = true;
    });
    items = items.filter(function (item) { return !localIds[item.id]; });
    el('itemCount').textContent = String(items.length + tasks.length);
    empty.classList.toggle('hidden', items.length + tasks.length > 0);

    var fragment = document.createDocumentFragment();
    tasks.forEach(function (task) {
      fragment.appendChild(renderTaskRow(task));
    });
    items.forEach(function (item) {
      fragment.appendChild(renderItem(item));
    });
    container.replaceChildren(fragment);
  }

  function renderItem(item) {
    var row = document.createElement('div');
    row.className = 'board-item kind-' + (item.kind === 'text' ? 'text' : 'file');
    if (item.state === 'receiving') row.classList.add('is-receiving');
    if (item.state === 'failed') row.classList.add('is-failed');

    var badge = document.createElement('span');
    badge.className = 'badge';
    badge.textContent = item.kind === 'text' ? '文本' : (extOf(item.name) || '文件');
    row.appendChild(badge);

    var main = document.createElement('div');
    main.className = 'board-main';

    var title = document.createElement('span');
    title.className = 'board-title';
    if (item.kind === 'text') {
      title.textContent = item.text || '';
      title.title = '点击复制';
      title.addEventListener('click', function () { copyText(item.text || ''); });
    } else {
      title.textContent = item.name || '未命名文件';
      title.title = '点击下载';
      title.addEventListener('click', function () {
        if (item.state === 'ready') downloadItem(item);
        else if (item.state === 'receiving') toast('文件还在接收中，请稍候');
      });
    }
    main.appendChild(title);

    var meta = document.createElement('div');
    meta.className = 'board-meta';
    if (item.kind === 'text') {
      meta.appendChild(span(formatSize(item.size || 0)));
    } else {
      meta.appendChild(span(formatSize(item.size || 0)));
      if (item.state === 'receiving') {
        var percent = item.size > 0 ? Math.floor((item.received || 0) * 100 / item.size) : 0;
        meta.appendChild(span('接收中 ' + percent + '%'));
      }
    }
    meta.appendChild(span(item.sender || '未知设备'));
    meta.appendChild(span(formatTime(item.createdAt)));
    if (item.state === 'failed') meta.appendChild(span('已失败'));
    main.appendChild(meta);

    if (item.state === 'receiving') {
      var bar = document.createElement('div');
      bar.className = 'progress receiving-bar';
      var inner = document.createElement('i');
      var percent2 = item.size > 0 ? Math.min(100, Math.floor((item.received || 0) * 100 / item.size)) : 0;
      inner.style.width = percent2 + '%';
      bar.appendChild(inner);
      main.appendChild(bar);
    }

    row.appendChild(main);

    var actions = document.createElement('div');
    actions.className = 'board-actions';
    var myId = (state.info && state.info.deviceId) || '';
    var mine = !!item.senderId && !!myId && item.senderId === myId;
    if (item.kind === 'file' && item.state === 'ready') {
      var download = document.createElement('button');
      download.className = 'btn ghost small';
      download.textContent = '下载';
      download.addEventListener('click', function () { downloadItem(item); });
      actions.appendChild(download);
    }
    if (item.state === 'ready' && (item.kind === 'text' || mine)) {
      var resend = document.createElement('button');
      resend.className = 'btn ghost small';
      resend.textContent = '再次发送';
      resend.addEventListener('click', function () { resendItem(item); });
      actions.appendChild(resend);
    }
    if (item.kind === 'file' && item.state === 'ready' && !mine) {
      var delFile = document.createElement('button');
      delFile.className = 'btn ghost small danger';
      delFile.textContent = '删除原文件';
      delFile.addEventListener('click', function () { deleteFileItem(item); });
      actions.appendChild(delFile);
    }
    var del = document.createElement('button');
    del.className = 'btn ghost small';
    del.textContent = '删除记录';
    del.addEventListener('click', function () { deleteItem(item); });
    actions.appendChild(del);
    row.appendChild(actions);

    return row;
  }

  function span(text) {
    var node = document.createElement('span');
    node.textContent = text;
    return node;
  }

  function downloadItem(item) {
    var link = document.createElement('a');
    link.href = '/api/file/' + encodeURIComponent(item.id) + '?download=1';
    link.download = item.name || 'luobo_file';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  }

  function resendItem(item) {
    api('POST', '/api/item/' + encodeURIComponent(item.id) + '/resend', {}).then(function () {
      toast('已再次发送');
      return refreshBoard(true);
    }).catch(function (error) {
      toast('再次发送失败：' + error.message);
    });
  }

  function deleteFileItem(item) {
    confirmDialog(
      '删除原文件',
      '确定删除「' + (item.name || '') + '」的原文件吗？\n删除后无法恢复，记录会保留。',
      '删除原文件'
    ).then(function (ok) {
      if (!ok) return;
      api('DELETE', '/api/file/' + encodeURIComponent(item.id)).then(function () {
        toast('原文件已删除');
        return refreshBoard(true);
      }).catch(function (error) {
        toast('删除失败：' + error.message);
      });
    });
  }

  function deleteItem(item) {
    var big = item.kind === 'file' && item.size > ((state.info && state.info.bigFileThreshold) || 20 * 1024 * 1024);
    var doDelete = function () {
      return api('DELETE', '/api/item/' + encodeURIComponent(item.id)).then(function () {
        toast('已删除');
        return refreshBoard(true);
      }).catch(function (error) {
        toast('删除失败：' + error.message);
      });
    };
    if (big) {
      confirmDialog(
        '删除大文件',
        '确定删除「' + (item.name || '') + '」吗？\n文件大小 ' + formatSize(item.size) + '，删除后无法恢复。',
        '删除'
      ).then(function (ok) {
        if (ok) doDelete();
      });
    } else {
      doDelete();
    }
  }

  function clearBoard() {
    confirmDialog('清空传输板', '确定清空传输板上的所有条目吗？已接收的文件会被删除，此操作无法恢复。', '清空')
      .then(function (ok) {
        if (!ok) return;
        api('POST', '/api/clear').then(function () {
          toast('已清空');
          return refreshBoard(true);
        }).catch(function (error) {
          toast('清空失败：' + error.message);
        });
      });
  }

  // ------------------------------------------------------------------ 发送文本
  function sendText() {
    var input = el('textInput');
    var text = input.value.trim();
    if (!text) {
      // 输入框为空时，发送按钮直接发送剪贴板内容
      sendClipboard();
      return;
    }
    var button = el('btnSendText');
    button.disabled = true;
    api('POST', '/api/text', {
      text: text,
      sender: BROWSER_SENDER,
      senderId: SENDER_ID
    }).then(function () {
      input.value = '';
      toast('已发送');
      return refreshBoard(true);
    }).catch(function (error) {
      toast('发送失败：' + error.message);
    }).then(function () {
      button.disabled = false;
    });
  }

  function sendClipboard() {
    if (!navigator.clipboard || !navigator.clipboard.readText) {
      toast('当前浏览器不允许读取剪贴板，请直接粘贴到输入框');
      el('textInput').focus();
      return;
    }
    navigator.clipboard.readText().then(function (text) {
      if (!text || !text.trim()) {
        toast('剪贴板是空的');
        return;
      }
      return api('POST', '/api/text', {
        text: text,
        sender: BROWSER_SENDER,
        senderId: SENDER_ID
      }).then(function () {
        toast('剪贴板已发送');
        return refreshBoard(true);
      });
    }).catch(function () {
      toast('浏览器不允许读取剪贴板，请直接粘贴到输入框');
      el('textInput').focus();
    });
  }

  // ------------------------------------------------------------------ 上传队列
  function formatState(task) {
    switch (task.state) {
      case 'queued': return '等待上传';
      case 'uploading': return '上传中';
      case 'paused': return '已暂停';
      case 'failed': return '失败：' + (task.error || '未知错误');
      case 'done': return '已完成';
      case 'cancelled': return '已取消';
      default: return task.state;
    }
  }

  function enqueueFiles(files) {
    if (!files || !files.length) return;
    var maxBytes = (state.info && state.info.maxUploadBytes) || 16 * 1024 * 1024 * 1024;
    var added = 0;
    for (var i = 0; i < files.length; i++) {
      var file = files[i];
      if (!file) continue;
      if (file.size > maxBytes) {
        toast('「' + file.name + '」超过单文件上限 ' + formatSize(maxBytes));
        continue;
      }
      state.tasks.push({
        id: 't' + (++state.taskSeq),
        file: file,
        name: file.name || '未命名文件',
        size: file.size,
        mime: file.type || '',
        state: 'queued',
        sent: 0,
        displaySent: 0,
        uploadId: null,
        itemId: null,
        chunkSize: 0,
        error: null,
        xhr: null
      });
      added++;
    }
    if (added > 0) {
      toast('开始上传 ' + added + ' 个文件');
      renderQueue();
      pump();
    }
  }

  function renderQueue() {
    renderBoard();
  }

  function renderTaskRow(task) {
    var row = document.createElement('div');
    row.className = 'board-item kind-file task-row state-' + task.state;
    row.dataset.id = task.id;

    var badge = document.createElement('span');
    badge.className = 'badge';
    badge.textContent = '上传';
    row.appendChild(badge);

    var main = document.createElement('div');
    main.className = 'board-main';

    var title = document.createElement('span');
    title.className = 'board-title';
    title.textContent = task.name;
    main.appendChild(title);

    var sent = task.displaySent || task.sent || 0;
    var meta = document.createElement('div');
    meta.className = 'board-meta';
    meta.appendChild(span(formatSize(sent) + ' / ' + formatSize(task.size)));
    var stateNode = span(formatState(task));
    stateNode.className = 'state ' + task.state;
    meta.appendChild(stateNode);
    meta.appendChild(span('本机'));
    main.appendChild(meta);

    var bar = document.createElement('div');
    bar.className = 'progress';
    var inner = document.createElement('i');
    inner.style.width = (task.size > 0 ? Math.min(100, Math.floor(sent * 100 / task.size)) : 0) + '%';
    bar.appendChild(inner);
    main.appendChild(bar);

    row.appendChild(main);

    var actions = document.createElement('div');
    actions.className = 'board-actions';
    if (task.state === 'uploading') {
      actions.appendChild(miniButton('⏸', '暂停', function () {
        task.state = 'paused';
        renderQueue();
      }));
      actions.appendChild(miniButton('✕', '取消', function () { cancelTask(task); }));
    } else if (task.state === 'paused') {
      actions.appendChild(miniButton('▶', '继续', function () { resumeTask(task); }));
      actions.appendChild(miniButton('✕', '取消', function () { cancelTask(task); }));
    } else if (task.state === 'failed') {
      actions.appendChild(miniButton('↻', '重试', function () { retryTask(task); }));
      actions.appendChild(miniButton('✕', '移除', function () { cancelTask(task); }));
    } else if (task.state === 'queued') {
      actions.appendChild(miniButton('✕', '取消', function () { cancelTask(task); }));
    }
    row.appendChild(actions);
    return row;
  }

  function miniButton(label, title, onClick) {
    var button = document.createElement('button');
    button.className = 'icon-mini';
    button.textContent = label;
    button.title = title;
    button.addEventListener('click', onClick);
    return button;
  }

  function cancelTask(task) {
    task.state = 'cancelled';
    if (task.xhr) {
      try {
        task.xhr.abort();
      } catch (e) {
        // ignore
      }
    }
    cleanupTask(task).then(function () {
      state.tasks = state.tasks.filter(function (item) { return item !== task; });
      renderQueue();
      refreshBoard(true);
    });
  }

  function cleanupTask(task) {
    if (!task.uploadId) return Promise.resolve();
    var uploadId = task.uploadId;
    task.uploadId = null;
    return api('DELETE', '/api/upload/' + encodeURIComponent(uploadId)).catch(function () {
      // 会话可能已结束，忽略
    });
  }

  function resumeTask(task) {
    if (task.state !== 'paused') return;
    task.state = 'queued';
    task.error = null;
    renderQueue();
    pump();
  }

  function retryTask(task) {
    if (task.state !== 'failed') return;
    task.state = 'queued';
    task.error = null;
    renderQueue();
    pump();
  }

  function pump() {
    if (state.pumping) return;
    state.pumping = true;
    (function loop() {
      var task = state.tasks.find(function (item) { return item.state === 'queued'; });
      if (!task) {
        state.pumping = false;
        renderQueue();
        return;
      }
      task.state = 'uploading';
      task.error = null;
      renderQueue();
      runUpload(task).then(function () {
        loop();
      }).catch(function (error) {
        if (task.state === 'cancelled') {
          state.tasks = state.tasks.filter(function (item) { return item !== task; });
        } else if (task.state !== 'paused') {
          task.state = 'failed';
          task.error = error && error.message ? error.message : '上传失败';
        }
        renderQueue();
        loop();
      });
    })();
  }

  function runUpload(task) {
    if (task.state === 'paused' || task.state === 'cancelled') {
      return Promise.resolve();
    }
    var prepare = task.uploadId
      ? Promise.resolve()
      : api('POST', '/api/upload/init', {
          name: task.name,
          size: task.size,
          mime: task.mime,
          sender: BROWSER_SENDER,
          senderId: SENDER_ID
        }).then(function (init) {
          task.uploadId = init.uploadId;
          task.itemId = init.itemId;
          task.chunkSize = init.chunkSize || 1024 * 1024;
          task.sent = 0;
          task.displaySent = 0;
          if (init.completed) {
            task.state = 'done';
            task.sent = task.size;
            task.displaySent = task.size;
            renderQueue();
            refreshBoard(true);
            return 'completed';
          }
          return null;
        });

    return prepare.then(function (early) {
      if (early === 'completed') return;
      return uploadChunks(task);
    });
  }

  function uploadChunks(task) {
    if (task.state === 'paused' || task.state === 'cancelled') return Promise.resolve();
    if (task.sent >= task.size) return finishTask(task);

    var end = Math.min(task.sent + task.chunkSize, task.size);
    var blob = task.file.slice(task.sent, end);
    return putChunk(task, blob, task.sent).then(function (result) {
      task.sent = result.received;
      task.displaySent = result.received;
      renderQueue();
      if (result.completed && task.sent >= task.size) {
        return finishTask(task);
      }
      return uploadChunks(task);
    }).catch(function (error) {
      if (error && error.offset !== undefined) {
        // 服务端偏移不一致：按服务端收到的位置继续
        task.sent = error.offset;
        task.displaySent = error.offset;
        renderQueue();
        return uploadChunks(task);
      }
      if (error && error.status === 404) {
        // 上传会话已失效：重新初始化
        task.uploadId = null;
        task.sent = 0;
        task.displaySent = 0;
        renderQueue();
        return runUpload(task);
      }
      throw error;
    });
  }

  function finishTask(task) {
    var complete = task.uploadId
      ? api('POST', '/api/upload/' + encodeURIComponent(task.uploadId) + '/complete').catch(function (error) {
          if (error && error.status === 409) return null; // 已自动完成
          throw error;
        })
      : Promise.resolve();
    return complete.then(function () {
      task.state = 'done';
      task.sent = task.size;
      task.displaySent = task.size;
      renderQueue();
      refreshBoard(true);
    });
  }

  function putChunk(task, blob, offset) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      task.xhr = xhr;
      xhr.open('PUT', '/api/upload/' + encodeURIComponent(task.uploadId) + '?offset=' + offset);
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');
      xhr.upload.onprogress = function (event) {
        if (event.lengthComputable) {
          task.displaySent = offset + event.loaded;
          renderQueue();
        }
      };
      xhr.onload = function () {
        task.xhr = null;
        var data = null;
        try {
          data = JSON.parse(xhr.responseText);
        } catch (e) {
          data = null;
        }
        if (xhr.status >= 200 && xhr.status < 300 && data && data.ok) {
          resolve(data);
          return;
        }
        if (xhr.status === 409 && data && data.error === 'offset mismatch') {
          var mismatch = new Error('offset mismatch');
          mismatch.offset = data.offset;
          mismatch.status = 409;
          reject(mismatch);
          return;
        }
        var error = new Error((data && data.error) || ('HTTP ' + xhr.status));
        error.status = xhr.status;
        reject(error);
      };
      xhr.onerror = function () {
        task.xhr = null;
        reject(new Error('网络错误'));
      };
      xhr.onabort = function () {
        task.xhr = null;
        reject(new Error('已取消'));
      };
      xhr.send(blob);
    });
  }

  function clearFinished() {
    state.tasks = state.tasks.filter(function (task) {
      return task.state !== 'done' && task.state !== 'cancelled';
    });
    renderQueue();
  }

  // ------------------------------------------------------------------ 事件绑定
  function bindEvents() {
    el('btnRefresh').addEventListener('click', function () {
      refreshInfo();
      refreshBoard(true);
      toast('已刷新');
    });
    el('btnShutdown').addEventListener('click', function () {
      confirmDialog('退出服务', '退出后其它设备将无法连接，需要重新启动「萝卜快传」才能继续传输。', '退出')
        .then(function (ok) {
          if (!ok) return;
          var markStopped = function () {
            gracefulClose();
            renderConnection(false);
            el('connState').textContent = '服务已停止';
            el('connState').classList.remove('ok', 'err');
            toast('服务已停止，可以关闭此页面');
          };
          api('POST', '/api/shutdown', {}).then(markStopped).catch(markStopped);
        });
    });
    el('btnCopyUrl').addEventListener('click', function () {
      copyText(el('serverUrl').textContent);
    });
    el('btnSendText').addEventListener('click', sendText);
    el('btnClipboard').addEventListener('click', sendClipboard);
    el('btnClearBoard').addEventListener('click', clearBoard);

    el('textInput').addEventListener('keydown', function (event) {
      if (event.key !== 'Enter') return;
      if (event.shiftKey) return;        // Shift+Enter 换行
      if (event.isComposing) return;     // 输入法选词中不发送
      event.preventDefault();
      sendText();
    });

    el('fileInput').addEventListener('change', function (event) {
      enqueueFiles(event.target.files);
      event.target.value = '';
    });

    // 文件拖到页面任意位置都可以上传
    var dragDepth = 0;
    ['dragenter', 'dragover'].forEach(function (name) {
      document.addEventListener(name, function (event) {
        event.preventDefault();
        if (name === 'dragenter') dragDepth++;
        document.body.classList.add('drag-over');
      });
    });
    ['dragleave', 'drop'].forEach(function (name) {
      document.addEventListener(name, function (event) {
        event.preventDefault();
        if (name === 'dragleave') {
          dragDepth = Math.max(0, dragDepth - 1);
          if (dragDepth > 0) return;
        }
        if (name === 'drop') dragDepth = 0;
        document.body.classList.remove('drag-over');
      });
    });
    document.addEventListener('drop', function (event) {
      event.preventDefault();
      if (event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files.length) {
        enqueueFiles(event.dataTransfer.files);
      }
    });

    // 页面整体拖拽兜底（防止浏览器直接打开文件）
    window.addEventListener('dragover', function (event) { event.preventDefault(); });
    window.addEventListener('drop', function (event) { event.preventDefault(); });

    el('modalCancel').addEventListener('click', function () { closeConfirm(false); });
    el('modalConfirm').addEventListener('click', function () { closeConfirm(true); });
    el('modal').addEventListener('click', function (event) {
      if (event.target === el('modal')) closeConfirm(false);
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && !el('modal').classList.contains('hidden')) {
        closeConfirm(false);
      }
    });

    document.addEventListener('visibilitychange', function () {
      if (!document.hidden) {
        refreshInfo();
        refreshBoard(true);
      }
    });

    // 关闭 / 刷新 / 切走页面时优雅收尾（停止轮询、取消在途上传）
    window.addEventListener('pagehide', gracefulClose);
    window.addEventListener('beforeunload', gracefulClose);
  }

  // ------------------------------------------------------------------ 启动
  function init() {
    bindEvents();
    refreshInfo();
    refreshBoard(true);
    state.timers.push(setInterval(function () {
      refreshBoard(false);
    }, POLL_LIST_MS));
    state.timers.push(setInterval(function () {
      refreshInfo();
    }, POLL_INFO_MS));
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
