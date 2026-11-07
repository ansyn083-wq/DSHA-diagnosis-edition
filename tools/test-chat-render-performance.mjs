#!/usr/bin/env node
// 当前 DSH ChatFlow、Markdown 和内置移动样式，使用合成长会话。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {browserFixture} from './rc1-browser-fixture.mjs';
import {testRuntime} from './test-runtime-fixture.mjs';

const fixture = await browserFixture(testRuntime('raw'));
const errors = [];
fixture.page.on('pageerror', error => errors.push(error.message));
fixture.page.on('console', message => {
  if (message.type() === 'error' && !message.location().url.endsWith('/favicon.ico'))
    errors.push(message.text());
});

try {
  await fixture.load('dsh-client-ui-chat', [], 'module.exports.audit = {ChatFlow, markdownLabels};');
  const source = fs.readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js', 'utf8');
  // 加载 APK 实际部署的布局模块，隔离与消息布局无关的插件副作用。
  await fixture.page.evaluate(() => {
    window.__ModuleLoader__ = {load({factory}) { factory(() => ({})); }};
  });
  await fixture.page.addScriptTag({content: source.replace('var __cache = {};',
    'window.mobileRequire = __localRequire; var __cache = {};')});
  const layout = await fixture.page.evaluate(() => Object.values(mobileRequire('./styles/layout.css.js'))
    .filter(value => typeof value === 'string').join('\n'));
  assert.ok(layout);
  await fixture.page.addStyleTag({content: layout});

  const results = await fixture.page.evaluate(async () => {
    const React = auditModules.react.default || auditModules.react;
    const ReactDOM = auditModules['react-dom'].default || auditModules['react-dom'];
    const primitives = auditModules['@deepseek-ai/dsh-client-ui-primitives'];
    const {ChatFlow, markdownLabels} = auditExports['@deepseek-ai/dsh-client-ui-chat'].audit;
    const frame = document.createElement('div');
    frame.dataset.mobileNav = 'frame';
    frame.innerHTML = '<div class="Conversation_scrollBody" style="height:650px!important;overflow-y:auto!important">'
      + '<div class="EvIC1a_scroll"><div class="EvIC1a_column" data-chat-flow>'
      + '<div data-slot="conversation.chat.flow"></div></div></div></div>';
    document.body.append(frame);
    const viewport = frame.firstElementChild;
    const column = frame.querySelector('[data-chat-flow]');
    const host = column.firstElementChild;
    const afterPaint = () => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    const nodes = new Map();
    const entries = [];
    const noop = () => {};
    const t = key => key;
    const labels = markdownLabels(t);
    const props = {
      pendingInputs: [], cwd: '', t,
      useSession: select => select({running: false}),
      useChat: select => select({nodes, navigation: {items: () => []}}),
      useChatNode: (key, select = value => value) => select(nodes.get(key)),
      useChatNodeBottom: () => false, useChatNodeProcess: () => undefined,
      useChatGroup: () => undefined, useGroupAction: () => React.useRef(null),
      usePresentation: select => select({foldCompletedTurns: false}),
      useStore: select => select({}), actions: {setTurnProcessOpen: noop},
      openFile: noop, inspectCall: noop, forkAt: noop, loadImage: noop, fileMentions: noop,
      renderSlot: (_slot, owner) => React.createElement(primitives.MarkdownText,
        {text: owner.node.data.text, streaming: false, labels})
    };
    const results = [];
    for (const count of [100, 1000]) {
      for (let index = entries.length; index < count; index++) {
        const key = `msg-${index}`;
        const text = `### Message ${index}\n\n- A **message** with [a link](https://example.test) and inline code.`
          + `\n- Another bullet.\n\n\`\`\`javascript\nconst n = ${index};\n\`\`\`\n\n`
          + 'Test payload for DSHA history. '.repeat(14);
        entries.push({kind: 'node', key});
        nodes.set(key, {key, kind: index % 4 === 0 ? 'user' : 'assistant-step', anchorSeq: index + 1,
          data: {text}, location: {kind: 'turn', turn: {turn: index, status: 'closed', data: {}}}});
      }
      viewport.style.setProperty('width', '360px', 'important');
      const app = ReactDOM.createRoot(host);
      const started = performance.now();
      app.render(React.createElement(ChatFlow, {...props, entries: entries.slice()}));
      await afterPaint();
      const mountMs = Math.round(performance.now() - started);
      const history = host.querySelector('[data-chat-flow-key="msg-2"]');
      const tail = host.lastElementChild;
      viewport.scrollTop = viewport.scrollHeight;
      await afterPaint();
      const layoutMs = [];
      const widths = [];
      for (const width of [335, 350, 335, 350, 335]) {
        const before = performance.now();
        viewport.style.setProperty('width', `${width}px`, 'important');
        widths.push(Math.round(viewport.getBoundingClientRect().width));
        layoutMs.push(Math.round(performance.now() - before));
        await afterPaint();
      }
      const historyVisibility = getComputedStyle(history).contentVisibility;
      history.dataset.chatMotion = '';
      const motionVisibility = getComputedStyle(history).contentVisibility;
      delete history.dataset.chatMotion;
      // 让较早的代码块滚进视口，再检查文本和交互按钮仍可访问。
      const copyButton = history.querySelector('button');
      copyButton.scrollIntoView({block: 'center'});
      await afterPaint();
      copyButton.focus();
      results.push({count, mountMs, layoutMs, widths,
        domRows: host.querySelectorAll('[data-chat-flow-key]').length,
        columnVisibility: getComputedStyle(column).contentVisibility,
        historyVisibility, motionVisibility, tailVisibility: getComputedStyle(tail).contentVisibility,
        historyAccessible: history.textContent.includes('Message 2') && document.activeElement === copyButton});
      app.unmount();
    }
    return results;
  });
  assert.deepEqual(errors, []);
  console.log(JSON.stringify(results, null, 2));
  const budget = Number(process.env.DSHA_CHAT_RENDER_BUDGET_MS || 3000);
  assert.ok(Number.isFinite(budget) && budget > 0);
  assert.ok(results[0].mountMs < budget, `100-message mount exceeded ${budget}ms`);
  for (const sample of results) {
    assert.deepEqual(sample.widths, [335, 350, 335, 350, 335]);
    assert.equal(sample.domRows, sample.count, 'all history rows remain mounted');
    assert.equal(sample.historyVisibility, 'auto', 'offscreen chat history needs row-level containment');
    assert.equal(sample.columnVisibility, 'visible', 'the entire transcript cannot share one containment boundary');
    assert.equal(sample.tailVisibility, 'visible', 'keep the live tail outside history containment');
    assert.equal(sample.motionVisibility, 'visible', 'let the host measure a folding row');
    assert.equal(sample.historyAccessible, true, 'an old message remains readable and interactive');
  }
} finally { await fixture.close(); }
