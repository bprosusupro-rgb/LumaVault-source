// Dependency-free smoke test for the gallery logic. Run with: node smoke-test.js
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const assert = require('node:assert/strict');

class Element {
  constructor(tag = 'div') {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.dataset = {};
    this.style = {};
    this.value = '';
    this.hidden = false;
    this.className = '';
    this.classList = {
      add: (...names) => { this.classes ??= new Set(); names.forEach(name => this.classes.add(name)); },
      remove: (...names) => { this.classes ??= new Set(); names.forEach(name => this.classes.delete(name)); },
      contains: name => this.classes?.has(name) ?? false
    };
  }
  append(...nodes) { this.children.push(...nodes); }
  appendChild(node) { this.children.push(node); return node; }
  setAttribute(name, value) { this[name] = String(value); }
  set innerHTML(value) { this.children = []; this.html = value; }
  get innerHTML() { return this.html ?? ''; }
  set textContent(value) { this.children = []; this.text = String(value); }
  get textContent() { return (this.text ?? '') + this.children.map(child => child.textContent ?? '').join(''); }
  click() { this.onclick?.({ target: this }); }
}

const filePath = path.join(__dirname, 'index.html');
const html = fs.readFileSync(filePath, 'utf8');
const mainActivityPath = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'java', 'local', 'vaultgallery', 'MainActivity.java');
const mainActivity = fs.readFileSync(mainActivityPath, 'utf8');
for (const bridgeMethod of ['getDiagnosticLog', 'logEvent', 'clearDiagnosticLog', 'exportDiagnosticLog']) {
  assert.match(mainActivity, new RegExp('@JavascriptInterface\\s+public\\s+\\w+\\s+' + bridgeMethod + '\\s*\\('), `Android bridge exposes ${bridgeMethod}`);
}
assert.match(mainActivity, /Intent\.ACTION_CREATE_DOCUMENT/, 'Android bridge opens the system save dialog');
assert.match(mainActivity, /window\.onDiagnosticExported\(true/, 'Android bridge reports completed exports to the UI');
const script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/gi)].at(-1)?.[1];
assert.ok(script, 'gallery JavaScript is present');

const ids = ['notice', 'edsStatus', 'edsStatusText', 'pickButton', 'helpButton', 'logButton', 'logModal', 'logPreview', 'logResult', 'closeLog', 'exportLog', 'clearLog', 'resetButton', 'folderInput', 'filesInput', 'tree', 'allCount', 'summary', 'empty', 'grid', 'search', 'sort', 'helpModal', 'closeHelp', 'closeViewer', 'prevViewer', 'nextViewer', 'lightbox', 'viewerImage', 'viewerName', 'viewerPath'];
const elements = Object.fromEntries(ids.map(id => [id, new Element(id === 'tree' ? 'ul' : 'div')]));
elements.sort.value = 'name';
const document = {
  getElementById: id => id === 'allCount' ? (elements.tree.children.flatMap(li => li.children).flatMap(button => button.children).find(child => child.id === 'allCount') ?? null) : elements[id] ?? (elements[id] = new Element()),
  createElement: tag => new Element(tag),
  createTextNode: text => ({ textContent: String(text) }),
  querySelectorAll: selector => selector === '#tree button' ? elements.tree.children.flatMap(li => li.children).filter(el => el.tagName === 'BUTTON') : []
};
let exported = false;
const window = { AndroidGallery: { edsStatus: () => 'provider', logEvent() {}, getDiagnosticLog: () => 'test diagnostic entry', exportDiagnosticLog: () => { exported = true; }, clearDiagnosticLog() {} } };
const context = {
  window, document, localStorage: { getItem: () => 'seen', setItem() {} },
  URL: { createObjectURL: file => `blob:${file.name}`, revokeObjectURL() {} },
  console
};
vm.runInNewContext(script, context, { filename: filePath });

elements.logButton.click();
assert.equal(elements.logPreview.textContent, 'test diagnostic entry');
assert.equal(elements.logModal.classList.contains('open'), true);
elements.exportLog.click();
assert.equal(exported, true, 'export button calls the Android save flow');

window.onNativeFolder([
  { name: 'cover.jpg', path: 'Album/cover.jpg', uri: 'native://img0/cover.jpg', size: 12, mime: 'image/jpeg' },
  { name: 'inside.png', path: 'Album/Trip/inside.png', uri: 'native://img1/inside.png', size: 24, mime: 'image/png' },
  { name: 'other.webp', path: 'Elsewhere/other.webp', uri: 'native://img2/other.webp', size: 36, mime: 'image/webp' }
], 'eds');

const folders = document.querySelectorAll('#tree button');
assert.deepEqual(folders.map(button => button.dataset.folder), ['', 'Album', 'Album/Trip', 'Elsewhere']);
assert.match(elements.edsStatusText.textContent, /working.*3 pictures found/);
assert.match(elements.summary.textContent, /^3 of 3 pictures/);

folders.find(button => button.dataset.folder === 'Album').click();
assert.equal(folders.find(button => button.dataset.folder === 'Album')['aria-pressed'], 'true', 'selected album is announced to assistive technology');
assert.equal(folders.find(button => button.dataset.folder === '')['aria-pressed'], 'false', 'inactive album is announced as unselected');
assert.match(elements.summary.textContent, /^2 of 3 pictures.*Album$/);
assert.equal(elements.grid.children.length, 2);

folders.find(button => button.dataset.folder === 'Album/Trip').click();
assert.match(elements.summary.textContent, /^1 of 3 pictures.*Album\/Trip$/);
assert.equal(elements.grid.children.length, 1);
elements.grid.children[0].children[0].click();
assert.equal(elements.viewerName.textContent, 'inside.png');
assert.equal(elements.viewerPath.textContent, 'Album/Trip/inside.png');
assert.equal(elements.viewerImage.src, 'native://img1/inside.png');

const createElement = document.createElement;
document.createElement = () => { throw new Error('gallery rendering failed after folder selection'); };
window.onNativeFolder([
  { name: 'visible.jpg', path: 'visible.jpg', uri: 'native://img3/visible.jpg', size: 8, mime: 'image/jpeg' }
], 'eds');
document.createElement = createElement;
assert.match(elements.notice.textContent, /gallery rendering failed after folder selection/);

console.log('PASS: nested albums, folder filtering, EDS status, and photo viewer');
