(function () {
  'use strict';
  const targets = {quick: 'quick-input', chat: 'chat-input'};
  const escape = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const microphone = '<svg aria-hidden="true" viewBox="0 0 24 24"><path d="M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0ZM5 11v1a7 7 0 0 0 14 0v-1M12 19v3m-4 0h8"/></svg>';
  const notices = {};
  let active = null, nextId = Date.now() % 100000000000;
  const inputFor = target => document.getElementById(targets[target]);
  const bridge = () => window.Device && typeof window.Device.speechStart === 'function';
  const say = text => { if (typeof window.toast === 'function') window.toast(text); };
  function statusHTML(target) {
    const current = active?.target === target ? active : null;
    const notice = notices[target];
    return `<div id="speech-status-${target}" class="speech-status" role="status" aria-live="polite" ${current || notice ? '' : 'hidden'}>${statusContent(target, current, notice)}</div>`;
  }
  function statusContent(target, current, notice) {
    if (current) {
      const canStop = current.phase === 'listening';
      return `<div class="speech-caption"><span class="speech-dot"></span>${escape(current.message)}</div>${current.partial ? `<p class="speech-preview">${escape(current.partial)}</p>` : ''}<div class="speech-controls">${canStop ? '<button type="button" class="text-button" data-speech-action="stop">说完了</button>' : ''}<button type="button" class="text-button" data-speech-action="cancel">取消</button></div><small>由手机系统识别；系统服务可能联网。文字先回填，检查后再发送。</small>`;
    }
    return notice ? `<p>${escape(notice.text)}</p>${notice.permission && typeof window.Device?.speechSettings === 'function' ? '<button type="button" class="text-button" data-speech-action="settings">打开麦克风权限设置</button>' : ''}` : '';
  }
  function update(target) {
    const status = document.getElementById(`speech-status-${target}`);
    const current = active?.target === target ? active : null;
    if (status) { status.hidden = !current && !notices[target]; status.innerHTML = statusContent(target, current, notices[target]); }
    document.querySelectorAll(`[data-voice-target="${target}"]`).forEach(button => {
      button.classList.toggle('recording', !!current);
      button.setAttribute('aria-pressed', current ? 'true' : 'false');
      button.setAttribute('aria-label', current ? '结束语音输入' : target === 'quick' ? '首页语音输入' : '助手语音输入');
    });
  }
  function button(target) {
    return `<button type="button" class="voice-button" data-act="voice" data-voice-target="${target}" aria-label="${target === 'quick' ? '首页语音输入' : '助手语音输入'}" aria-pressed="false" title="语音输入">${microphone}</button>`;
  }
  function clearCurrent() {
    const old = active; active = null;
    if (old) { clearTimeout(old.timer); update(old.target); }
    return old;
  }
  function cancel(silent = false) {
    const old = clearCurrent();
    if (!old) return;
    try { window.Device?.speechCancel(old.id); } catch (_) { }
    if (!silent) say('已取消语音输入，原文字保留');
  }
  function stop() {
    if (!active) return;
    if (active.phase !== 'listening') { cancel(); return; }
    try { const id=active.id; active.phase = 'processing'; active.message = '正在整理识别文字…'; update(active.target); window.Device.speechStop(id); }
    catch (_) { cancel(true); say('语音服务未响应，请重试'); }
  }
  function start(target) {
    if (!Object.prototype.hasOwnProperty.call(targets, target)) return;
    if (active) { if (active.target === target) stop(); else say('请先结束当前语音输入'); return; }
    const input = inputFor(target);
    if (!input) return;
    if (!bridge()) { notices[target] = {text: '请在 Android 安装版使用语音输入。浏览器预览不申请录音权限。'}; update(target); say(notices[target].text); return; }
    delete notices[target];
    const base = input.value;
    active = {id: String(++nextId), target, base, start: input.selectionStart ?? base.length, end: input.selectionEnd ?? base.length, phase: 'starting', message: '正在启动语音输入…', partial: ''};
    const id = active.id;
    active.timer = setTimeout(() => { if (active?.id === id) { cancel(true); notices[target] = {text:'语音识别超时，请分段重试'}; update(target); say(notices[target].text); } }, 70000);
    update(target);
    try { window.Device.speechStart(id); }
    catch (_) { clearCurrent(); notices[target] = {text:'无法启动语音输入，请检查系统语音服务'}; update(target); say(notices[target].text); }
  }
  window.onSpeechEvent = function (event) {
    if (!active || !event || event.id !== active.id) return;
    const job = active, target = job.target;
    if (['starting','permission','listening','external','processing'].includes(event.kind)) {
      job.phase = event.kind;
      job.message = String(event.text || '正在识别…');
      update(target); return;
    }
    if (event.kind === 'partial') { job.partial = String(event.text || ''); update(target); return; }
    if (!['result','error','cancelled'].includes(event.kind)) return;
    clearCurrent();
    if (event.kind === 'cancelled') { say('已取消语音输入，原文字保留'); return; }
    if (event.kind === 'error') { notices[target] = {text: String(event.text || '语音识别失败，请重试'), permission: event.code === 'permission'}; update(target); say(notices[target].text); return; }
    const input = inputFor(target), text = typeof event.text === 'string' ? event.text.trim() : '';
    if (!input || !text) { say('没有识别到文字，请重试'); return; }
    const unchanged = input.value === job.base;
    const before = unchanged ? job.base.slice(0, job.start) : input.value + (input.value ? ' ' : '');
    const after = unchanged ? job.base.slice(job.end) : '';
    const next = before + text + after;
    if (input.maxLength > 0 && next.length > input.maxLength) { notices[target] = {text:'识别文字超过输入长度上限，原内容已保留，请分段输入'}; update(target); say(notices[target].text); return; }
    input.value = next;
    input.dispatchEvent(new Event('input', {bubbles:true}));
    input.focus({preventScroll:true});
    input.setSelectionRange(before.length + text.length, before.length + text.length);
    say(target === 'quick' ? '文字已填入，请检查后点箭头识别账单' : '文字已填入，请检查后再发送');
  };
  document.addEventListener('click', event => {
    const element = event.target.closest('[data-speech-action]');
    if (!element) return;
    if (element.dataset.speechAction === 'stop') stop();
    if (element.dataset.speechAction === 'cancel') cancel();
    if (element.dataset.speechAction === 'settings') { try { window.Device?.speechSettings(); } catch (_) { say('请在系统应用设置中开启麦克风权限'); } }
  });
  window.addEventListener('pagehide', () => cancel(true));
  window.VoiceInput = {button, statusHTML, start, stop, cancel, beforeRender: () => cancel(true), isActive: () => !!active};
})();
