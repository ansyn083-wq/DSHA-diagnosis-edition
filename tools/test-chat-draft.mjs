// Actual import coordinator against the published SessionInputShell interface.
// Boundary state fixture; this is not an Android, model or full Lexical integration test.
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
class DetailEvent extends Event {constructor(type,options={}){super(type);this.detail=options.detail;}}
const document = new EventTarget();
document.documentElement={getAttribute:()=> 'a'.repeat(32)};
let snapshot={draft:'',phase:'plain',attachmentIds:[],occurrences:[]};
let writes=0, submits=0, current='one';
const subscribers=new Set();
const shell={state:{getSnapshot:()=>snapshot,subscribe:fn=>{subscribers.add(fn);return()=>subscribers.delete(fn);}},
  actions:{setDraft:text=>{writes++;snapshot={...snapshot,draft:text};for(const fn of subscribers)fn();},
    submit:()=>submits++}};
const ctx={sessions:{list:{getSnapshot:()=>({byId:{[current]:{id:current,retainedBy:{mainView:1}}}})},binding:id=>id==='one'?{}:null},
  conversation:{input:{for:()=>shell}}};
const window={};window.top=window;
vm.runInNewContext(fs.readFileSync('app/src/main/assets/web-integration/chat-draft.js','utf8'),
  {window,document,CustomEvent:DetailEvent,setTimeout,clearTimeout,queueMicrotask});
assert.ok(window.__dshaChatDraft,'coordinator installed');
const off=window.__dshaChatDraft.install(ctx);
const results=[];document.addEventListener('dsha-chat-draft-result',e=>results.push(e.detail.status));
let serial=0;
const send=(text,nonce='a'.repeat(32),token=String(++serial))=>document.dispatchEvent(new DetailEvent('dsha-chat-import-draft',
  {detail:{text,nonce,requestToken:token,generation:1,instance:'fixture'}}));
send('Hello');await new Promise(r=>setTimeout(r,10));
assert.equal(snapshot.draft,'Hello');assert.equal(results.at(-1),'applied');assert.equal(submits,0);
send('Overwrite');assert.equal(snapshot.draft,'Hello');assert.equal(results.at(-1),'occupied');
snapshot={...snapshot,draft:'',attachmentIds:['image']};send('No');assert.equal(snapshot.draft,'');
snapshot={...snapshot,attachmentIds:[],phase:'submitting'};send('No');assert.equal(snapshot.draft,'');
snapshot={...snapshot,phase:'plain'};send('Unsafe','b'.repeat(32));assert.equal(snapshot.draft,'');
send('Once','a'.repeat(32),'same');await new Promise(r=>setTimeout(r,10));
snapshot={...snapshot,draft:''};send('Again','a'.repeat(32),'same');assert.equal(snapshot.draft,'');
current='missing';send('Missing');assert.equal(snapshot.draft,'');
off();current='one';send('Disposed');assert.equal(snapshot.draft,'');
assert.equal(writes,2);assert.equal(submits,0);
console.log('PASS draft bridge: applied readback, occupied/attachment/inflight/nonce/session guards, no replay, disposal, no submit');
