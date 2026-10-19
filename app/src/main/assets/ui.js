'use strict';
let state = {items: [], country: 'CN', updated: {}, message: '正在读取本期免费内容…', busy: false, epicAccount: {}, fabAccount: {}};
let category = 'pc';
const selected = new Set();
const $ = id => document.getElementById(id);
const platforms = {pc: '电脑游戏', android: 'Android 手机游戏', fab: 'Fab 素材'};
const statuses = {unknown: '未能确认', owned: '已拥有', unowned: '未拥有', partial: '部分已拥有', expired: '活动已结束', error: '同步失败'};

const categoryScenes = {
  pc: {title: '免费好游戏\n收进口袋', caption: '本期免费游戏，领取后入库', art: 'game-night.webp'},
  android: {title: '手机好游戏\n收进口袋', caption: '本期 Android 免费游戏', art: 'mobile-gift.webp'},
  fab: {title: '创作新素材\n收进口袋', caption: '本期 Fab 限免素材', art: 'fab-workshop.webp'}
};

function element(tag, className, text) {
  const result = document.createElement(tag);
  if (className) result.className = className;
  if (text !== undefined) result.textContent = text;
  return result;
}

function command(action, data = {}) {
  if (!window.PocketApp) {
    state.message = '当前是界面预览。登录、同步与领取请在安卓 APK 中使用。';
    render();
    return;
  }
  PocketApp.postMessage(JSON.stringify({action, ...data}));
}

function localTime(value) {
  return new Date(value).toLocaleString('zh-CN', {month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit'});
}

function deadline(item) {
  if (item.availability === 'upcoming') return localTime(PocketDomain.promotionTime(item.start)) + ' 开始';
  if (!item.end) return '以官网活动为准';
  const left = Date.parse(item.end) - Date.now();
  if (left <= 0) return '活动已结束';
  if (left >= 86400000) return '剩余 ' + Math.ceil(left / 86400000) + ' 天';
  if (left >= 3600000) return '剩余 ' + Math.ceil(left / 3600000) + ' 小时';
  return '剩余 ' + Math.max(1, Math.ceil(left / 60000)) + ' 分钟';
}

function currentItems() { return state.items.filter(item => item.category === category).map(item => ({...item, availability: PocketDomain.availabilityAt(item)})); }
function available(items) { return PocketDomain.queue(items, items.map(item => item.key)); }
function accountForCategory() { return category === 'fab' ? state.fabAccount || {} : state.epicAccount || {}; }

function artwork(url, alt, className, retryable = false) {
  const frame = element('div', 'media-frame');
  if (!url) return element('div', 'cover-fallback', '暂无官方预览图');
  const image = element('img', className);
  image.src = url;
  image.alt = alt;
  image.referrerPolicy = 'no-referrer';
  image.decoding = 'async';
  image.addEventListener('error', () => {
    image.hidden = true;
    const error = element('div', 'cover-fallback', '图片加载失败，请检查网络');
    if (retryable) {
      const retry = element('button', 'secondary', '重试图片');
      retry.onclick = () => {
        error.remove(); image.hidden = false;
        const next = new URL(url, location.href);
        next.searchParams.set('retry', String(Date.now()));
        image.src = next.href;
      };
      error.append(retry);
    }
    frame.append(error);
  });
  frame.append(image);
  return frame;
}

function cover(item, className) {
  return artwork(item.localCover || PocketDomain.coverUrl(item.cover), item.title + ' 官方封面', className, !!className);
}

function renderCard(item) {
  const card = element('article', 'game-card');
  card.dataset.key = item.key;
  const preview = element('button', 'cover-button');
  preview.setAttribute('aria-label', '查看 ' + item.title + ' 的详情与图片');
  preview.append(cover(item, ''));
  preview.onclick = () => openDetail(item.key);
  const statusText = statuses[item.status] || statuses.unknown;
  const ownership = element('span', 'ownership ' + item.status, statusText + (item.cached && item.status === 'owned' ? ' · 缓存' : ''));
  const info = element('div', 'card-info');
  const titleRow = element('div', 'card-title');
  const label = element('label', 'card-select');
  const check = element('input');
  check.type = 'checkbox';
  check.checked = selected.has(item.key);
  check.disabled = state.busy || !available([item]).length;
  check.setAttribute('aria-label', '选择领取 ' + item.title);
  check.onchange = () => {
    if (check.checked) selected.add(item.key); else selected.delete(item.key);
    updateSelection();
  };
  label.append(check);
  titleRow.append(label, element('h3', '', item.title));
  const meta = element('div', 'card-meta');
  if (item.originalPrice) meta.append(element('del', '', item.originalPrice.replace(/^CN¥/, '¥')));
  let priceLabel = '限时免费';
  if (item.availability === 'upcoming') priceLabel = '即将限免 · 暂不可领取';
  if (item.availability === 'expired') priceLabel = '活动已结束';
  meta.append(element('span', 'free-price', priceLabel), element('span', 'deadline', deadline(item)));
  info.append(titleRow, meta);
  if (item.checkedAt) info.append(element('p', 'card-hint', (item.cached ? '上次同步 ' : '已核实 ') + localTime(item.checkedAt)));
  else if (item.status === 'error') info.append(element('p', 'card-hint', '未把读取失败当作未拥有'));
  card.append(preview, element('div', 'card-shade'), element('span', 'platform-chip', platforms[category]), ownership, info);
  return card;
}

function updateSelection() {
  const candidates = available(currentItems());
  const chosen = candidates.filter(item => selected.has(item.key));
  $('select-all').checked = candidates.length > 0 && chosen.length === candidates.length;
  $('select-all').indeterminate = chosen.length > 0 && chosen.length < candidates.length;
  $('select-all').disabled = state.busy || !candidates.length;
  $('selection-count').textContent = chosen.length ? '已选择 ' + chosen.length + ' 项' : '未选择';
  $('claim').disabled = state.busy || !!state.journalError || !chosen.length;
  const connected = accountForCategory().connected;
  if (state.busy) $('claim').textContent = '处理中…';
  else $('claim').textContent = connected ? '领取所选' : '登录并领取';
}

function render() {
  const scene = categoryScenes[category];
  $('hero-title').textContent = scene.title;
  $('hero-caption').textContent = scene.caption;
  const artworkPath = 'brand/' + scene.art;
  if ($('hero-art').getAttribute('src') !== artworkPath) $('hero-art').src = artworkPath;
  const current = currentItems();
  const active = current.filter(item => item.availability !== 'upcoming');
  const upcoming = current.filter(item => item.availability === 'upcoming');
  const account = accountForCategory();
  $('category-title').textContent = category === 'fab' ? '限时免费素材' : '当前限免';
  $('item-count').textContent = '共 ' + active.length + ' 项';
  let connection = category === 'fab' ? '未连接 Fab' : '未连接 Epic';
  if (account.connected) connection = account.displayName || '账号已连接';
  if (account.error) connection += ' · 同步失败';
  $('connection').textContent = connection;
  $('connection').classList.toggle('connected', !!account.connected && !account.error);
  $('sync-time').textContent = account.checkedAt ? '拥有状态同步于 ' + localTime(account.checkedAt) : '连接后自动核实拥有状态';
  $('message').textContent = state.message;
  $('country').value = state.country;
  if (account.error) $('account').textContent = '检查账号';
  else $('account').textContent = account.connected ? '账号已连接' : '连接账号';
  $('epic-name').textContent = state.epicAccount?.connected ? state.epicAccount.displayName : '未连接';
  $('fab-name').textContent = state.fabAccount?.connected ? state.fabAccount.displayName : '未连接';
  $('login-epic').textContent = state.epicAccount?.connected ? '重新授权' : '连接 Epic';
  $('login-fab').textContent = state.fabAccount?.connected ? '重新授权' : '连接 Fab';
  for (const button of document.querySelectorAll('[data-tab]')) {
    button.setAttribute('aria-selected', String(button.dataset.tab === category));
    button.disabled = state.busy;
    const count = state.items.filter(item => item.category === button.dataset.tab).length;
    $('count-' + button.dataset.tab).textContent = state.updated[button.dataset.tab] ? count : '—';
  }
  for (const id of ['account', 'refresh', 'sync', 'country', 'login-epic', 'login-fab', 'switch-account', 'logout']) $(id).disabled = state.busy;
  $('owned-count').textContent = '已拥有 ' + current.filter(item => item.status === 'owned').length + ' 项';
  const shown = $('hide-owned').checked ? active.filter(item => item.status !== 'owned') : active;
  $('items').replaceChildren(...shown.map(renderCard));
  const future = $('hide-owned').checked ? upcoming.filter(item => item.status !== 'owned') : upcoming;
  $('upcoming-section').hidden = !upcoming.length;
  $('upcoming-count').textContent = '共 ' + upcoming.length + ' 项';
  $('upcoming-items').replaceChildren(...future.map(renderCard));
  if (upcoming.length && !future.length) $('upcoming-items').append(element('p', 'dialog-note', '即将限免的内容均已拥有，已按筛选隐藏。'));
  if (!shown.length) {
    const empty = element('div', 'empty');
    let title = '读取本期免费内容';
    let text = '活动从官方商城获取，点击刷新可重试。';
    if (active.length && $('hide-owned').checked) { title = '本期内容都已拥有'; text = '取消“隐藏已拥有”可以查看已领取内容。'; }
    else if (state.updated[category]) { title = '当前没有可领取的活动'; text = '活动会定期更新，以官方商城为准。'; }
    empty.append(element('h3', '', title), element('p', '', text));
    $('items').append(empty);
  }
  $('updated').textContent = state.updated[category] ? '活动更新于 ' + localTime(state.updated[category]) : '活动尚未更新';
  updateSelection();
  renderBatchControls();
  if ($('history-dialog').open) renderHistory();
}

const resultLabels = {pending: '等待处理', working: '处理中', claimed: '已确认入库', owned: '原已拥有', skipped: '已跳过', failed: '失败，可重试', uncertain: '订单待核实'};
function recoverable(run) {
  return run && (run.state === 'paused' || Object.keys(run.errors || {}).length > 0 ||
    run.entries.some(entry => ['pending', 'working', 'failed', 'uncertain'].includes(entry.status)));
}
function renderBatchControls() {
  const latest = state.history?.[0];
  $('claim-all').disabled = state.busy || !!state.journalError;
  $('claim-all').textContent = state.busy ? '正在处理…' : '一键领取本期全部';
  $('batch-resume').hidden = !recoverable(latest) && !state.journalError;
  $('batch-note').textContent = state.journalError || '有未完成或待核实的记录。继续时先查库，已拥有不会重复领取。';
  $('resume-batch').disabled = state.busy || !recoverable(latest) || !!state.journalError;
}
function renderHistory() {
  const runs = state.history || [];
  const selectedId = $('history-run').value;
  $('history-run').replaceChildren(...runs.map(run => {
    const option = element('option', '', localTime(run.startedAt) + (run.state === 'paused' ? ' · 已暂停' : ''));
    option.value = run.id;
    return option;
  }));
  if (runs.some(run => run.id === selectedId)) $('history-run').value = selectedId;
  const run = runs.find(entry => entry.id === $('history-run').value);
  const content = $('history-content');
  content.replaceChildren();
  $('retry-batch').disabled = state.busy || !run || run.id !== runs[0]?.id || !recoverable(run) || !!state.journalError;
  if (!run) { content.append(element('p', 'dialog-note', state.journalError || '还没有领取记录。点击“一键领取本期全部”或选择项目开始。')); return; }
  const owners = Object.entries(run.owners || {}).map(([platform, owner]) => (platform === 'fab' ? 'Fab：' : 'Epic：') + owner.name);
  content.append(element('p', 'dialog-note', owners.join('；') || '等待连接并核实账号'));
  const counts = {};
  for (const entry of run.entries) counts[entry.status] = (counts[entry.status] || 0) + 1;
  content.append(element('p', 'history-summary', Object.entries(counts).map(([status, count]) => (resultLabels[status] || status) + ' ' + count + ' 项').join(' · ') || '正在读取活动与库存'));
  for (const [platform, reason] of Object.entries(run.errors || {})) content.append(element('p', 'dialog-note', (platforms[platform] || platform) + '：' + reason));
  for (const entry of run.entries) {
    const row = element('div', 'history-entry');
    row.append(element('strong', '', entry.title), element('p', '', (platforms[entry.category] || entry.category) + ' · ' + (resultLabels[entry.status] || entry.status)), element('p', '', entry.reason));
    if (entry.status === 'uncertain' && state.items.some(item => item.key === entry.key)) {
      const official = element('button', 'secondary', '去官网核实');
      official.disabled = state.busy;
      official.onclick = () => { closeDetail(); command('open', {key: entry.key}); };
      row.append(official);
    }
    content.append(row);
  }
}

function openDetail(key) {
  const item = state.items.find(entry => entry.key === key);
  if (!item) return;
  const body = element('div', 'detail-body');
  const title = element('h2', '', item.title);
  title.id = 'detail-title';
  body.append(title, element('p', 'detail-status', (statuses[item.status] || statuses.unknown) + (item.checkedAt ? ' · ' + localTime(item.checkedAt) : '')));
  if (item.statusReason) body.append(element('p', 'dialog-note', item.statusReason));
  if (item.availability === 'upcoming') body.append(element('p', 'upcoming-note', '即将限免，' + localTime(PocketDomain.promotionTime(item.start)) + ' 开始。开始前不可领取，拥有状态仍会同步。'));
  const gallery = element('div', 'detail-gallery');
  for (const [index, src] of (item.screenshots || []).entries()) {
    const url = item.localScreenshots?.[index] || PocketDomain.previewUrl(src);
    if (!url) continue;
    const frame = artwork(url, item.title + ' 官方预览图 ' + (index + 1), '', true);
    frame.querySelector('img').loading = 'lazy';
    gallery.append(frame);
  }
  if (gallery.children.length) body.append(element('p', 'gallery-label', '官方预览图 · ' + gallery.children.length + ' 张，可横向滑动'), gallery);
  body.append(element('p', 'detail-description', item.description || '当前官方列表未提供简介，可在商品页面查看完整说明。'));
  if (item.publisher) body.append(element('p', 'dialog-note', '发行方 / 作者：' + item.publisher));
  body.append(element('p', 'dialog-note', item.end ? '领取截止：' + new Date(item.end).toLocaleString('zh-CN') : '领取时间以官方活动页面为准'));
  const official = element('button', 'primary', '查看官方商品页面');
  official.disabled = state.busy;
  official.onclick = () => { closeDetail(); command('open', {key}); };
  body.append(official);
  $('detail-content').replaceChildren(cover(item, 'detail-cover'), body);
  $('detail-content').dataset.key = key;
  $('detail-dialog').showModal();
}

function closeDetail() {
  if ($('history-dialog').open) { $('history-dialog').close(); return true; }
  if ($('detail-dialog').open) { $('detail-dialog').close(); return true; }
  if ($('account-dialog').open) { $('account-dialog').close(); return true; }
  return false;
}
window.closeDetail = closeDetail;
window.receiveState = next => {
  state = next;
  lastPhaseSignature = phaseSignature();
  for (const key of selected) if (!available(state.items).some(item => item.key === key)) selected.delete(key);
  render();
  if ($('detail-dialog').open) {
    const item = state.items.find(entry => entry.key === $('detail-content').dataset.key);
    if (item) $('detail-content').querySelector('.detail-status').textContent = (statuses[item.status] || statuses.unknown) + (item.checkedAt ? ' · ' + localTime(item.checkedAt) : '');
    $('detail-content').querySelector('.primary').disabled = state.busy;
  }
};
for (const button of document.querySelectorAll('[data-tab]')) button.onclick = () => {
  category = button.dataset.tab;
  lastPhaseSignature = phaseSignature();
  selected.clear();
  render();
  if (!state.updated[category] || Date.now() - state.updated[category] > 300000) command('refresh', {category});
  else if (accountForCategory().connected && currentItems().some(item => item.status === 'unknown' || item.cached)) command('check', {category});
};
$('hide-owned').onchange = render;
$('select-all').onchange = event => {
  for (const item of available(currentItems())) {
    if (event.target.checked) selected.add(item.key); else selected.delete(item.key);
  }
  render();
};
$('account').onclick = () => $('account-dialog').showModal();
$('close-account').onclick = closeDetail;
$('close-detail').onclick = closeDetail;
$('refresh').onclick = () => command('refresh', {category});
$('sync').onclick = () => command('check', {category});
$('claim').onclick = () => command('claim', {category, keys: PocketDomain.queue(currentItems(), [...selected]).map(item => item.key)});
$('claim-all').onclick = () => { selected.clear(); command('claimAll'); };
$('resume-batch').onclick = () => command('resumeBatch');
$('show-history').onclick = () => { renderHistory(); $('history-dialog').showModal(); };
$('close-history').onclick = closeDetail;
$('history-run').onchange = renderHistory;
$('retry-batch').onclick = () => { closeDetail(); command('resumeBatch'); };
$('login-epic').onclick = () => { closeDetail(); command('login', {category: category === 'android' ? 'android' : 'pc'}); };
$('login-fab').onclick = () => { closeDetail(); command('login', {category: 'fab'}); };
function changeAccount(action) {
  selected.clear();
  closeDetail();
  command(action, {category});
}
$('switch-account').onclick = () => changeAccount('switchAccount');
$('logout').onclick = () => changeAccount('logout');
$('country').onchange = event => { closeDetail(); command('country', {country: event.target.value}); };
let lastPhaseSignature = '', lastAutomaticRefresh = 0;
function phaseSignature() { return currentItems().map(item => item.key + ':' + item.availability).join('|'); }
window.refreshIfDue = () => {
  if (state.busy || document.hidden) return;
  const signature = phaseSignature();
  const changed = !!lastPhaseSignature && lastPhaseSignature !== signature;
  lastPhaseSignature = signature;
  render();
  if ((changed || (state.updated[category] && Date.now() - state.updated[category] >= 300000)) && Date.now() - lastAutomaticRefresh >= 60000) {
    lastAutomaticRefresh = Date.now();
    command('refresh', {category});
  }
};
setInterval(window.refreshIfDue, 60000);
document.addEventListener('visibilitychange', () => { if (!document.hidden) window.refreshIfDue(); });
render();
