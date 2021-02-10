const fs = require('fs');
const PO = require('pofile');

const FILES = [
  'resources/public/pebble_templates/index.html',
  'resources/public/pebble_templates/faq.html',
  'resources/public/pebble_templates/info.html',
  'resources/public/pebble_templates/profile.html',
  'resources/public/pebble_templates/40x.html'
];

const PEBBLE_COMMENT_MATCH = /{([#])((?!\1}).)*\1}/g;
const PEBBLE_CODE_MATCH = /{([%])((?!\1}).)*\1}|{{((?!}}).)*}}/g;

const I18N_MATCH = /i18n\s*[(]/g;
// a string matching regex, handles escapes and escaped escapes.
const STRING_MATCH = /(["'])((\\{2})*|(.*?[^\\](\\{2})*))\1/g;

const contract = (s, size) => s.slice(size, -size);
const shave = (s, size) => contract(s, size).trim();

const poFiles = new Map();

let stringCount = 0;

const offsetToLine = (text, offset) => Array.from(text.slice(0, offset).matchAll('\n')).length;
const globalOffsetToLocal = (text, offset) => offset - text.slice(0, offset).lastIndexOf('\n');

const itemsByIdByPofile = new Map();

for (const path of FILES) {
  const file = fs.readFileSync(path).toString();

  const commentsByLine = new Map();

  const comments = file.matchAll(PEBBLE_COMMENT_MATCH);

  for (const comment of comments) {
    const line = offsetToLine(file, comment.index);
    if (!commentsByLine.has(line)) {
      commentsByLine.set(line, []);
    }
    commentsByLine.get(line).push(shave(comment[0], 2));
  }

  const sections = file.matchAll(PEBBLE_CODE_MATCH);

  for (const section of sections) {
    const sectionOffset = section.index;
    const sectionLine = offsetToLine(file, section.index);

    const gettextCalls = section[0].matchAll(I18N_MATCH);

    for (const call of gettextCalls) {
      const callOffset = sectionOffset + call.index;
      const callLine = sectionLine + offsetToLine(section[0], call.index);
      const callLocalOffset = globalOffsetToLocal(file, callOffset);

      const params = [];

      let depth = 1;
      let callLength;

      for (let i = call.index + 5; i < section[0].length; i++) {
        let char = section[0][i];

        if (char === '\'' || char === '"') {
          const match = section[0].slice(i - 1).matchAll(STRING_MATCH).next();
          if (match.done) {
            throw new Error(`Template file is incorrect (unterminated string) at ${callLine}:${callLocalOffset}`);
          }
          i += match.value[0].length;
          params.push(contract(match.value[0], 1).replaceAll('\\\'', '\''));

          char = section[0][i];
        }

        if (char === '(') {
          depth++;
        } else if (char === ')') {
          depth--;
          if (depth === 0) {
            // Since we can calculate this, it's worth having
            // even if we don't currently use it.
            /* eslint-disable-next-line no-unused-vars */
            callLength = i;
            break;
          }
        }
      }

      const [poFileName, id] = params;

      if (!poFiles.has(poFileName)) {
        const poFile = new PO();
        poFiles.set(poFileName, poFile);
        poFile.headers['Project-Id-Version'] = 'Pxls';
        poFile.headers['POT-Creation-Date'] = (new Date()).toISOString();
        // "Better written as…" not it's not - look at the context.
        /* eslint-disable-next-line dot-notation */
        poFile.headers['Language'] = '';
        poFile.headers['Content-Type'] = 'text/plain; charset=UTF-8';

        itemsByIdByPofile.set(poFileName, new Map());
      }

      const poFile = poFiles.get(poFileName);

      const itemsById = itemsByIdByPofile.get(poFileName);

      if (!itemsById.has(id)) {
        stringCount++;
        const item = new PO.Item();
        itemsById.set(id, item);
        poFile.items.push(item);
      }

      const item = itemsById.get(id);

      item.msgid = id;
      item.references.push(`${path}:${callLine + 1}:${callLocalOffset + 1}`);

      if (commentsByLine.has(callLine - 1)) {
        item.extractedComments.push(...commentsByLine.get(callLine - 1));
      }

      if (commentsByLine.has(callLine)) {
        item.extractedComments.push(...commentsByLine.get(callLine));
      }
    }
  }
}

for (const [name, poFile] of poFiles.entries()) {
  poFile.save(`po/${name}.pot`, e => e ? console.error : null);
}

console.info(`Parsed ${FILES.length} files.`);
console.info(`${stringCount} strings found.`);
console.info(`Output ${poFiles.size} files.`);
