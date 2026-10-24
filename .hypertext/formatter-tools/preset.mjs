// Load the existing Studio style authority from the prepared formatter installation.
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

const tools = path.dirname(fileURLToPath(import.meta.url));
const name = '@thehypertextstudio/format-config';
const json = async (filename) => JSON.parse(await readFile(filename, 'utf8'));
const exact = (value) => typeof value === 'string' && /^\d+\.\d+\.\d+$/.test(value);
let expected;
let prettierVersion;
let anchor;
try {
  // A cache-backed formatter installation must still match its committed recipe.
  const spec = await json(path.join(tools, 'tools.json'));
  const runtime = await json(path.join(tools, 'runtime.json'));
  if (!isDeepStrictEqual(spec, runtime.spec))
    throw Error('Formatter pins changed; rerun hook setup');
  expected = spec.npm[name];
  prettierVersion = runtime.prettier;
  anchor = path.join(runtime.npm, '../package.json');
} catch (error) {
  // Only an absent tools.json selects the generic repository-local installation.
  // A broken or unprepared cache must never fall back to another installation.
  if (error.code !== 'ENOENT' || error.path !== path.join(tools, 'tools.json')) throw error;
  anchor = path.join(tools, 'package.json');
  const manifest = await json(anchor).catch(async (error) => {
    if (error.code !== 'ENOENT' || error.path !== anchor) throw error;
    // Reuse a repository's existing dependency owner instead of another manifest.
    anchor = path.resolve(tools, '../../package.json');
    return json(anchor);
  });
  expected = manifest.devDependencies?.[name] ?? manifest.dependencies?.[name];
  prettierVersion = manifest.devDependencies?.prettier ?? manifest.dependencies?.prettier;
}
if (!exact(expected) || !exact(prettierVersion))
  throw Error('Run Studio setup: shared preset and Prettier require exact pins');
const require = createRequire(anchor);

async function metadata(filename, packageName) {
  let folder = path.dirname(filename);
  while (true) {
    const candidate = await json(path.join(folder, 'package.json')).catch((error) => {
      if (error.code === 'ENOENT') return null;
      throw error;
    });
    if (candidate?.name === packageName) return candidate;
    const parent = path.dirname(folder);
    if (parent === folder) throw Error('Cannot identify installed package: ' + packageName);
    folder = parent;
  }
}
const entry = require.resolve(name + '/prettier');
const config = await metadata(entry, name);
const prettier = await metadata(require.resolve('prettier'), 'prettier');
if (config.version !== expected)
  throw Error('Run Studio setup: shared preset version differs from its pin');
if (prettier.version !== prettierVersion || config.peerDependencies?.prettier !== prettierVersion)
  throw Error('Run Studio setup: shared preset requires its exact Prettier peer');
export default (await import(pathToFileURL(entry))).default;
