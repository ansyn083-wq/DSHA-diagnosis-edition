// Production presentation running in Chromium with a controlled host DOM and service boundary.
// No Android device or model call is implied.
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const require = createRequire(import.meta.url);
const {chromium} = require(process.env.DSHA_PLAYWRIGHT || 'playwright');
const browser = await chromium.launch({headless:true,
  ...(process.env.DSHA_CHROME ? {executablePath:process.env.DSHA_CHROME,args:['--no-sandbox']} : {})});
const page = await browser.newPage({viewport:{width:360,height:800},hasTouch:true});
try {
  await page.setContent(`<html><body><div data-mobile-nav="frame" data-sidebar-collapsed><aside data-pane="sidebar">Saved chats</aside><main data-phase="idle"><header><button data-mobile-nav="toggle">Chats</button><span>DSHA</span></header><section data-chat-message-list><div class="x_userStack"><div class="x_bubble">Hello</div></div><div class="x_assistant">Reply <button>Copy</button></div><div role="dialog">Tool approval</div></section><div data-composer-card><div contenteditable="true" data-composer-input role="textbox"></div><button>Attach</button><button>Send</button></div></main><div data-shell-overlay></div></div></body></html>`);
  await page.addStyleTag({content:fs.readFileSync('app/src/main/assets/web-integration/chat-ui.css','utf8')});
  await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/web-integration/compat.js','utf8')});
  await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/web-integration/chat-ui.js','utf8')});
  await page.evaluate(() => {
    window.receipts=[];
    window.DshaChat={postMessage:text=>receipts.push(JSON.parse(text))};
    __dshaChatUi.install({nonce:'a'.repeat(32),generation:1,instance:'fixture'});
    __dshaChatUi.install({nonce:'a'.repeat(32),generation:1,instance:'fixture'});
  });
  await page.getByRole('button',{name:'App settings'}).waitFor();
  assert.equal(await page.locator('[data-dsha-chat-settings]').count(),1);
  await page.getByRole('button',{name:'App settings'}).click();
  assert.equal(await page.evaluate(()=>receipts[0].action),'settings');
  // Missing and resident draft must never be silently replaced or submitted.
  await page.evaluate(() => __dshaChatUi.offerDraft('Native draft'));
  assert.equal(await page.locator('[data-composer-input]').innerText(),'');
  let submits=0;page.on('request',req=>{if(req.method()==='POST')submits++;});
  await page.evaluate(() => {
    window.draftCalls=0;
    document.addEventListener('dsha-chat-import-draft',event=>{
      draftCalls++; const input=document.querySelector('[data-composer-input]');
      const result=input.textContent?'occupied':'applied';
      if(result==='applied')input.textContent=event.detail.text;
      document.dispatchEvent(new CustomEvent('dsha-chat-draft-result',{detail:{...event.detail,status:result}}));
    });
  });
  await page.getByRole('button',{name:'Use saved draft'}).click();
  await page.waitForFunction(()=>document.querySelector('[data-composer-input]').textContent==='Native draft');
  assert.equal(submits,0);
  await page.evaluate(()=>__dshaChatUi.offerDraft('Another draft'));
  await page.getByRole('button',{name:'Use saved draft'}).click();
  assert.equal(await page.locator('[data-composer-input]').innerText(),'Native draft');
  assert.ok(await page.locator('[data-dsha-chat-draft]').isVisible());
  for(const width of [360,412,800,840,1024]) {
    await page.setViewportSize({width,height:800});
    const bounds=await page.locator('[data-composer-card]').boundingBox();
    assert.ok(bounds.x>=0 && bounds.x+bounds.width<=width+1,`composer stays inside ${width}px`);
    assert.equal(await page.getByRole('dialog').isVisible(),true,'tool approval remains visible');
  }
  await page.evaluate(()=>{document.documentElement.setAttribute('data-ds-dark-theme','true');});
  assert.equal(await page.locator('body').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(33, 33, 33)');
  await page.evaluate(()=>{document.documentElement.removeAttribute('data-ds-dark-theme');document.body.setAttribute('data-ds-dark-theme','');});
  assert.equal(await page.locator('body').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(33, 33, 33)');
  await page.evaluate(()=>{__dshaChatUi.cancel();__dshaChatUi.install({nonce:'bad',generation:0,instance:''});});
  assert.equal(await page.locator('[data-dsha-chat-settings]').count(),0);
  console.log('PASS chat presentation: settings, idempotence, explicit draft, collision, no submit, five widths, dark theme, invalid binding');
} finally {await browser.close();}
