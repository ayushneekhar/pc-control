// Patches react-native-enriched-markdown's Android EnrichedMarkdownTextInputView during `expo prebuild`
// so `onTextContextMenuItem(android.R.id.paste)` does not coerce clipboard image URIs (`content://...`)
// into literal text via `item.coerceToText(context)` and instead falls through to `super.onTextContextMenuItem(id)`
// so `ViewCompat.setOnReceiveContentListener` (PcNative.attachImagePaste) receives the image clip.
const fs = require('node:fs');
const path = require('node:path');
const { withDangerousMod } = require('expo/config-plugins');

const RELATIVE_TARGET =
  'node_modules/react-native-enriched-markdown/android/src/main/java/com/swmansion/enriched/markdown/input/EnrichedMarkdownTextInputView.kt';

const MARKER = 'clipboardHasReceiveContentUri';

const PASTE_TARGET = `  override fun onTextContextMenuItem(id: Int): Boolean {
    if (id == android.R.id.paste) {`;

const PASTE_REPLACEMENT = `  override fun onTextContextMenuItem(id: Int): Boolean {
    if (id == android.R.id.paste) {
      if (clipboardHasReceiveContentUri()) {
        return super.onTextContextMenuItem(id)
      }`;

const HELPER_TARGET = `  private fun plainTextFromClipboard(): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
    return item.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
  }`;

const HELPER_REPLACEMENT = `${HELPER_TARGET}

  private fun clipboardHasReceiveContentUri(): Boolean {
    val mimeTypes = androidx.core.view.ViewCompat.getOnReceiveContentMimeTypes(this) ?: return false
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    val clip = clipboard.primaryClip ?: return false
    val desc = clip.description
    if (!mimeTypes.any { desc.hasMimeType(it) }) return false
    for (i in 0 until clip.itemCount) {
      val uri = clip.getItemAt(i)?.uri
      if (
        uri != null &&
        (uri.scheme == android.content.ContentResolver.SCHEME_CONTENT || uri.scheme == android.content.ContentResolver.SCHEME_FILE)
      ) {
        return true
      }
    }
    return false
  }`;

function patchSource(src) {
  if (src.includes(MARKER)) return src;
  if (!src.includes(PASTE_TARGET)) {
    throw new Error('withEnrichedMarkdownImagePaste: could not find onTextContextMenuItem paste block');
  }
  if (!src.includes(HELPER_TARGET)) {
    throw new Error('withEnrichedMarkdownImagePaste: could not find plainTextFromClipboard helper');
  }
  return src.replace(PASTE_TARGET, PASTE_REPLACEMENT).replace(HELPER_TARGET, HELPER_REPLACEMENT);
}

module.exports = function withEnrichedMarkdownImagePaste(config) {
  return withDangerousMod(config, [
    'android',
    async (cfg) => {
      const targetPath = path.join(cfg.modRequest.projectRoot, RELATIVE_TARGET);
      if (!fs.existsSync(targetPath)) {
        throw new Error(`withEnrichedMarkdownImagePaste: missing file at ${targetPath}`);
      }
      const before = fs.readFileSync(targetPath, 'utf8');
      const after = patchSource(before);
      if (after !== before) {
        fs.writeFileSync(targetPath, after, 'utf8');
      }
      return cfg;
    },
  ]);
};

module.exports.patchSource = patchSource;
