// Release signing from env (PCC_KEYSTORE, PCC_KEY_ALIAS, PCC_STORE_PASSWORD, PCC_KEY_PASSWORD), set by
// scripts/build-apk.sh from ~/.config/pc-control/keystore.env. No secrets ever land in the repo or android/.
const { withAppBuildGradle } = require('expo/config-plugins');

const RELEASE_CONFIG = `
        release {
            def ks = System.getenv("PCC_KEYSTORE")
            if (ks) {
                storeFile file(ks)
                storePassword System.getenv("PCC_STORE_PASSWORD")
                keyAlias System.getenv("PCC_KEY_ALIAS")
                keyPassword System.getenv("PCC_KEY_PASSWORD")
            }
        }`;

module.exports = function withReleaseSigning(config) {
  return withAppBuildGradle(config, (cfg) => {
    let g = cfg.modResults.contents;
    if (!g.includes('PCC_KEYSTORE')) {
      g = g.replace(/signingConfigs\s*\{/, (m) => m + RELEASE_CONFIG);
      // buildTypes.release: use the release config (unsigned without the env; build-apk.sh verifies the cert)
      g = g.replace(/(buildTypes\s*\{[\s\S]*?release\s*\{[\s\S]*?)signingConfig signingConfigs\.debug/, (m, pre) =>
        pre + 'signingConfig System.getenv("PCC_KEYSTORE") ? signingConfigs.release : null');
    }
    cfg.modResults.contents = g;
    return cfg;
  });
};
