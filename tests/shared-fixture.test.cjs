const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const D = require('../app/src/main/assets/domain.js');

// One canonical fixture feeds both this suite and CatalogRulesTest (JVM) so the two PC
// parsers cannot drift apart. All dates are strict ISO-8601 UTC: JS Date.parse and Kotlin
// Instant.parse agree on them; epoch-ms stays a cache-boundary concern and is absent here.
const dir = path.join(__dirname, '../app/src/test/resources/fixtures');
const data = JSON.parse(fs.readFileSync(path.join(dir, 'pc-promotions.json'), 'utf8'));
const expected = JSON.parse(fs.readFileSync(path.join(dir, 'pc-promotions-expected.json'), 'utf8'));
const now = Date.parse(expected.now);

test('shared PC fixture: JS parser matches the cross-language reference output', () => {
  const items = D.parsePc(data, now);
  assert.equal(items.length, expected.items.length);
  items.forEach((got, i) => {
    const want = expected.items[i];
    assert.equal(got.key, want.key, want.key);
    assert.equal(got.title, want.title, want.key);
    assert.equal(got.availability, want.availability, want.key);
    assert.equal(got.url, want.url, want.key);
    assert.equal(got.cover, want.cover, want.key);
    assert.deepEqual(got.catalogItems, want.catalogItems, want.key);
  });
  for (const title of expected.excludedTitles) {
    assert.ok(!items.some(item => item.title === title), `${title} must be excluded`);
  }
});

test('button vocabulary groups keep the exact-match contract per claim stage', () => {
  const L = D.labels;
  for (const text of ['Get', 'GET', '获取', '领取']) assert.ok(L.claim.test(text), text);
  for (const text of ['In Library', 'Owned', '已在库中', '已拥有']) assert.ok(L.owned.test(text), text);
  assert.ok(!L.claim.test('In Library') && !L.owned.test('Get'), 'claim and owned stay disjoint');
  for (const text of ['Wishlist', 'Add to Wishlist', '加入愿望单', '添加至愿望单', '願望清單']) assert.ok(L.wishlistAnchor.test(text), text);
  for (const text of ['Place Order', 'Add to Library', '下单', '下订单', '加入库', '添加到库']) assert.ok(L.submitOrder.test(text), text);
  for (const text of ['Buy now', '立即购买']) assert.ok(L.fabBuy.test(text), text);
  assert.ok(L.viewInLibrary.test('View in My Library'));
  assert.ok(L.unavailable.test('Buy Now') && L.unavailable.test('不可用') && L.unavailable.test('Coming Soon'), 'unavailable is a substring guard');
  for (const text of ['Thank you!', 'Thanks for your purchase.', 'Order complete.', '感谢购买！', '订单完成！']) assert.ok(L.receiptDone.test(text), text);
  assert.ok(L.receiptAllYours.test("It's All Yours"), 'receipt marker');
  assert.ok(L.receiptThanks.test('Thanks for your order.'), 'receipt marker');
  assert.ok(L.continueBrowsing.test('Continue Browsing'));
});
