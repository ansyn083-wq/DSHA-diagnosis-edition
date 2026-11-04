(function () {
  'use strict';
  if (window.top !== window || window.__dshaChatDraft) return;
  window.__dshaChatDraft = {
    install(ctx) {
      let alive = true;
      const seen = new Set(), cleanups = new Set();
      function active() {
        const rows = Object.values(ctx.sessions.list.getSnapshot().byId);
        const candidates = rows.filter(row => (row.retainedBy?.mainView || 0) > 0);
        if (candidates.length !== 1) return null;
        const row = candidates[0], binding = ctx.sessions.binding(row.id);
        if (!binding) return null;
        const shell = ctx.conversation.input.for(binding.ctx);
        return shell?.state && shell.actions?.setDraft ? {id:row.id,shell} : null;
      }
      function receive(event) {
        const value = event.detail;
        if (!alive || !value || typeof value.text !== 'string' || !value.text || value.text.length > 65536
            || typeof value.requestToken !== 'string' || value.requestToken.length > 80
            || !/^[a-f0-9]{32}$/.test(value.nonce)
            || value.nonce !== document.documentElement.getAttribute('data-dsha-native-nonce')
            || seen.has(value.requestToken)) return;
        seen.add(value.requestToken);
        if (seen.size > 64) seen.delete(seen.values().next().value);
        const reply = status => {
          if (alive) document.dispatchEvent(new CustomEvent('dsha-chat-draft-result',{
            detail:{nonce:value.nonce,requestToken:value.requestToken,status}
          }));
        };
        let target;
        try {target = active();} catch (_) {reply('unavailable');return;}
        if (!target) {reply('unavailable');return;}
        const {shell} = target, initial = shell.state.getSnapshot();
        if (typeof initial.draft !== 'string' || initial.draft || initial.phase !== 'plain'
            || !Array.isArray(initial.attachmentIds) || initial.attachmentIds.length
            || !Array.isArray(initial.occurrences) || initial.occurrences.length) {
          reply('occupied');return;
        }
        let done = false, off, timer;
        const stop = () => {done = true;off?.();clearTimeout(timer);cleanups.delete(stop);};
        const finish = status => {if(done)return;stop();reply(status);};
        const check = () => {
          try {
            const now = active();
            if (!alive || now?.id !== target.id || now.shell !== shell
                || value.nonce !== document.documentElement.getAttribute('data-dsha-native-nonce')) {
              finish('unavailable');return;
            }
            if (shell.state.getSnapshot().draft === value.text) finish('applied');
          } catch (_) {finish('unavailable');}
        };
        cleanups.add(stop);
        off = shell.state.subscribe(check);
        timer = setTimeout(()=>finish('unavailable'),2000);
        // This is the locked host's public input action. No submit/DOM emulation.
        try {shell.actions.setDraft(value.text);queueMicrotask(check);}
        catch (_) {finish('unavailable');}
      }
      document.addEventListener('dsha-chat-import-draft',receive);
      return () => {
        alive = false;document.removeEventListener('dsha-chat-import-draft',receive);
        for (const stop of [...cleanups]) stop();
      };
    }
  };
  document.dispatchEvent(new CustomEvent('dsha-chat-draft-ready'));
})();
