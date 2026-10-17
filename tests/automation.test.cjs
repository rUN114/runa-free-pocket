const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const base = path.join(__dirname, '../app/src/main/assets');
function element(text, overrides = {}) {
  return {innerText:text,textContent:text,disabled:false,getBoundingClientRect:()=>({width:100,height:40}),
    querySelector:()=>null,querySelectorAll:()=>[],click(){this.clicks=(this.clicks||0)+1;},...overrides};
}
async function run({mode='check',category='pc',ctaText='Get',paymentText=null,library=[],fetchError=false} = {}) {
  const messages = [], ticks = [];
  const cta = element(ctaText);
  const h1 = element('Test Game');
  const main = element('', {querySelector: selector => selector==='h1' ? h1 : cta});
  h1.parentElement = main;
  const submit = element('Place Order');
  const payment = paymentText === null ? null : element(paymentText, {querySelectorAll:selector=>selector.startsWith('button') ? [submit] : []});
  const doc = {body:element(''),querySelector:selector=>selector==='main'?main:null,
    querySelectorAll:selector=>selector.includes('dialog')&&payment?[payment]:[]};
  const url = category==='fab' ? 'https://www.fab.com/listings/12345678-abcd-abcd-abcd-123456789012?lang=en' : 'https://store.epicgames.com/en-US/p/test-game';
  const context = {console,URL,AbortController,location:new URL(url),document:doc,getComputedStyle:()=>({visibility:'visible',display:'block'}),
    setInterval:fn=>ticks.push(fn),setTimeout:fn=>ticks.push(fn),addEventListener:()=>{},
    clearTimeout:()=>{},
    PocketStore:{postMessage:data=>messages.push(JSON.parse(data))},
    fetch:async request=>({ok:!fetchError,status:fetchError?401:200,json:async()=>request.includes('/users/me')?{uid:'user-a'}:{results:library,cursors:{next:null}}})};
  context.window = context; context.top = context;
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(base,'domain.js'),'utf8')+'\n'+fs.readFileSync(path.join(base,'automation.js'),'utf8'), context);
  context.runPocketTask({nonce:'test',mode,category,country:'CN',item:{id:'12345678-abcd-abcd-abcd-123456789012',url}});
  await ticks[0]();
  return {messages,cta,submit,tick:ticks[0]};
}
test('Epic: main CTA confirms owned without touching checkout', async () => {
  const result = await run({ctaText:'In Library'});
  assert.equal(result.messages[0].status,'owned'); assert.equal(result.cta.clicks,undefined);
});
test('Epic: a Get CTA means unowned in check mode, without clicking', async () => {
  const result = await run(); assert.equal(result.messages[0].status,'unowned'); assert.equal(result.cta.clicks,undefined);
});
test('Epic: paid main CTA is never clicked', async () => {
  const result = await run({mode:'claim',ctaText:'Buy Now',paymentText:'Total $0.00'});
  assert.equal(result.messages[0].status,'expired'); assert.equal(result.cta.clicks,undefined);
  assert.equal(result.submit.clicks,undefined);
});
test('checkout: missing total never places an order', async () => {
  const result = await run({mode:'claim',paymentText:'Free game'});
  assert.equal(result.submit.clicks,undefined); assert.ok(result.messages.some(m=>m.type==='waiting'));
});
test('checkout: nonzero total stops automation', async () => {
  const result = await run({mode:'claim',paymentText:'Total $5.00'});
  assert.equal(result.submit.clicks,undefined); assert.ok(result.messages.some(m=>m.type==='blocked'));
});
test('checkout: a zero total submits once, and never marks owned from the click', async () => {
  const result = await run({mode:'claim',paymentText:'Total $0.00'}); await result.tick();
  assert.equal(result.submit.clicks,1); assert.ok(result.messages.some(m=>m.type==='ordered'));
  assert.ok(!result.messages.some(m=>m.status==='owned'));
});
test('Fab: acquired listing ID confirms ownership', async () => {
  const result = await run({category:'fab',library:[{listing:{uid:'12345678-abcd-abcd-abcd-123456789012'}}]});
  assert.equal(result.messages[0].status,'owned');
});
test('Fab: an empty authenticated library means unowned', async () => {
  const result = await run({category:'fab'}); assert.equal(result.messages[0].status,'unowned');
});
test('Fab: HTTP 401 is a failure, never an empty library or an unowned result', async () => {
  const result = await run({category:'fab',fetchError:true});
  assert.equal(result.messages[0].type,'error'); assert.match(result.messages[0].message,/401/);
});
