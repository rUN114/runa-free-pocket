const test=require('node:test');
const assert=require('node:assert/strict');
const D=require('../app/src/main/assets/domain.js');
test('Fab tier must be explicit and distinguish personal from professional',()=>{
 assert.equal(D.fabTier('PERSONAL Free -100%'),'personal');
 assert.equal(D.fabTier('Professional $0.00'),'professional');
 assert.equal(D.fabTier('Personal Professional'),'');
 assert.equal(D.fabTier('Select a license'),'');
});
