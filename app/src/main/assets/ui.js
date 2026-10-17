'use strict';
let state = {items: [], country: 'CN', updated: {}, message: '首次使用：登录账号，然后刷新活动。', busy: false};
let category = 'pc';
const selected = new Set();
const titles = {pc: '本周免费游戏', android: '安卓限时免费', fab: 'Fab 限时免费素材'};
const descriptions = {pc: '读取 Epic 官方活动，领取后留在你的游戏库', android: '安卓版本单独领取，以账号地区和商品页面为准', fab: '通常每两周更新，以 Fab 当前活动为准'};
const statusLabels = {unknown: '待核实', owned: '已拥有', unowned: '未拥有', expired: '当前不可领', error: '核实失败'};
const platformLabels = {pc: '电脑游戏', android: 'Android', fab: 'Fab 素材'};
const $ = id => document.getElementById(id);
function command(action, data = {}) {
  if (!window.PocketApp) { state.message = '这是界面预览；登录和领取请在安卓 APK 中使用。'; render(); return; }
  PocketApp.postMessage(JSON.stringify({action, ...data}));
}
function node(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
}
function shown() { return state.items.filter(item => item.category === category); }
function render() {
  $('category-title').textContent = titles[category];
  $('source-note').textContent = descriptions[category];
  $('message').textContent = state.message;
  $('country').value = state.country;
  document.querySelectorAll('[data-tab]').forEach(button => button.setAttribute('aria-selected', String(button.dataset.tab === category)));
  $('items').replaceChildren();
  const list = shown();
  if (!list.length) {
    const empty = node('section', 'empty');
    empty.append(node('div', 'empty-mark', '▱'), node('h3', '', state.updated[category] ? '当前没有可领取的活动' : '把本期免费收进口袋'), node('p', '', state.updated[category] ? '可以刷新活动，或在官方网页查看。' : '点击“刷新活动”，读取官方免费内容。'));
    $('items').append(empty);
  }
  for (const item of list) {
    const card = node('article', 'item'); card.dataset.category = category;
    const tile = node('div', 'tile', item.title.substring(0, 1).toUpperCase()); tile.setAttribute('aria-hidden', 'true');
    const body = node('div', 'item-body');
    let meta = platformLabels[category];
    if (item.end) meta += ' · ' + new Date(item.end).toLocaleString('zh-CN', {month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'}) + ' 截止';
    body.append(node('h3', '', item.title), node('p', 'item-meta', meta), node('span', 'status ' + item.status, statusLabels[item.status] || '待核实'));
    const actions = node('div', 'item-actions');
    const view = node('button', '', '查看官方页面'); view.disabled = state.busy; view.onclick = () => command('open', {key: item.key});
    actions.append(view); body.append(actions);
    const checkLabel = node('label', 'item-check');
    const checkbox = node('input'); checkbox.type = 'checkbox'; checkbox.checked = selected.has(item.key);
    checkbox.disabled = item.status === 'owned' || state.busy;
    checkbox.setAttribute('aria-label', '选择 ' + item.title);
    checkbox.onchange = () => { checkbox.checked ? selected.add(item.key) : selected.delete(item.key); render(); };
    checkLabel.append(checkbox); card.append(tile, body, checkLabel); $('items').append(card);
  }
  const available = list.filter(item => item.status !== 'owned');
  const count = available.filter(item => selected.has(item.key)).length;
  $('select-all').checked = available.length > 0 && count === available.length;
  $('select-all').indeterminate = count > 0 && count < available.length;
  $('select-all').disabled = !available.length || state.busy;
  $('selection-count').textContent = count ? '已选择 ' + count + ' 项' : '尚未选择';
  $('claim').disabled = count === 0 || state.busy;
  $('claim').textContent = state.busy ? '正在处理…' : '一键领取';
  for (const id of ['login','refresh','sync','country']) $(id).disabled = state.busy;
  $('updated').textContent = state.updated[category] ? '活动更新于 ' + new Date(state.updated[category]).toLocaleString('zh-CN', {month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'}) : '尚未读取活动';
}
window.receiveState = next => {
  state = next;
  for (const key of selected) if (!state.items.some(item => item.key === key && item.status !== 'owned')) selected.delete(key);
  render();
};
document.querySelectorAll('[data-tab]').forEach(button => button.onclick = () => { category = button.dataset.tab; selected.clear(); render(); });
$('select-all').onchange = event => { for (const item of shown().filter(x => x.status !== 'owned')) event.target.checked ? selected.add(item.key) : selected.delete(item.key); render(); };
$('login').onclick = () => command('login', {category});
$('refresh').onclick = () => command('refresh', {category});
$('sync').onclick = () => command('check', {keys: shown().map(item => item.key)});
$('claim').onclick = () => command('claim', {keys: PocketDomain.queue(state.items, [...selected]).map(item => item.key)});
$('country').onchange = event => command('country', {country: event.target.value});
render();
