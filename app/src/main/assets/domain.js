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
  function imageUrl(value) {
    if (typeof value !== 'string') return '';
    try {
      const url = new URL(value);
      const allowed = ['epicgames.com', 'unrealengine.com', 'fab.com', 'sketchfab.com'];
      return url.protocol === 'https:' && allowed.some(host => url.hostname === host || url.hostname.endsWith('.' + host)) ? url.href : '';
    } catch { return ''; }
  }
  function optimizedImageUrl(value, width, height) {
    const safe = imageUrl(value);
    if (!safe) return '';
    const url = new URL(safe);
    // Epic's public image CDN supports server-side thumbnails; preserve signed/other-provider URLs.
    if (url.hostname === 'cdn1.epicgames.com' && !url.search) {
      url.searchParams.set('resize', '1');
      url.searchParams.set('w', String(width));
      if (height) url.searchParams.set('h', String(height));
      url.searchParams.set('quality', 'medium');
    }
    return url.href;
  }
  function coverUrl(value) { return optimizedImageUrl(value, 720, 405); }
  function previewUrl(value) { return optimizedImageUrl(value, 960); }
  function epicMedia(images = []) {
    const wide = images.find(image => ['OfferImageWide', 'DieselStoreFrontWide', 'DieselGameBox'].includes(image.type));
    const cover = imageUrl(wide?.url) || images.map(image => imageUrl(image.url)).find(Boolean) || '';
    const screenshots = images.filter(image => ['featuredMedia', 'GalleryImage', 'Screenshot'].includes(image.type))
      .map(image => imageUrl(image.url)).filter(Boolean);
    return {cover, screenshots: [...new Set(screenshots)]};
  }
  function plainText(value) {
    return typeof value === 'string' ? value.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 3000) : '';
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
        url: productUrl(slug), id: item.id, namespace: item.namespace, end: ending, status: 'unknown',
        catalogItems: (item.items || []).filter(entry => entry.id && entry.namespace)
          .map(entry => ({id: entry.id, namespace: entry.namespace})),
        originalPrice: item.price.totalPrice.fmtPrice?.originalPrice || '', description: plainText(item.description),
        publisher: item.seller?.name || '', ...epicMedia(item.keyImages)}];
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
          slug, end: claim.discount?.discountEndDate, status: 'unknown',
          catalogItems: content.catalogItemId && claim.purchasePayload?.sandboxId ?
            [{id: content.catalogItemId, namespace: claim.purchasePayload.sandboxId}] : [],
          cover: imageUrl(content.media?.card16x9?.imageSrc || content.media?.coverImage?.imageSrc),
          screenshots: [imageUrl(content.media?.coverImage?.imageSrc)].filter(Boolean),
          description: plainText(content.description), originalPrice: claim.discount?.originalPriceDisplay || ''}];
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
        url: 'https://www.fab.com/listings/' + listing.uid + '?lang=en', status: 'unknown', ...fabDetail(listing)}];
    });
  }
  function fabDetail(listing) {
    const images = (listing.thumbnails || []).map(thumbnail => imageUrl(thumbnail.mediaUrl)).filter(Boolean);
    return {cover: images[0] || '', screenshots: [...new Set(images)],
      description: plainText(listing.description), publisher: listing.user?.sellerName || '', originalPrice: ''};
  }
  function applyOwnership(items, snapshot, fresh = true, category = null) {
    if (!snapshot?.accountId || !Number.isFinite(snapshot.checkedAt)) throw new Error('账号库存身份或同步时间缺失');
    const owned = new Set(array(snapshot.owned, '账号库存').map(entry => entry.namespace + ':' + entry.id));
    return items.map(item => {
      if (item.category === 'fab' || (category && item.category !== category)) return item;
      const ids = item.catalogItems || [];
      if (!ids.length) return {...item, status: 'unknown', statusReason: '该商品暂时无法匹配账号库存，请查看官方商品页面'};
      const matches = ids.filter(entry => owned.has(entry.namespace + ':' + entry.id)).length;
      const hasAll = matches === ids.length;
      let status = 'unknown';
      if (hasAll) status = 'owned';
      else if (fresh) status = matches ? 'partial' : 'unowned';
      let reason = '等待重新同步';
      if (hasAll) reason = '账号拥有凭证已确认';
      else if (fresh) reason = matches ? '组合商品中仅部分已拥有' : '完整拥有凭证中未找到该商品';
      return {...item, status,
        ownershipAccount: snapshot.accountId, checkedAt: snapshot.checkedAt, cached: !fresh,
        statusReason: reason};
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
    return items.filter(item => wanted.has(item.key) && !['owned', 'expired'].includes(item.status) &&
      (!item.end || Date.parse(item.end) > Date.now()));
  }
  const api = {parsePc, parseMobile, parseFab, fabDetail, applyOwnership, imageUrl, coverUrl, previewUrl, zeroTotal, queue};
  if (typeof module !== 'undefined') module.exports = api;
  root.PocketDomain = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
