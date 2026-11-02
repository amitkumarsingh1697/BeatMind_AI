// User-perceived characters, so a Devanagari syllable counts once (REQ-F-08). The API counts the
// same way and has the final say; this only drives the counter under the text box.

export function countGraphemes(text: string): number {
  if (typeof Intl !== "undefined" && "Segmenter" in Intl) {
    let n = 0;
    for (const _ of new Intl.Segmenter(undefined, { granularity: "grapheme" }).segment(text)) n++;
    return n;
  }
  return Array.from(text).length;
}
