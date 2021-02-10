const fs = require('fs');
const path = require('path');
const childProcess = require('child_process');

const { promisify } = require('util');

const PO_DIR = 'po';
const OUTPUT_DIR = 'resources';

const dir = fs.readdirSync(PO_DIR);

const translations = dir.filter(f => f.endsWith('.po'))
  .map(f => f.slice(0, -3));

const processes = [];

const spawn = promisify(childProcess.exec);

for (const translation of translations) {
  const input = path.join(PO_DIR, `${translation}.po`);
  const output = path.join(OUTPUT_DIR, `${translation}.properties`);
  const command = `msgcat --properties-output ${input} -o ${output}`;
  processes.push(spawn(command).then(() => {
    // hacky fix
    // basically, msgcat double-escapes quotes for no good reason
    // this could have been handled with a sed in the original command,
    // but I'd rather this be slightly more system agnostic.
    fs.writeFileSync(output, fs.readFileSync(output).toString().replaceAll('\\\\"', '"'));
  }));
}

Promise.all(processes).then(() => {
  console.info(`Output ${translations.length} translations.`);
}).catch(console.error);
