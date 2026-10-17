(function (root) {
  'use strict';
  function array(value, label) {
    if (!Array.isArray(value)) throw new Error(label + '返回格式已变化');
    return value;
  }
  function productUrl(slug) {
    if (typeof slug !== 'string' || !/^[a-zA-Z0-9_-]+$/.test(slug)) throw new Error('商品地址无效');
    return 'https://store.epicgames.com/en-US/p/' + slug;
  }
  function parsePc(data, now = Date.now()) {
    const items = array(data?.data?.Catalog?.searchStore?.elements, 'Epic 活动');
    return items.flatMap(item => {
      const promotions = item.promotions?.promotionalOffers || [];
      const active = promotions.some(group => (group.promotionalOffers || []).some(p =>
        Date.parse(p.startDate) <= now && now < Date.parse(p.endDate) &&
        p.discountSetting?.discountPercentage === 0));
      if (!active || item.price?.totalPrice?.discountPrice !== 0) return [];
      const slug = item.offerMappings?.find(m => m.pageType === 'productHome')?.pageSlug ||
        item.catalogNs?.mappings?.[0]?.pageSlug || item.productSlug || item.urlSlug;
      const ending = promotions.flatMap(p => p.promotionalOffers || []).find(p =>
        Date.parse(p.startDate) <= now && now < Date.parse(p.endDate) && p.discountSetting?.discountPercentage === 0)?.endDate;
      if (!slug || !item.id || !item.namespace || !item.title) return [];
      return [{key: 'pc:' + item.namespace + ':' + item.id, category: 'pc', title: item.title,
        url: productUrl(slug), id: item.id, namespace: item.namespace, end: ending, status: 'unknown'}];
    });
  }
  function parseMobile(data, now = Date.now()) {
    return array(data?.data, '安卓活动').filter(s => s.type === 'freeGame').flatMap(section =>
      array(section.offers, '安卓商品').flatMap(offer => {
        const content = offer.content;
        const claim = content?.purchase?.find(p => p.purchaseType === 'Claim' && p.price?.decimalPrice === 0 &&
          (!p.purchaseStateEffectiveDate || Date.parse(p.purchaseStateEffectiveDate) <= now) &&
          (!p.discount?.discountEndDate || now < Date.parse(p.discount.discountEndDate)));
        if (!claim) return [];
        const slug = content.mapping?.slug;
        if (!slug || !content.title) return [];
        return [{key: 'android:' + slug, category: 'android', title: content.title, url: productUrl(slug),
          slug, end: claim.discount?.discountEndDate, status: 'unknown'}];
      }));
  }
  function parseFab(data) {
    if (typeof data?.isLimitedFreeContent !== 'boolean' || !data.blockContentType) throw new Error('Fab 活动返回格式已变化');
    if (!data.isLimitedFreeContent) return [];
    if (data.blockContentType !== 'listings') throw new Error('Fab 活动内容格式已变化');
    return array(data.tiles, 'Fab 素材').flatMap(tile => {
      const listing = tile.listing;
      if (!listing?.uid || !/^[a-f0-9-]{36}$/i.test(listing.uid) || !listing.title) return [];
      return [{key: 'fab:' + listing.uid, id: listing.uid, category: 'fab', title: listing.title,
        url: 'https://www.fab.com/listings/' + listing.uid + '?lang=en', status: 'unknown'}];
    });
  }
  // A missing/unreadable total never grants permission to order. Subtotal and savings are excluded.
  function zeroTotal(text) {
    const lines = String(text).split(/\r?\n/).map(line => line.trim()).filter(Boolean);
    const totals = [];
    for (let i = 0; i < lines.length; i++) {
      const label = lines[i].match(/^(?:(?:order|grand)\s+)?(?:total\b|总计|合计|總計)\s*[:：]?\s*(.*)$/i);
      if (label) totals.push(label[1] || lines[i + 1] || '');
    }
    if (totals.length !== 1) return null;
    const amount = totals[0].match(/^(?:(?:USD|EUR|GBP|CNY|HKD|JPY|RMB|US\$|HK\$|CN¥|[$€£¥￥])\s*)?([0-9]+(?:[.,][0-9]{1,2})?)(?:\s*(?:USD|EUR|GBP|CNY|HKD|JPY|RMB|[$€£¥￥]))?$/i);
    return amount ? Number(amount[1].replace(',', '.')) === 0 : null;
  }
  function queue(items, keys) {
    const wanted = new Set(keys);
    return items.filter(item => wanted.has(item.key) && item.status !== 'owned');
  }
  const api = {parsePc, parseMobile, parseFab, zeroTotal, queue};
  if (typeof module !== 'undefined') module.exports = api;
  root.PocketDomain = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
