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
test('PC: active and announced future promotions are listed', () => {
  assert.equal(D.parsePc(pc(game()), now).length, 1);
  assert.equal(D.parsePc(pc(game()), Date.parse('2026-10-14'))[0].availability, 'upcoming');
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
test('free checkout requires the exact free-card statement and one explicit currency amount', () => {
  const text=price=>'Game\n'+price+'\nThis is free. Add it to your library to get started.';
  assert.equal(D.zeroFreeCheckout(text('HK$0.00')),true);
  assert.equal(D.zeroFreeCheckout(text('USD 0.00')),true);
  assert.equal(D.zeroFreeCheckout(text('HK$0.01')),false);
  for(const price of ['0.00','Free','HK$zero','HK$0.00\nHK$0.00','HK$0.00\nTotal HK$5.00','HK$0.00\nTotal 0evil'])
    assert.equal(D.zeroFreeCheckout(text(price)),null);
  assert.equal(D.zeroFreeCheckout('Description says free\nHK$0.00'),null);
});
test('Android: announced future offers are visible but expired entries are excluded', () => {
  const purchase = {purchaseType:'Claim',price:{decimalPrice:0},purchaseStateEffectiveDate:'2026-10-17T00:00:00Z'};
  const data = {data:[{type:'freeGame',offers:[{content:{title:'Test Mobile',mapping:{slug:'test-mobile'},purchase:[purchase]}}]}]};
  assert.equal(D.parseMobile(data,now)[0].availability,'upcoming');
  purchase.purchaseStateEffectiveDate='2026-10-15T00:00:00Z';
  purchase.discount={discountEndDate:'2026-10-16T12:00:00Z'};
  assert.equal(D.parseMobile(data,now).length,0);
});
test('PC media preserves wide cover, screenshots, real price and catalog IDs', () => {
  const item=game({items:[{namespace:'space-1',id:'catalog-1'}],description:'<p>Real description</p>',
    keyImages:[{type:'OfferImageWide',url:'https://cdn1.epicgames.com/cover.png'},
      {type:'featuredMedia',url:'https://cdn1.epicgames.com/screen.jpg'},
      {type:'heroCarouselVideo',url:'com.epicgames.video.qs://video'}],
    price:{totalPrice:{discountPrice:0,fmtPrice:{originalPrice:'CN¥40.00'}}}});
  const parsed=D.parsePc(pc(item),now)[0];
  assert.equal(parsed.cover,'https://cdn1.epicgames.com/cover.png');
  assert.deepEqual(parsed.screenshots,['https://cdn1.epicgames.com/screen.jpg']);
  assert.equal(parsed.originalPrice,'CN¥40.00');
  assert.deepEqual(parsed.catalogItems,[{namespace:'space-1',id:'catalog-1'}]);
  assert.equal(parsed.description,'Real description');
});
test('image URLs accept official HTTPS CDNs and reject deceptive hosts and active schemes', () => {
  assert.equal(D.imageUrl('https://cdn1-epicgames-1251447533.file.myqcloud.com/a.png'), 'https://cdn1-epicgames-1251447533.file.myqcloud.com/a.png');
  assert.equal(D.imageUrl('https://other.file.myqcloud.com/a.png'), '');
  assert.equal(D.imageUrl('https://cdn1-epicgames-1251447533.file.myqcloud.com.evil.test/a.png'), '');
  assert.equal(D.imageUrl('https://media.fab.com/a.png'),'https://media.fab.com/a.png');
  assert.equal(D.imageUrl('https://cdn1.epicgames.com.evil.test/a.png'),'');
  assert.equal(D.imageUrl('javascript:alert(1)'),'');
  assert.equal(D.imageUrl('http://cdn1.epicgames.com/a.png'),'');
});
test('owned matching uses namespace + catalog item, not the promotional offer ID', () => {
  const items=[{key:'offer-1',category:'pc',catalogItems:[{namespace:'ns',id:'catalog-1'}]}];
  const snapshot={accountId:'account-a',checkedAt:now,owned:[{namespace:'ns',id:'offer-1'}],checks:[{namespace:'ns',id:'catalog-1',owned:false}]};
  assert.equal(D.applyOwnership(items,snapshot)[0].status,'unowned');
  snapshot.owned=[{namespace:'ns',id:'catalog-1'}];
  assert.equal(D.applyOwnership(items,snapshot)[0].status,'owned');
  snapshot.owned=[{namespace:'another-namespace',id:'catalog-1'}];
  assert.equal(D.applyOwnership(items,snapshot)[0].status,'unowned');
});
test('PC and Android variants of the same title have distinct ownership', () => {
  const items=[{key:'pc',category:'pc',catalogItems:[{namespace:'same',id:'pc-item'}]},
    {key:'android',category:'android',catalogItems:[{namespace:'same',id:'android-item'}]}];
  const result=D.applyOwnership(items,{accountId:'account-a',checkedAt:now,owned:[{namespace:'same',id:'pc-item'}]});
  assert.deepEqual(result.map(item=>item.status),['owned','unknown']);
});
test('bundle ownership requires all parts and identifies partially owned bundles', () => {
  const items=[{key:'bundle',category:'pc',catalogItems:[{namespace:'ns',id:'a'},{namespace:'ns',id:'b'}]}];
  const snapshot={accountId:'account-a',checkedAt:now,owned:[{namespace:'ns',id:'a'}],checks:[{namespace:'ns',id:'b',owned:false}]};
  assert.equal(D.applyOwnership(items,snapshot)[0].status,'partial');
  snapshot.owned.push({namespace:'ns',id:'b'});
  assert.equal(D.applyOwnership(items,snapshot)[0].status,'owned');
});
test('cached positive ownership survives, but missing cached IDs do not mean unowned', () => {
  const items=[{category:'pc',catalogItems:[{namespace:'ns',id:'a'}]},
    {category:'android',catalogItems:[{namespace:'ns',id:'b'}]}];
  const result=D.applyOwnership(items,{accountId:'account-a',checkedAt:now,owned:[{namespace:'ns',id:'a'}]},false);
  assert.equal(result[0].status,'owned'); assert.equal(result[0].cached,true);
  assert.equal(result[1].status,'unknown');
});
test('unknown identity, malformed ownership, and missing product IDs never become false unowned', () => {
  assert.throws(()=>D.applyOwnership([],{owned:[],checkedAt:now}),/身份/);
  assert.throws(()=>D.applyOwnership([],{accountId:'a',owned:{},checkedAt:now}),/格式/);
  const result=D.applyOwnership([{category:'pc',catalogItems:[]}],{accountId:'a',owned:[],checkedAt:now});
  assert.equal(result[0].status,'unknown');
});
test('switching accounts replaces previous ownership instead of accumulating it', () => {
  const item={category:'pc',catalogItems:[{namespace:'ns',id:'a'}]};
  const owned=D.applyOwnership([item],{accountId:'a',checkedAt:now,owned:[{namespace:'ns',id:'a'}]});
  const other=D.applyOwnership(owned,{accountId:'b',checkedAt:now+1000,owned:[],checks:[{namespace:'ns',id:'a',owned:false}]});
  assert.equal(other[0].status,'unowned'); assert.equal(other[0].ownershipAccount,'b');
});
test('Fab ownership is kept separate from Epic game entitlements', () => {
  const asset={category:'fab',status:'owned'};
  assert.equal(D.applyOwnership([asset],{accountId:'a',checkedAt:now,owned:[]})[0],asset);
});
test('expired giveaways and owned items are excluded from the actual claim queue', () => {
  const items=[{key:'expired',status:'unowned',end:'2020-01-01T00:00:00Z'},
    {key:'owned',status:'owned'},{key:'ready',status:'unowned'}];
  assert.deepEqual(D.queue(items,items.map(i=>i.key)).map(i=>i.key),['ready']);
});
test('updating cached metadata for one platform preserves fresh ownership on the other platform', () => {
  const items=[{key:'pc',category:'pc',status:'unowned',catalogItems:[{namespace:'ns',id:'a'}]},
    {key:'android',category:'android',catalogItems:[{namespace:'ns',id:'b'}]}];
  const result=D.applyOwnership(items,{accountId:'a',checkedAt:now,owned:[{namespace:'ns',id:'b'}]},false,'android');
  assert.equal(result[0],items[0]); assert.equal(result[0].status,'unowned');
  assert.equal(result[1].status,'owned');
});
test('mobile covers use the verified Epic thumbnail endpoint without rewriting signed or other-provider images', () => {
  const thumb=new URL(D.coverUrl('https://cdn1.epicgames.com/game/cover.png'));
  assert.equal(thumb.searchParams.get('w'),'720'); assert.equal(thumb.searchParams.get('h'),'405');
  assert.equal(D.coverUrl('https://cdn1.epicgames.com/game/cover.png?signature=example'),
    'https://cdn1.epicgames.com/game/cover.png?signature=example');
  assert.equal(D.coverUrl('https://media.fab.com/cover.png'),'https://media.fab.com/cover.png');
});
test('mobile grant inherited through another catalog item is confirmed by exact ownership query', () => {
  const item={key:'android-agent',category:'android',catalogItems:[{namespace:'same-ns',id:'android-item'}]};
  const direct={accountId:'a',checkedAt:now,owned:[{namespace:'same-ns',id:'parent-item'}]};
  assert.equal(D.applyOwnership([item],direct)[0].status,'unknown','absence from direct grants is insufficient');
  const proof={...direct,checks:[{namespace:'same-ns',id:'android-item',owned:true}]};
  assert.equal(D.applyOwnership([item],proof)[0].status,'owned');
  proof.checks[0].owned=false;
  assert.equal(D.applyOwnership([item],proof)[0].status,'unowned','only an explicit false permission is a negative');
  proof.checks[0].id='pc-item';
  assert.equal(D.applyOwnership([item],proof)[0].status,'unknown','PC permission cannot stand in for Android permission');
});
test('upcoming games retain ownership and cannot be selected even when keys are supplied directly', () => {
  const future=game({items:[{namespace:'ns',id:'future-item'}],price:{totalPrice:{discountPrice:500}},
    promotions:{upcomingPromotionalOffers:[{promotionalOffers:[{startDate:'2099-10-22T15:00:00Z',endDate:'2099-10-29T15:00:00Z',discountSetting:{discountPercentage:0}}]}]}});
  const parsed=D.parsePc(pc(future),now);
  assert.equal(parsed[0].availability,'upcoming');
  assert.equal(D.queue(parsed,[parsed[0].key]).length,0);
  const owned=D.applyOwnership(parsed,{accountId:'a',checkedAt:now,owned:[{namespace:'ns',id:'future-item'}]});
  assert.equal(owned[0].status,'owned');
  assert.equal(owned[0].availability,'upcoming');
  assert.equal(D.queue([{key:'tampered',start:'2099-01-01T00:00:00Z',availability:'active'}],['tampered']).length,0);
});
test('mobile future free price is shown with independent catalog and offer identity', () => {
  const data={data:[{type:'freeGame',offers:[{content:{title:'Mobile future',catalogItemId:'mobile-catalog',mapping:{slug:'mobile-future'},purchase:[{
    purchaseType:'Claim',price:{decimalPrice:0},purchaseStateEffectiveDate:Date.parse('2099-01-01'),purchasePayload:{sandboxId:'mobile-ns',offerId:'mobile-offer'},discount:{discountEndDate:'2099-01-08T00:00:00Z'}}]}}]}]};
  const item=D.parseMobile(data,now)[0];
  assert.equal(item.availability,'upcoming');
  assert.deepEqual(item.catalogItems,[{namespace:'mobile-ns',id:'mobile-catalog'}]);
  assert.equal(item.id,'mobile-offer');
  assert.equal(D.queue([item],[item.key]).length,0);
});
test('an active mobile Claim takes precedence over a later free price and malformed dates are excluded', () => {
  const future={purchaseType:'Claim',price:{decimalPrice:0},purchaseStateEffectiveDate:'2099-01-01T00:00:00Z'};
  const active={...future,purchaseStateEffectiveDate:'2026-10-15T00:00:00Z'};
  const content={title:'Mobile',mapping:{slug:'mobile'},purchase:[future,active]};
  const data={data:[{type:'freeGame',offers:[{content}]}]};
  assert.equal(D.parseMobile(data,now)[0].availability,'active');
  content.purchase=[{...future,purchaseStateEffectiveDate:'broken-date'}];
  assert.equal(D.parseMobile(data,now).length,0);
});
