const test = require('node:test');
const assert = require('node:assert/strict');
const D = require('../app/src/main/assets/domain.js');
const now = Date.parse('2026-10-16T12:00:00Z');
function game(overrides = {}) {
  return {id:'offer-1',namespace:'space-1',title:'Test Game',catalogNs:{mappings:[{pageSlug:'test-game'}]},
    price:{totalPrice:{discountPrice:0}},promotions:{promotionalOffers:[{promotionalOffers:[{
      startDate:'2026-10-15T15:00:00Z',endDate:'2026-10-22T15:00:00Z',discountSetting:{discountPercentage:0}}]}]},...overrides};
}
const pc = item => ({data:{Catalog:{searchStore:{elements:[item]}}}});
test('PC: only an active free promotion is listed', () => {
  assert.equal(D.parsePc(pc(game()), now).length, 1);
  assert.equal(D.parsePc(pc(game()), Date.parse('2026-10-14')).length, 0);
  assert.equal(D.parsePc(pc(game()), Date.parse('2026-10-22T15:00:00Z')).length, 0);
});
test('PC: paid items, permanent free items, and partial discounts are excluded', () => {
  assert.deepEqual(D.parsePc(pc(game({price:{totalPrice:{discountPrice:100}}})), now), []);
  assert.deepEqual(D.parsePc(pc(game({promotions:null})), now), []);
  const partial = game(); partial.promotions.promotionalOffers[0].promotionalOffers[0].discountSetting.discountPercentage = 50;
  assert.deepEqual(D.parsePc(pc(partial), now), []);
});
test('PC: schema failures are explicit, and missing identity is skipped', () => {
  assert.throws(() => D.parsePc({}), /格式/);
  assert.deepEqual(D.parsePc(pc(game({namespace:''})), now), []);
  assert.throws(() => D.parsePc(pc(game({catalogNs:{mappings:[{pageSlug:'https://bad.example/'}]}})), now), /地址/);
});
test('Android: requires zero-priced Claim and keeps platform identity', () => {
  const content = {mapping:{slug:'mobile-game'},title:'Test Mobile',purchase:[{purchaseType:'Claim',price:{decimalPrice:0}}]};
  const data = {data:[{type:'freeGame',offers:[{content}]}]};
  assert.equal(D.parseMobile(data)[0].key, 'android:mobile-game');
  content.purchase[0].price.decimalPrice = 2;
  assert.equal(D.parseMobile(data).length, 0);
  content.purchase[0] = {purchaseType:'Buy',price:{decimalPrice:0}};
  assert.equal(D.parseMobile(data).length, 0);
  assert.throws(() => D.parseMobile({data:{}}), /格式/);
});
test('Fab: only the explicitly marked limited-time promotion is listed', () => {
  const blade = {isLimitedFreeContent:true,blockContentType:'listings',tiles:[{listing:{uid:'12345678-abcd-abcd-abcd-123456789012',title:'Test Asset'}}]};
  assert.equal(D.parseFab(blade).length, 1);
  assert.equal(D.parseFab({...blade,isLimitedFreeContent:false}).length, 0);
  assert.throws(() => D.parseFab({tiles:[]}), /格式/);
  assert.throws(() => D.parseFab({...blade,blockContentType:'unknown'}), /格式/);
  assert.equal(D.parseFab({...blade,tiles:[{listing:{uid:'../../bad',title:'Bad'}}]}).length, 0);
});
for (const value of ['Total $0.00','TOTAL\nUSD 0.00','Total CN¥0.00','Order total: €0,00','总计 ￥0.00','合计：0.00 CNY']) {
  test('zero total accepted: ' + value, () => assert.equal(D.zeroTotal(value), true));
}
for (const value of ['Total $0.01','Total €20,00','Total CNY 10.00']) {
  test('paid total blocked: ' + value, () => assert.equal(D.zeroTotal(value), false));
}
for (const value of ['Free','Subtotal $0.00','Savings $20.00','Product description mentions Total $0.00','Total $0.00\nTotal $0.00','Total $1,000.00','Total -$1.00','Total 0evil','']) {
  test('ambiguous/missing total blocked: ' + value, () => assert.equal(D.zeroTotal(value), null));
}
test('selection skips owned entries and ignores missing/duplicate keys', () => {
  const items = [{key:'a',status:'owned'},{key:'b',status:'unknown'},{key:'c',status:'unowned'}];
  assert.deepEqual(D.queue(items,['a','b','b','unknown']).map(i=>i.key), ['b']);
});
test('Android: future and expired free price entries are not offered', () => {
  const purchase = {purchaseType:'Claim',price:{decimalPrice:0},purchaseStateEffectiveDate:'2026-10-17T00:00:00Z'};
  const data = {data:[{type:'freeGame',offers:[{content:{title:'Test Mobile',mapping:{slug:'test-mobile'},purchase:[purchase]}}]}]};
  assert.equal(D.parseMobile(data,now).length,0);
  purchase.purchaseStateEffectiveDate='2026-10-15T00:00:00Z';
  purchase.discount={discountEndDate:'2026-10-16T12:00:00Z'};
  assert.equal(D.parseMobile(data,now).length,0);
});
