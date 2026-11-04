(function () {
  'use strict';
  if (window.top !== window || window.__dshaChatUi) return;
  let binding, observer, frame = 0, draft = '', pending, banner, button, label;
  const text = (zh,en) => /^zh/i.test(window.__DSHA_LANGUAGE__ || document.documentElement.lang || navigator.language) ? zh : en;
  function valid(next) {
    return next && /^[a-f0-9]{32}$/.test(next.nonce) && Number.isSafeInteger(next.generation)
      && next.generation > 0 && typeof next.instance === 'string' && next.instance.length > 0;
  }
  function schedule() {
    if (!binding || frame) return;
    frame = requestAnimationFrame(() => {frame = 0; mount();});
  }
  function mount() {
    if (!binding) return;
    const header = document.querySelector('[data-phase] header');
    if (header && !header.querySelector('[data-dsha-chat-settings]') && window.DshaChat?.postMessage) {
      const settings = document.createElement('button');
      settings.type = 'button'; settings.setAttribute('data-dsha-chat-settings','');
      settings.setAttribute('aria-label',text('应用设置','App settings'));
      settings.title = text('应用设置','App settings');
      settings.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="12" cy="12" r="3"/><path d="m9 3-1 3-3 1-2 3 2 2-1 3 3 2 3-1 2 3 3-1 1-3 3-1 2-3-2-2 1-3-3-2-3 1-2-3Z"/></svg>';
      settings.onclick = () => {if (binding) window.DshaChat.postMessage(JSON.stringify({...binding,action:'settings'}));};
      const opener = header.querySelector('[data-mobile-nav="files"]');
      (opener?.parentElement || header).appendChild(settings);
    }
    const composer = document.querySelector('[data-composer-card]');
    if (!composer || !draft) return;
    if (!banner) {
      banner = document.createElement('div'); banner.setAttribute('data-dsha-chat-draft','');
      label = document.createElement('span'); label.textContent = text('首页有一份草稿，尚未发送。','You have a saved home draft. It has not been sent.');
      button = document.createElement('button'); button.type = 'button';
      button.textContent = text('带入草稿','Use saved draft');
      button.onclick = () => {
        if (!binding || pending || !draft) return;
        const token = crypto.randomUUID(); pending = {...binding,requestToken:token,text:draft};
        button.disabled = true;
        document.dispatchEvent(new CustomEvent('dsha-chat-import-draft',{detail:pending}));
        const ticket = pending;
        setTimeout(() => {
          if (pending !== ticket) return;
          pending = undefined; button.disabled = false;
          label.textContent = text('草稿仍保留在首页，可稍后再试。','Your draft remains on the home screen. Try again later.');
        },2500);
      };
      banner.append(label,button);
    }
    if (banner.parentElement !== composer) composer.prepend(banner);
  }
  document.addEventListener('dsha-chat-draft-result',event => {
    const value = event.detail;
    if (!pending || !value || value.nonce !== pending.nonce || value.requestToken !== pending.requestToken) return;
    pending = undefined;
    if (value.status === 'applied') {draft = ''; banner?.remove(); banner = undefined;}
    else {
      button.disabled = false;
      label.textContent = text('当前会话已有内容或暂不可写；首页草稿已保留。','This chat already has a draft or is unavailable. Your saved draft is retained.');
    }
  });
  function cancel() {
    binding = undefined; pending = undefined;
    observer?.disconnect(); if(frame) cancelAnimationFrame(frame); frame = 0;
    banner?.remove(); banner = undefined;
    document.querySelectorAll('[data-dsha-chat-settings]').forEach(el=>el.remove());
    document.documentElement.removeAttribute('data-dsha-chat-ui');
  }
  window.__dshaChatUi = {
    install(next) {
      if (!valid(next)) return;
      if (binding && binding.nonce !== next.nonce) cancel();
      binding = {nonce:next.nonce,generation:next.generation,instance:next.instance};
      document.documentElement.setAttribute('data-dsha-chat-ui','');
      observer?.disconnect();
      observer = new MutationObserver(schedule);
      observer.observe(document.documentElement,{childList:true,subtree:true}); schedule();
    },
    offerDraft(value) {
      if (typeof value !== 'string' || value.length > 65536 || !value) return;
      draft = value; schedule();
    },
    cancel
  };
})();
