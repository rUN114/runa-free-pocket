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
async function run({mode='check',category='pc',ctaText='Get',paymentText=null,library=[],fetchError=false,
  libraryPages=null,failLibraryPage=-1,urlOverride=null,authBody='',itemOverrides={},separatePurchaseCard=false,ambiguousCards=false,
  storeCheckout=false,submitText='Place Order',fabProduct=false} = {}) {
  const messages = [], ticks = [], requests = [];
  let libraryPage = 0;
  const cta = element(ctaText);
  const h1 = element('Test Game');
  const main = element('', {querySelector: selector => selector==='h1' ? h1 : cta});
  h1.parentElement = main;
  if (separatePurchaseCard) {
    h1.parentElement = element('', {parentElement:main});
    const wishlist = element('Wishlist');
    const card = element('Base Game\nFree', {querySelectorAll:selector=>selector.startsWith('button')?[cta,wishlist]:[]});
    const another = element('Base Game\nFree', {querySelectorAll:selector=>selector.startsWith('button')?[element('Get'),element('Wishlist')]:[]});
    main.querySelector = selector => selector==='h1' ? h1 : null;
    main.querySelectorAll = selector => selector==='section' ? (ambiguousCards ? [card,another] : [card]) : [];
  }
  let libraryVisible = false;
  if (fabProduct) {
    main.innerText = 'PERSONAL Free -100%';
    cta.innerText = 'Buy now';
    const link = element('View in My Library', {href:'https://www.fab.com/library'});
    main.querySelectorAll = selector => {
      if (selector.startsWith('button')) return [cta];
      if (selector==='a[href]' && libraryVisible) return [link];
      return [];
    };
  }
  const submit = element(submitText);
  const payment = paymentText === null ? null : element(paymentText, {querySelectorAll:selector=>selector.startsWith('button') ? [submit] : []});
  const doc = {body:storeCheckout&&payment?payment:element(authBody),querySelector:selector=>selector==='main'?main:null,
    querySelectorAll:selector=>selector.includes('dialog')&&payment?[payment]:[]};
  const url = category==='fab' ? 'https://www.fab.com/listings/12345678-abcd-abcd-abcd-123456789012?lang=en' : 'https://store.epicgames.com/en-US/p/test-game';
  const context = {console,URL,AbortController,location:new URL(urlOverride || url),document:doc,getComputedStyle:()=>({visibility:'visible',display:'block'}),
    setInterval:fn=>ticks.push(fn),setTimeout:fn=>ticks.push(fn),addEventListener:()=>{},
    clearTimeout:()=>{},
    PocketStore:{postMessage:data=>messages.push(JSON.parse(data))},
    fetch:async request=>{
      requests.push(request);
      if (request.includes('/blades/')) return {ok:true,json:async()=>({isLimitedFreeContent:true,blockContentType:'listings',
        tiles:[{listing:{uid:'12345678-abcd-abcd-abcd-123456789012',title:'Test asset'}}]})};
      if (request.includes('/users/me')) return {ok:!fetchError,status:fetchError?401:200,json:async()=>({uid:'user-a',displayName:'Test Fab'})};
      const index=libraryPage++;
      return {ok:!fetchError&&index!==failLibraryPage,status:fetchError?401:index===failLibraryPage?503:200,
        json:async()=>libraryPages?libraryPages[index]:{results:library,cursors:{next:null}}};
    }};
  context.window = context; context.top = context;
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.join(base,'domain.js'),'utf8')+'\n'+fs.readFileSync(path.join(base,'automation.js'),'utf8'), context);
  context.runPocketTask({nonce:'test',mode,category,country:'CN',item:{id:'12345678-abcd-abcd-abcd-123456789012',url,...itemOverrides}});
  await ticks[0]();
  return {messages,cta,submit,tick:ticks[0],requests,showFabLibrary:()=>{libraryVisible=true;}};
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
test('Epic: current mobile layout finds the product action outside its title header', async () => {
  const result=await run({mode:'claim',separatePurchaseCard:true});
  assert.equal(result.cta.clicks,1);
  assert.ok(!result.messages.some(message=>message.type==='ordered'));
});
test('Epic: owned purchase card is recognized and ambiguous product cards are not clicked', async () => {
  const owned=await run({separatePurchaseCard:true,ctaText:'In Library'});
  assert.equal(owned.messages[0].status,'owned');
  const ambiguous=await run({mode:'claim',separatePurchaseCard:true,ambiguousCards:true});
  assert.equal(ambiguous.cta.clicks,undefined);
  assert.ok(ambiguous.messages.some(message=>message.type==='waiting'));
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
test('current Epic checkout: explicit zero-price free card submits Add to library once', async () => {
  const result=await run({mode:'claim',storeCheckout:true,submitText:'Add to library',urlOverride:'https://store.epicgames.com/checkout',
    paymentText:'Bad Cheese\nHK$0.00\nThis is free. Add it to your library to get started.'});
  await result.tick();
  assert.equal(result.submit.clicks,1);
  assert.ok(result.messages.some(message=>message.type==='ordered'));
  assert.ok(!result.messages.some(message=>message.status==='owned'));
});
test('current Epic checkout: nonzero or malformed free-card amounts never submit', async () => {
  for(const price of ['HK$0.01','HK$zero','0.00','HK$0.00\nTotal HK$5.00']){
    const result=await run({mode:'claim',storeCheckout:true,submitText:'Add to library',urlOverride:'https://store.epicgames.com/checkout',
      paymentText:'Game\n'+price+'\nThis is free. Add it to your library to get started.'});
    assert.equal(result.submit.clicks,undefined);
  }
});
test('Fab checkout: exact official free checkout submits once and rejects other paths', async () => {
  for (const paymentPath of ['/payment/web/purchase','/unrelated']) {
    const result=await run({mode:'claim',category:'fab',storeCheckout:true,submitText:'Add to library',
      urlOverride:'https://www.fab.com'+paymentPath,
      paymentText:'Ready Map Vol 2\nHK$0.00\nThis is free. Add it to your library to get started.'});
    await result.tick();
    assert.equal(result.submit.clicks,paymentPath==='/payment/web/purchase'?1:undefined);
    assert.ok(!result.messages.some(message=>message.status==='owned'));
  }
});

test('Fab checkout: nonzero amounts never submit', async () => {
  const result=await run({mode:'claim',category:'fab',storeCheckout:true,submitText:'Add to library',
    urlOverride:'https://www.fab.com/payment/web/purchase',
    paymentText:'Material\nHK$0.01\nThis is free. Add it to your library to get started.'});
  assert.equal(result.submit.clicks,undefined);
  assert.ok(result.messages.some(message=>message.type==='blocked'));
});

test('current Epic receipt triggers inventory verification without treating the receipt as ownership', async () => {
  const result=await run({mode:'claim',paymentText:"It's all yours\nThanks for your order.",submitText:'Continue Browsing'});
  assert.equal(result.messages.at(-1).type,'checkoutComplete');
  assert.equal(result.submit.clicks,undefined);
  assert.ok(!result.messages.some(message=>message.status==='owned'));
});
test('partial receipt text cannot skip straight to a completed checkout', async () => {
  for(const text of ["It's all yours",'Thanks for your order.']){
    const result=await run({mode:'claim',paymentText:text,submitText:'Continue Browsing'});
    assert.ok(!result.messages.some(message=>message.type==='checkoutComplete'));
  }
});
test('Fab closes checkout into a library link and requests fresh ownership verification', async () => {
  const result=await run({mode:'claim',category:'fab',fabProduct:true});
  assert.equal(result.cta.clicks,1);
  await result.tick();
  assert.ok(!result.messages.some(message=>message.type==='checkoutComplete'));
  result.showFabLibrary();
  await result.tick();
  await result.tick();
  assert.equal(result.messages.filter(message=>message.type==='checkoutComplete').length,1);
  assert.equal(result.cta.clicks,1);
  assert.ok(!result.messages.some(message=>message.status==='owned'));
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
test('Epic authorization accepts the code only on the exact official redirect page', async () => {
  const code='test-authorization-code-1234567890';
  const result=await run({mode:'auth',urlOverride:'https://www.epicgames.com/id/api/redirect',authBody:JSON.stringify({authorizationCode:code})});
  assert.equal(result.messages[0].type,'authorized'); assert.equal(result.messages[0].code,code);
  const unrelated=await run({mode:'auth',urlOverride:'https://www.epicgames.com.evil.test/id/api/redirect',authBody:JSON.stringify({authorizationCode:code})});
  assert.equal(unrelated.messages.length,0);
  const differentPage=await run({mode:'auth',urlOverride:'https://www.epicgames.com/id/login',authBody:JSON.stringify({authorizationCode:code})});
  assert.equal(differentPage.messages.length,0);
});
test('Epic authorization error text never becomes a successful connection', async () => {
  const result=await run({mode:'auth',urlOverride:'https://www.epicgames.com/id/api/redirect',authBody:'Incorrect response. Please refresh the page.'});
  assert.ok(result.messages.every(message=>message.type!=='authorized'));
});
test('Fab sign-in confirms its own session before returning to the app', async () => {
  const result=await run({mode:'auth',category:'fab'});
  assert.equal(result.messages[0].type,'authenticated');
  const signedOut=await run({mode:'auth',category:'fab',fetchError:true});
  assert.ok(signedOut.messages.every(message=>message.type!=='authenticated'));
});
test('Fab batch ownership reads every page once and publishes the complete acquired set', async () => {
  const result=await run({mode:'syncFab',category:'fab',libraryPages:[
    {results:[{listing:{uid:'asset-a'}}],cursors:{next:'page-2'}},
    {results:[{listing:{uid:'asset-b'}}],cursors:{next:null}}]});
  const batch=result.messages.find(message=>message.type==='ownership');
  assert.deepEqual([...batch.owned],['asset-a','asset-b']);
  assert.equal(result.requests.filter(url=>url.startsWith('/i/library/search')).length,2);
});
test('Fab pagination failure does not publish incomplete ownership as a completed library', async () => {
  const result=await run({mode:'syncFab',category:'fab',failLibraryPage:1,libraryPages:[
    {results:[{listing:{uid:'asset-a'}}],cursors:{next:'page-2'}}]});
  assert.ok(result.messages.some(message=>message.type==='error'));
  assert.ok(!result.messages.some(message=>message.type==='ownership'));
});
test('Fab repeated cursors fail explicitly instead of repeatedly loading the library', async () => {
  const result=await run({mode:'syncFab',category:'fab',libraryPages:[
    {results:[],cursors:{next:'again'}},{results:[],cursors:{next:'again'}}]});
  assert.ok(result.messages.some(message=>message.type==='error'&&/重复/.test(message.message)));
  assert.ok(!result.messages.some(message=>message.type==='ownership'));
});
test('upcoming or expired tasks cannot click Get or place even a zero order', async () => {
  for (const itemOverrides of [{availability:'upcoming'}, {start:'2099-01-01T00:00:00Z'}, {end:'2000-01-01T00:00:00Z'}]) {
    const result=await run({mode:'claim',paymentText:'Total $0.00',itemOverrides});
    assert.equal(result.messages[0].type,'blocked');
    assert.equal(result.cta.clicks,undefined);
    assert.equal(result.submit.clicks,undefined);
  }
});
