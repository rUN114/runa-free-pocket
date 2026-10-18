// Optional design-time tool. APK builds use the committed WebP files directly.
const sharp = require('sharp');
const path = require('node:path');
const fs = require('node:fs');
const root = path.resolve(__dirname, '..');
const source = path.join(root, 'design/brand-source');
const brand = path.join(root, 'app/src/main/assets/brand');
(async () => {
  for (const name of ['game-night', 'mobile-gift', 'fab-workshop']) {
    await sharp(path.join(source, name + '.png'))
      .resize({width: 640, kernel: 'nearest', withoutEnlargement: true})
      .webp({quality: 88, alphaQuality: 100, effort: 6, smartSubsample: true})
      .toFile(path.join(brand, name + '.webp'));
  }
  const icon = path.join(source, 'launcher-square.png');
  await sharp(icon).resize({width: 192, kernel: 'nearest'})
    .webp({quality: 90, effort: 6}).toFile(path.join(brand, 'logo.webp'));
  await sharp(icon).resize({width: 432, kernel: 'nearest'})
    .webp({quality: 94, effort: 6})
    .toFile(path.join(root, 'app/src/main/res/drawable-nodpi/runa_icon.webp'));
  for (const name of fs.readdirSync(brand)) console.log(name + ': ' + fs.statSync(path.join(brand, name)).size + ' bytes');
})().catch(error => { console.error(error.message); process.exitCode = 1; });
