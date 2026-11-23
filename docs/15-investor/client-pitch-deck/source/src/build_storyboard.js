// Prints the storyboard (markdown) from script_content.js.  node src/build_storyboard.js > out/BeatMind_AI_Storyboard.md
const S = require('./script_content');
const out = ['# BeatMind AI · Client pitch deck storyboard', '',
  'Generated from `src/script_content.js`, the single source for the speaker notes, the presenter script and this storyboard.', '',
  '| # | Act | Title | Key message | Time |', '|---|---|---|---|---|',
  ...S.map(s => `| ${s.n} | ${s.act} | ${s.title} | ${s.key} | ${s.duration} |`), ''];
for (const s of S) out.push(`## ${s.n}. ${s.title}`, '', `**Act:** ${s.act}  `, `**Objective:** ${s.objective}  `, `**Key message:** ${s.key}`, '',
  `**Visual:** ${s.visual}`, '', `**Layout:** ${s.layout}`, '', `**Motion:** ${s.animation} Transition: ${s.transition}`, '', `**Duration:** ${s.duration}`, '');
console.log(out.join('\n'));
