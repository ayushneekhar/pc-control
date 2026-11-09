/** The YouTube link inside shared text (the YouTube app shares "Title https://youtu.be/…"), or null. */
export function youtubeLink(text: string): string | null {
  const m = text.match(/https?:\/\/(?:www\.|m\.|music\.)?(?:youtube\.com\/(?:watch\?[^\s]*v=|live\/|shorts\/)|youtu\.be\/)[A-Za-z0-9_-]{11}[^\s]*/);
  return m ? m[0] : null;
}
