function runPocketTask(task) {
  'use strict';
  const D = PocketDomain;
  let finished = false, busy = false, clickedGet = false, ordered = false, lastNotice = '';
  let authChecked = false;
  let fabOwned = null;
  const send = (type, data = {}) => PocketStore.postMessage(JSON.stringify({nonce: task.nonce, type, ...data}));
  const finish = (type, data) => { if (!finished) { finished = true; send(type, data); } };
  const notice = text => { if (text !== lastNotice) { lastNotice = text; send('waiting', {message: text}); } };
  const visible = el => {
    const box = el.getBoundingClientRect();
    const style = getComputedStyle(el);
    return box.width > 0 && box.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
  };
  const label = el => (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
  const buttons = scope => [...scope.querySelectorAll('button, a[role="button"]')].filter(visible);
  async function json(url) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 25000);
    try {
      const credentials = new URL(url, location.href).origin === location.origin ? 'include' : 'omit';
      const response = await fetch(url, {credentials, headers: {Accept: 'application/json'}, signal: controller.signal});
      if (!response.ok) throw new Error('请求失败 HTTP ' + response.status + '；请确认登录或完成网页验证');
      return await response.json();
    } finally { clearTimeout(timeout); }
  }
  async function discover() {
    let items;
    if (task.category === 'fab') {
      items = D.parseFab(await json('/i/blades/free_content_blade'));
      await Promise.all(items.map(async item => {
        if (item.cover) return;
        try { Object.assign(item, D.fabDetail(await json('/i/listings/' + item.id))); }
        catch { item.mediaError = '素材图片暂未能读取，可在官方页面查看'; }
      }));
    } else if (task.category === 'android') {
      items = D.parseMobile(await json('https://egs-platform-service.store.epicgames.com/api/v2/public/discover/home?count=10&country=' + task.country + '&locale=en&platform=android&start=0&store=EGS'));
    } else throw new Error('此网页任务不支持该活动分类');
    finish('catalogue', {items});
  }
  async function fabLibrary() {
    const me = await json('/i/users/me');
    if (!me || typeof me !== 'object') throw new Error('Fab 登录状态无法确认');
    const owned = new Set(), cursors = new Set();
    let url = '/i/library/search?count=100&source=acquired';
    for (let page = 0; page < 200; page++) {
      const data = await json(url);
      if (!Array.isArray(data.results)) throw new Error('Fab 素材库格式已变化，无法确认拥有状态');
      for (const entry of data.results) {
        if (entry.uid) owned.add(entry.uid);
        if (entry.listing?.uid) owned.add(entry.listing.uid);
      }
      const next = data.cursors?.next;
      if (!next) {
        if (data.results.length === 100 && !data.cursors) throw new Error('素材库缺少分页信息，暂不能判定未拥有');
        return {owned, displayName: me.displayName || me.username || me.sellerName || 'Fab 账号'};
      }
      if (cursors.has(next)) throw new Error('素材库分页重复，暂不能确认拥有状态');
      cursors.add(next);
      url = '/i/library/search?count=100&source=acquired&cursor=' + encodeURIComponent(next);
    }
    throw new Error('素材库分页数量超出本次核实范围');
  }
  async function fabOwnership() { return (await fabLibrary()).owned.has(task.item.id); }
  async function auth() {
    if (task.category === 'fab' && location.hostname === 'www.fab.com') {
      if (authChecked) return;
      authChecked = true;
      try {
        const me = await json('/i/users/me');
        if (!me || typeof me !== 'object' || Array.isArray(me)) throw new Error('Fab 登录状态无法确认');
        finish('authenticated', {displayName: me.displayName || me.username || me.sellerName || 'Fab 账号'});
      } catch { notice('请完成 Fab 官方登录及首次协议，完成后将自动刷新并同步素材库'); }
      return;
    }
    if (task.category !== 'fab' && location.hostname === 'www.epicgames.com' && location.pathname === '/id/api/redirect') {
      try {
        const data = JSON.parse(document.body.innerText);
        if (typeof data.authorizationCode === 'string' && /^[a-zA-Z0-9_-]{16,1024}$/.test(data.authorizationCode)) {
          finish('authorized', {code: data.authorizationCode});
        } else notice('未取得库存授权，请在官方登录页面完成账号验证');
      } catch { notice('正在等待 Epic 官方授权完成'); }
    }
  }
  function productScope() {
    const main = document.querySelector('main');
    if (!main) return null;
    const heading = main.querySelector('h1');
    if (!heading || !visible(heading)) return null;
    return main;
  }
  function epicCta(scope) {
    // This product's explicit CTA takes precedence over recommendation cards.
    const exact = scope.querySelector('button[data-testid="purchase-cta-button"]');
    if (exact && visible(exact)) return exact;
    // New layouts put the product action beside the main heading, before recommendations.
    const h1 = scope.querySelector('h1');
    let parent = h1.parentElement;
    for (let depth = 0; parent && parent !== scope && depth < 5; depth++, parent = parent.parentElement) {
      const candidates = buttons(parent).filter(b => /^(get|in library|owned|获取|领取|已在库中|已拥有)$/i.test(label(b)));
      if (candidates.length === 1) return candidates[0];
    }
    return null;
  }
  async function inspectProduct() {
    const path = url => new URL(url).pathname.replace(/^\/[a-z]{2}-[A-Z]{2}(?=\/)/, '');
    if (path(location.href) !== path(task.item.url)) return;
    const scope = productScope();
    if (!scope) return;
    if (task.category === 'fab') {
      if (clickedGet) return;
      if (fabOwned === null) fabOwned = await fabOwnership();
      if (fabOwned) { finish('result', {status: 'owned'}); return; }
      if (task.mode === 'check') { finish('result', {status: 'unowned'}); return; }
      const current = D.parseFab(await json('/i/blades/free_content_blade'));
      if (!current.some(x => x.id === task.item.id)) { finish('result', {status: 'expired', message: '该素材已不在限时免费活动中'}); return; }
      const buy = buttons(scope).find(b => /^(buy now|立即购买)$/i.test(label(b)));
      const freeLabel = /(?:-100%|100% off)/i.test(scope.innerText) && /\bfree\b|免费/i.test(scope.innerText);
      if (!buy || !freeLabel) { notice('未能确认当前素材免费；请查看官方页面后手动处理'); return; }
      clickedGet = true;
      buy.click();
    } else {
      const cta = epicCta(scope);
      if (!cta) { notice('正在等待商品主按钮；若网页要求登录或验证，请先完成'); return; }
      const text = label(cta);
      if (/^(in library|owned|已在库中|已拥有)$/i.test(text)) { finish('result', {status: 'owned'}); return; }
      if (!/^(get|获取|领取)$/i.test(text)) {
        if (/buy|购买|unavailable|不可用|即将|coming/i.test(text)) finish('result', {status: 'expired', message: '该商品当前无法免费领取'});
        return;
      }
      if (task.mode === 'check') { finish('result', {status: 'unowned'}); return; }
      if (!clickedGet && !cta.disabled) { clickedGet = true; cta.click(); }
    }
  }
  function checkout() {
    if (finished || task.mode !== 'claim') return;
    const inPaymentFrame = location.hostname === 'www.epicgames.com' && location.pathname.startsWith('/payment/');
    const scope = inPaymentFrame ? document.body : [...document.querySelectorAll('[role="dialog"], dialog')].find(visible);
    if (!scope) return;
    const heading = [...scope.querySelectorAll('h1,h2,h3')].find(el => visible(el) &&
      /^(thank you[!.]?|thanks for (your purchase|buying)[!.]?|order complete[!.]?|感谢购买[！!]?|订单完成[！!]?)$/i.test(label(el)));
    if (heading) { finish('checkoutComplete'); return; }
    if (ordered) return;
    const submit = buttons(scope).find(b => /^(place order|add to library|下单|下订单|加入库|添加到库)$/i.test(label(b)));
    if (!submit || submit.disabled) return;
    const free = D.zeroTotal(scope.innerText);
    if (free !== true) {
      if (free === false) finish('blocked', {message: '订单合计不为零，已停止自动下单'});
      else notice('未能读取明确的零元合计。请查看订单，必要时手动领取，再点“核实结果”');
      return;
    }
    // Consent, age, and marketing choices belong to the user; never tick them automatically.
    const unchecked = [...scope.querySelectorAll('input[type="checkbox"][required]')].some(x => !x.checked);
    if (unchecked) { notice('请阅读并处理官方许可协议，然后程序会继续'); return; }
    ordered = true;
    submit.click();
    send('ordered');
  }
  async function tick() {
    if (finished || busy || !document.body) return;
    busy = true;
    try {
      const isTop = window === window.top;
      const fab = location.hostname === 'www.fab.com';
      const epic = location.hostname === 'store.epicgames.com';
      if (isTop && task.mode === 'auth') {
        await auth();
      } else if (isTop && task.mode === 'syncFab' && fab) {
        const library = await fabLibrary();
        finish('ownership', {owned: [...library.owned], checkedAt: Date.now(), displayName: library.displayName});
      } else if (isTop && task.mode === 'discover' && ((task.category === 'fab' && fab) || (task.category !== 'fab' && epic))) {
        await discover();
      } else if (task.mode === 'check' || task.mode === 'claim') {
        if (isTop && ((task.category === 'fab' && fab) || (task.category !== 'fab' && epic))) await inspectProduct();
        checkout();
      }
    } catch (error) {
      finish('error', {message: error.message});
    } finally { busy = false; }
  }
  setInterval(tick, 1600);
  addEventListener('DOMContentLoaded', tick, {once: true});
  setTimeout(tick, 50);
}
