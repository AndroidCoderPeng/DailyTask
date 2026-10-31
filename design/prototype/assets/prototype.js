/* ===========================================================
   DailyTask · 界面原型 v2 —— 交互脚本
   =========================================================== */
(function () {
  'use strict';

  const $ = (sel, root) => (root || document).querySelector(sel);
  const $$ = (sel, root) => Array.prototype.slice.call((root || document).querySelectorAll(sel));

  /* ---------------------------------------------------------
     状态
  --------------------------------------------------------- */
  const state = {
    screen: 'main',
    history: [],
    running: true,
    mask: false,
    tasks: [
      { id: 1, time: '08:30:00', window: '08:20 – 08:40', state: 'done', actual: '08:29:47', progress: 100 },
      { id: 2, time: '12:30:00', window: '12:20 – 12:40', state: 'running', actual: '', progress: 42 },
      { id: 3, time: '18:30:00', window: '18:20 – 18:40', state: 'pending', actual: '', progress: 0 }
    ],
    nextId: 4,
    workday: [1, 2, 3, 4, 5],
    editingId: null,
    sheetTimeMode: 'add'
  };

  /* ---------------------------------------------------------
     工具
  --------------------------------------------------------- */
  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function nowTime() { const d = new Date(); return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()); }
  function fmt(d) { return d.getFullYear() + '年' + pad(d.getMonth() + 1) + '月' + pad(d.getDate()) + '日 ' + '周' + '日一二三四五六'.charAt(d.getDay()); }

  let toastTimer = null;
  function toast(text) {
    const el = $('#toast');
    el.textContent = text;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.hidden = true; }, 1800);
  }

  /* ---------------------------------------------------------
     页面导航
     注意：URL 同步放在最后并做容错，file:// 直接打开时
     history.replaceState 可能抛 SecurityError，不能影响切页。
  --------------------------------------------------------- */
  function syncHash(screen) {
    if (location.hash.slice(1) === screen) return;
    try {
      history.replaceState(null, '', '#' + screen);
    } catch (e) {
      try { location.hash = screen; } catch (e2) { /* file:// 下忽略 */ }
    }
  }

  function goto(screen, noHistory) {
    if (!$('.screen[data-screen="' + screen + '"]')) return;
    if (state.screen === screen) { syncHash(screen); return; }
    if (!noHistory) state.history.push(state.screen);
    state.screen = screen;

    // 总览模式下选择某个页面 → 自动回到单页浏览
    exitOverview();

    $$('.screen').forEach(function (s) {
      s.classList.toggle('is-active', s.dataset.screen === screen);
    });
    $$('.nav-item').forEach(function (b) {
      b.classList.toggle('is-active', b.dataset.goto === screen);
    });
    $$('.proto-switch button').forEach(function (b) {
      b.classList.toggle('is-active', b.dataset.goto === screen);
    });

    const active = $('.screen.is-active .scroll');
    if (active) active.scrollTop = 0;

    syncHash(screen);
  }

  function back() {
    const prev = state.history.pop() || 'main';
    goto(prev, true);
  }

  function exitOverview() {
    const stage = $('.stage');
    if (!stage.classList.contains('is-overview')) return;
    stage.classList.remove('is-overview');
    const btn = $('#overviewBtn');
    btn.classList.remove('is-on');
    btn.textContent = '总览';
  }

  /* ---------------------------------------------------------
     顶部时钟 / 倒计时
  --------------------------------------------------------- */
  function tickClock() {
    const t = nowTime();
    $('#statusClock').textContent = t.slice(0, 5);
    $('#heroClock').textContent = t;
    $('#maskClock').textContent = t.slice(0, 5);
    $('#mainDate').textContent = fmt(new Date());
  }

  function tickCountdown() {
    const now = new Date();
    const reset = new Date(now);
    reset.setHours(6, 0, 0, 0);
    if (reset <= now) reset.setDate(reset.getDate() + 1);
    let diff = Math.floor((reset - now) / 1000);
    const h = Math.floor(diff / 3600); diff -= h * 3600;
    const m = Math.floor(diff / 60); diff -= m * 60;
    $('#resetCountdown').textContent = pad(h) + ':' + pad(m) + ':' + pad(diff);
  }

  /* ---------------------------------------------------------
     首页：任务渲染
  --------------------------------------------------------- */
  const STATE_TEXT = { done: '已完成', running: '执行中', pending: '待执行', skipped: '已跳过' };

  function renderTasks() {
    const list = $('#taskList');
    const empty = $('#emptyState');

    $('#taskCount').textContent = state.tasks.length;
    if (!state.tasks.length) {
      list.innerHTML = '';
      empty.hidden = false;
      return;
    }
    empty.hidden = true;

    list.innerHTML = state.tasks.map(function (t, i) {
      const foot = t.state === 'running'
        ? '<span class="task-actual">倒计时 38s</span>'
        : '<span class="task-actual">' + (t.actual ? '实际 ' + t.actual : '实际 --:--:--') + '</span>';
      return '' +
        '<article class="task" data-state="' + t.state + '" data-id="' + t.id + '">' +
          '<div class="task-top">' +
            '<span class="task-idx">' + (i + 1) + '</span>' +
            '<div class="task-time">' + t.time + '</div>' +
            '<div class="task-meta">' +
              '<span class="task-state">' + STATE_TEXT[t.state] + '</span>' +
              '<span class="task-window">随机 ' + t.window + '</span>' +
            '</div>' +
          '</div>' +
          (t.state === 'running'
            ? '<div class="task-progress"><i style="width:' + t.progress + '%"></i></div>'
            : '') +
          '<div class="task-foot">' +
            '<span>钉钉 · 极速打卡</span><span class="sep">·</span><span>超时 60s</span>' + foot +
          '</div>' +
        '</article>';
    }).join('');
  }

  /* ---------------------------------------------------------
     首页：运行状态
  --------------------------------------------------------- */
  function renderRunState() {
    const pill = $('#runPill');
    const text = $('#runPillText');
    const btn = $('#runBtn');
    const btnText = $('#runBtnText');
    const tip = $('#tipBar');
    const tipText = $('#tipText');
    const title = $('#mainTitle');

    if (state.running) {
      pill.className = 'pill pill-running';
      text.textContent = '运行中';
      btn.className = 'btn btn-danger btn-block';
      btnText.textContent = '停止今日任务';
      btn.querySelector('svg').innerHTML = '<rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor"/>';
      tip.dataset.tone = 'info';
      tipText.textContent = '准备执行第 2 个任务 · 计划 12:30:00';
      title.textContent = '今日任务执行中';
    } else {
      pill.className = 'pill pill-idle';
      text.textContent = '未运行';
      btn.className = 'btn btn-primary btn-block';
      btnText.textContent = '启动今日任务';
      btn.querySelector('svg').innerHTML = '<path d="M8 5v14l11-7z" fill="currentColor"/>';
      tip.dataset.tone = 'warn';
      tipText.textContent = '任务已停止，点击按钮可重新启动今日任务。';
      title.textContent = '今日任务待启动';
    }
  }

  /* ---------------------------------------------------------
     启动 / 停止
  --------------------------------------------------------- */
  function toggleRun() {
    state.running = !state.running;

    if (state.running) {
      const next = state.tasks.filter(function (t) { return t.state === 'pending'; })[0];
      if (next) { next.state = 'running'; next.progress = 8; }
      simulate();
    } else {
      state.tasks.forEach(function (t) {
        if (t.state === 'running') { t.state = 'pending'; t.progress = 0; }
      });
      toast('任务已停止，请及时打开下次任务');
    }
    renderRunState();
    renderTasks();
  }

  let simTimer = null;
  function simulate() {
    clearInterval(simTimer);
    simTimer = setInterval(function () {
      if (!state.running) return;
      const cur = state.tasks.filter(function (t) { return t.state === 'running'; })[0];
      if (!cur) return;
      cur.progress = Math.min(96, cur.progress + 4);
      if (cur.progress >= 96) {
        cur.state = 'done';
        cur.actual = nowTime();
        cur.progress = 100;
        const next = state.tasks.filter(function (t) { return t.state === 'pending'; })[0];
        if (next) { next.state = 'running'; next.progress = 4; }
        toast('第 ' + (state.tasks.indexOf(cur) + 1) + ' 个任务已完成');
      }
      renderTasks();
    }, 900);
  }

  /* ---------------------------------------------------------
     弹窗 / 底部弹窗
  --------------------------------------------------------- */
  function openSheet(sel) { $(sel).hidden = false; }
  function closeSheets() { $$('.sheet-mask').forEach(function (s) { s.hidden = true; }); }

  function openListSheet(title, options, onPick) {
    $('#listSheetTitle').textContent = title;
    $('#listSheetBody').innerHTML = options.map(function (o) {
      return '<button class="opt" data-val="' + o.value + '">' + o.label +
        (o.checked ? '<span class="ok">✓</span>' : '') + '</button>';
    }).join('');
    $$('#listSheetBody .opt').forEach(function (b) {
      b.addEventListener('click', function () {
        closeSheets();
        onPick(b.dataset.val);
      });
    });
    openSheet('#sheetList');
  }

  function openDialog(opt) {
    $('#dialogTitle').textContent = opt.title || '提示';
    $('#dialogMsg').textContent = opt.msg || '';
    $('#dialogMsg').style.textAlign = 'center';
    $('#dialogOk').textContent = opt.okText || '确定';
    $('#dialogCancel').textContent = opt.cancelText || '取消';
    $('#dialogCancel').style.display = opt.hideCancel ? 'none' : '';
    $('#dialogMask').hidden = false;
    $('#dialogOk').onclick = function () {
      $('#dialogMask').hidden = true;
      if (opt.onOk) opt.onOk();
    };
    $('#dialogCancel').onclick = function () {
      $('#dialogMask').hidden = true;
      if (opt.onCancel) opt.onCancel();
    };
  }

  function openInputDialog(title, hint, value, onOk) {
    openDialog({
      title: title,
      onOk: function () {
        const input = $('#dialogInput');
        onOk(input ? input.value.trim() : '');
      },
      onCancel: function () {
        const msg = $('#dialogMsg');
        msg.textContent = '';
        msg.style.textAlign = 'center';
      }
    });

    const msg = $('#dialogMsg');
    msg.textContent = '';
    msg.style.textAlign = 'left';
    const input = document.createElement('input');
    input.className = 'input';
    input.id = 'dialogInput';
    input.value = value || '';
    input.placeholder = hint || '';
    msg.appendChild(input);
    setTimeout(function () { input.focus(); }, 120);
  }

  /* ---------------------------------------------------------
     时间选择器
  --------------------------------------------------------- */
  function buildTimePicker() {
    const hours = $('.tp-col[data-col="hour"]');
    const mins = $('.tp-col[data-col="min"]');
    const secs = $('.tp-col[data-col="sec"]');

    function fill(el, len) {
      let html = '';
      for (let i = 0; i < len; i++) html += '<div class="tp-opt" data-v="' + pad(i) + '">' + pad(i) + '</div>';
      el.innerHTML = html;
    }
    fill(hours, 24); fill(mins, 60); fill(secs, 60);

    $$('.tp-opt').forEach(function (o) {
      o.addEventListener('click', function () {
        o.parentElement.querySelectorAll('.tp-opt').forEach(function (x) { x.classList.remove('is-active'); });
        o.classList.add('is-active');
      });
    });
  }

  function pickTime(sel, val) {
    const col = $('.tp-col[data-col="' + sel + '"]');
    col.querySelectorAll('.tp-opt').forEach(function (o) { o.classList.remove('is-active'); });
    const target = col.querySelector('.tp-opt[data-v="' + val + '"]');
    if (target) {
      target.classList.add('is-active');
      col.scrollTop = Math.max(0, target.offsetTop - (col.clientHeight - target.offsetHeight) / 2);
    }
  }

  function selectedTime() {
    function get(sel) {
      const active = $('.tp-col[data-col="' + sel + '"] .tp-opt.is-active');
      return active ? active.dataset.v : '00';
    }
    return get('hour') + ':' + get('min') + ':' + get('sec');
  }

  function openTimeSheet(mode, task) {
    state.sheetTimeMode = mode;
    state.editingId = task ? task.id : null;
    $('#timeSheetTitle').textContent = mode === 'add' ? '添加任务' : '修改任务时间';
    const t = task ? task.time : '08:30:00';
    openSheet('#sheetTime');
    // 面板显示后再定位，避免隐藏状态下无法滚动
    setTimeout(function () {
      pickTime('hour', t.slice(0, 2));
      pickTime('min', t.slice(3, 5));
      pickTime('sec', t.slice(6, 8));
    }, 30);
  }

  function randomWindow(time) {
    const h = time.slice(0, 2), m = time.slice(3, 5);
    const base = parseInt(h, 10) * 60 + parseInt(m, 10);
    const a = base - 10 < 0 ? 0 : base - 10;
    const b = base + 10;
    function fmt(min) {
      return pad(Math.floor(min / 60) % 24) + ':' + pad(min % 60);
    }
    return fmt(a) + ' – ' + fmt(b);
  }

  function commitTimeSheet() {
    const time = selectedTime();
    closeSheets();

    if (state.sheetTimeMode === 'add') {
      const exist = state.tasks.some(function (t) { return t.time === time; });
      if (exist) { toast('任务时间点已存在'); return; }
      state.tasks.push({
        id: state.nextId++, time: time, window: randomWindow(time),
        state: 'pending', actual: '', progress: 0
      });
      toast('已添加任务 ' + time);
    } else {
      const t = state.tasks.filter(function (x) { return x.id === state.editingId; })[0];
      if (t) { t.time = time; t.window = randomWindow(time); }
      toast('任务时间已更新');
    }
    renderTasks();
  }

  /* ---------------------------------------------------------
     工作日
  --------------------------------------------------------- */
  function workdayText() {
    const d = state.workday.slice().sort();
    if (d.join() === '1,2,3,4,5') return '周一至周五';
    if (d.join() === '1,2,3,4,5,6,7') return '每天';
    const names = { 1: '周一', 2: '周二', 3: '周三', 4: '周四', 5: '周五', 6: '周六', 7: '周日' };
    return d.map(function (i) { return names[i]; }).join('、');
  }

  function renderWorkday() {
    const grid = $('#workdayGrid');
    const names = { 1: '周一', 2: '周二', 3: '周三', 4: '周四', 5: '周五', 6: '周六', 7: '周日' };
    let html = '';
    for (let i = 1; i <= 7; i++) {
      html += '<button class="day-chip' + (state.workday.indexOf(i) >= 0 ? ' is-on' : '') + '" data-day="' + i + '">' + names[i] + '</button>';
    }
    grid.innerHTML = html;
    $$('#workdayGrid .day-chip').forEach(function (c) {
      c.addEventListener('click', function () {
        const d = parseInt(c.dataset.day, 10);
        const idx = state.workday.indexOf(d);
        if (idx >= 0) state.workday.splice(idx, 1); else state.workday.push(d);
        c.classList.toggle('is-on');
      });
    });
  }

  /* ---------------------------------------------------------
     事件绑定
  --------------------------------------------------------- */
  function bind() {
    // 导航
    document.addEventListener('click', function (e) {
      const gotoBtn = e.target.closest('[data-goto]');
      if (gotoBtn) { goto(gotoBtn.dataset.goto); return; }

      const backBtn = e.target.closest('[data-back]');
      if (backBtn) { back(); return; }

      const closeBtn = e.target.closest('[data-sheet-close]');
      if (closeBtn) { closeSheets(); return; }
    });

    $('#runBtn').addEventListener('click', toggleRun);
    $('#fabAdd').addEventListener('click', function () { openTimeSheet('add', null); });
    $('#timeSheetOk').addEventListener('click', commitTimeSheet);

    // 任务卡：点击编辑，长按删除
    $('#taskList').addEventListener('click', function (e) {
      const card = e.target.closest('.task');
      if (!card) return;
      if (state.running) { toast('任务进行中，无法修改'); return; }
      const id = parseInt(card.dataset.id, 10);
      const task = state.tasks.filter(function (t) { return t.id === id; })[0];
      openTimeSheet('edit', task);
    });

    let pressTimer = null;
    $('#taskList').addEventListener('pointerdown', function (e) {
      const card = e.target.closest('.task');
      if (!card) return;
      pressTimer = setTimeout(function () {
        const id = parseInt(card.dataset.id, 10);
        openDialog({
          title: '删除任务',
          msg: '确定要删除这个任务吗？',
          onOk: function () {
            state.tasks = state.tasks.filter(function (t) { return t.id !== id; });
            renderTasks();
            toast('任务已删除');
          }
        });
      }, 550);
    });
    ['pointerup', 'pointerleave', 'pointercancel'].forEach(function (ev) {
      $('#taskList').addEventListener(ev, function () { clearTimeout(pressTimer); });
    });

    // 设置页：结果来源
    $('#sourceSegment').addEventListener('click', function (e) {
      const seg = e.target.closest('.seg');
      if (!seg) return;
      $$('#sourceSegment .seg').forEach(function (s) { s.classList.remove('is-active'); });
      seg.classList.add('is-active');
      toast('结果来源已切换为「' + seg.textContent.trim() + '」');
    });

    // 消息渠道页：分段
    $('#channelSegment').addEventListener('click', function (e) {
      const seg = e.target.closest('.seg');
      if (!seg) return;
      $$('#channelSegment .seg').forEach(function (s) { s.classList.remove('is-active'); });
      seg.classList.add('is-active');
      $$('[data-panel]').forEach(function (p) { p.hidden = p.dataset.panel !== seg.dataset.value; });
    });

    // 指令复制
    $('#cmdList').addEventListener('click', function (e) {
      const item = e.target.closest('.cmd-item');
      if (!item) return;
      if (navigator.clipboard) navigator.clipboard.writeText(item.dataset.cmd).catch(function () {});
      toast('指令「' + item.dataset.cmd + '」已复制');
    });

    // FAQ 折叠
    $('#qaList').addEventListener('click', function (e) {
      const head = e.target.closest('.acc-head');
      if (!head) return;
      const item = head.parentElement;
      const body = item.querySelector('.acc-body');
      const open = item.classList.contains('is-open');
      item.classList.toggle('is-open', !open);
      body.style.maxHeight = open ? '0px' : body.scrollHeight + 20 + 'px';
    });

    // 随机时间联动
    $('#randomSwitch').addEventListener('change', function () {
      $('#rangeRow').style.display = this.checked ? '' : 'none';
    });

    // 工作日
    $('#workdayOk').addEventListener('click', function () {
      if (!state.workday.length) { toast('至少保留一天为工作日'); return; }
      closeSheets();
      $$('.row-tap').forEach(function () {});
      const row = $('[data-act="workday"] .row-value');
      if (row) row.textContent = workdayText();
      toast('工作日已更新为 ' + workdayText());
    });

    // 设置页 / 任务配置页的分散动作
    document.addEventListener('click', function (e) {
      const actBtn = e.target.closest('[data-act]');
      if (!actBtn) return;
      const act = actBtn.dataset.act;

      if (act === 'add') { openTimeSheet('add', null); }
      else if (act === 'import') {
        openInputDialog('导入任务', '请将导出的任务粘贴到这里', '', function (v) {
          if (!v) return;
          v.split(/[\n,，\s]+/).forEach(function (item) {
            if (/^\d{2}:\d{2}(:\d{2})?$/.test(item)) {
              const time = item.length === 5 ? item + ':00' : item;
              if (!state.tasks.some(function (t) { return t.time === time; })) {
                state.tasks.push({ id: state.nextId++, time: time, window: randomWindow(time), state: 'pending', actual: '', progress: 0 });
              }
            }
          });
          renderTasks();
          toast('任务导入成功');
        });
      }
      else if (act === 'export') {
        openListSheet('导出到', [
          { label: 'QQ', value: 'qq' }, { label: '微信', value: 'wx' },
          { label: 'TIM', value: 'tim' }, { label: '支付宝', value: 'zfb' },
          { label: '复制到剪贴板', value: 'clip' }
        ], function (v) { toast(v === 'clip' ? '已复制到剪贴板' : '已通过 ' + v.toUpperCase() + ' 分享'); });
      }
      else if (act === 'updateHoliday') { toast('节假日数据已是最新（2026-10-31）'); }
      else if (act === 'openTest') { toast('已唤起目标应用，请检查屏幕'); }
      else if (act === 'captureTest') { toast('截屏成功，正在通过消息渠道发送'); }
      else if (act === 'sendLog') { toast('日志已打包，正在唤起 QQ'); }
      else if (act === 'testWx') {
        toast('测试消息已发送，验证通过');
        $('#wxStatus .pill').className = 'pill pill-ok';
        $('#wxStatus .pill').innerHTML = '<i class="dot"></i>已验证';
        $('#wxStatus .status-text').textContent = '配置保存于刚刚';
      }
      else if (act === 'testMail') { toast('测试邮件发送成功，配置已保存'); }
      else if (act === 'resetTime') {
        const opts = [];
        for (let i = 0; i < 8; i++) opts.push({ label: '每天 ' + i + ' 点', value: String(i) });
        opts.push({ label: '自定义（单位：时）', value: 'custom' });
        openListSheet('重置时间', opts, function (v) {
          if (v === 'custom') {
            openInputDialog('设置重置时间', '直接输入整数时间即可，如：6', '', function (val) {
              if (!/^\d+$/.test(val) || +val > 23) { toast('重置时间必须在 0 到 23 点之间'); return; }
              setResetHour(+val);
            });
          } else setResetHour(+v);
        });
      }
      else if (act === 'timeout') {
        openListSheet('超时时间', [
          { label: '15 秒', value: '15' }, { label: '30 秒', value: '30' },
          { label: '45 秒', value: '45' }, { label: '自定义（单位：秒）', value: 'custom' }
        ], function (v) {
          if (v === 'custom') {
            openInputDialog('设置超时时间', '直接输入整数时间即可，如：60', '', function (val) {
              if (!/^\d+$/.test(val) || +val <= 0) { toast('超时时间必须大于 0 秒'); return; }
              setTimeout2(+val);
            });
          } else setTimeout2(+v);
        });
      }
      else if (act === 'commandKey') {
        openInputDialog('设置打卡口令', '请输入打卡口令，如：打卡', '打卡', function (v) {
          if (!v) { toast('口令不能为空'); return; }
          $('[data-act="commandKey"] .row-value').textContent = v;
          toast('口令已更新为「' + v + '」');
        });
      }
      else if (act === 'workday') { renderWorkday(); openSheet('#sheetWorkday'); }
      else if (act === 'minuteRange') {
        openInputDialog('设置随机时间范围', '请输入整数，如：30', '30', function (v) {
          if (!/^\d+$/.test(v)) { toast('直接输入整数时间即可'); return; }
          $('[data-act="minuteRange"] .row-value').textContent = v + ' 分钟';
          toast('随机时间范围已更新');
        });
      }
    });

    // 伪灭屏
    $('#maskToggle').addEventListener('click', function () {
      state.mask = !state.mask;
      $('#maskOverlay').hidden = !state.mask;
    });
    $('#maskOverlay').addEventListener('click', function () {
      state.mask = false;
      $('#maskOverlay').hidden = true;
    });

    // 进入设置前先看使用须知（对齐原 App 行为）
    $('#settingsBtn').addEventListener('click', function () {
      openDialog({
        title: '使用须知',
        msg: '本软件完全免费！仅供内部使用！严禁商用或者用作其他非法用途！\n近期发现有人在闲鱼私自倒卖本软件，请勿购买！如有购买，请联系卖家退款！',
        okText: '知道了',
        hideCancel: true,
        onOk: function () { goto('settings'); }
      });
    });

    // 总览模式：6 个页面并排，一屏看全
    $('#overviewBtn').addEventListener('click', function () {
      const on = $('.stage').classList.toggle('is-overview');
      this.classList.toggle('is-on', on);
      this.textContent = on ? '返回单页' : '总览';
    });

    // 遮罩点击关闭
    $$('.sheet-mask').forEach(function (m) {
      m.addEventListener('click', function (e) { if (e.target === m) closeSheets(); });
    });
    $('#dialogMask').addEventListener('click', function (e) {
      if (e.target === $('#dialogMask')) $('#dialogMask').hidden = true;
    });

    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') { closeSheets(); $('#dialogMask').hidden = true; }
    });

    // 浏览器前进 / 后退
    window.addEventListener('hashchange', function () {
      const h = location.hash.slice(1);
      if (h && $('.screen[data-screen="' + h + '"]') && h !== state.screen) goto(h, true);
    });

    // 快捷键：1~6 直接跳页，← 返回，G 总览
    window.addEventListener('keydown', function (e) {
      const keys = { '1': 'main', '2': 'settings', '3': 'taskconfig', '4': 'channel', '5': 'command', '6': 'qa' };
      if (keys[e.key]) goto(keys[e.key]);
      else if (e.key === 'ArrowLeft' && state.screen !== 'main') back();
      else if (e.key === 'g' || e.key === 'G') $('#overviewBtn').click();
    });

    // 出错时给出可见提示，避免"点了没反应"无从排查
    window.addEventListener('error', function (e) {
      toast('脚本异常：' + (e.message || '未知错误'));
    });
  }

  function setResetHour(h) {
    $('[data-act="resetTime"] .row-value').textContent = '每天 ' + h + ' 点';
    toast('重置时间已更新为每天 ' + h + ' 点');
  }

  function setTimeout2(s) {
    $('[data-act="timeout"] .row-value').textContent = s + 's';
    toast('超时时间已更新为 ' + s + ' 秒');
  }

  /* ---------------------------------------------------------
     初始化
  --------------------------------------------------------- */
  function init() {
    buildTimePicker();
    bind();
    renderTasks();
    renderRunState();
    renderWorkday();
    tickClock();
    tickCountdown();
    setInterval(tickClock, 1000);
    setInterval(tickCountdown, 1000);
    simulate();

    // 支持 #settings 之类的直达链接，便于评审时分享具体页面
    const hash = location.hash.slice(1);
    if (hash && $('.screen[data-screen="' + hash + '"]')) goto(hash, true);
    if (hash === 'sheet-time') openTimeSheet('add', null);
    if (hash === 'sheet-workday') { renderWorkday(); openSheet('#sheetWorkday'); }
    if (hash === 'dialog') openDialog({ title: '使用须知', msg: '本软件完全免费！仅供内部使用！\n严禁商用或者用作其他非法用途！', okText: '知道了', hideCancel: true });
  }

  document.addEventListener('DOMContentLoaded', init);
})();
