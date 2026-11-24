// Native Android build & runtime optimizations preserved across `expo prebuild --clean`:
// 1. Gradle Build Cache + generous JVM heap/metaspace in android/gradle.properties.
// 2. Prebundle MaterialCommunityIcons.ttf into android/app/src/main/assets/fonts/material-community.ttf
//    so ReactFontManager + expo-font load it natively from the APK (Font.isLoaded('material-community') === true
//    on frame 1), eliminating the need to ship the ~1.1 MB font inside every Nitro OTA bundle.zip.
// 3. Forward EXPO_PUBLIC_BUNDLED_OTA_VERSION into config.extra.bundledOtaVersion so expo-constants bakes it
//    into the APK's app.config even when Metro reuses a warm transform cache.
const fs = require('node:fs');
const path = require('node:path');
const { withDangerousMod, withGradleProperties } = require('expo/config-plugins');

const GRADLE_PROPS = [
  { key: 'org.gradle.caching', value: 'true' },
  { key: 'org.gradle.jvmargs', value: '-Xmx4096m -XX:MaxMetaspaceSize=1024m' },
];

function setGradleProperty(props, key, value) {
  const existing = props.find((p) => p.type === 'property' && p.key === key);
  if (existing) {
    existing.value = value;
  } else {
    props.push({ type: 'property', key, value });
  }
}

module.exports = function withBuildOptimizations(config) {
  const bundledOta = process.env.EXPO_PUBLIC_BUNDLED_OTA_VERSION?.trim();
  if (bundledOta) {
    config.extra = { ...(config.extra ?? {}), bundledOtaVersion: bundledOta };
  }

  config = withGradleProperties(config, (cfg) => {
    for (const { key, value } of GRADLE_PROPS) {
      setGradleProperty(cfg.modResults, key, value);
    }
    return cfg;
  });

  config = withDangerousMod(config, [
    'android',
    async (cfg) => {
      const srcFont = path.join(
        cfg.modRequest.projectRoot,
        'node_modules/@expo/vector-icons/build/vendor/react-native-vector-icons/Fonts/MaterialCommunityIcons.ttf',
      );
      if (!fs.existsSync(srcFont)) {
        throw new Error(`withBuildOptimizations: missing font at ${srcFont}`);
      }
      const dstDir = path.join(cfg.modRequest.platformProjectRoot, 'app/src/main/assets/fonts');
      fs.mkdirSync(dstDir, { recursive: true });
      // Filename must match @expo/vector-icons' fontFamily ('material-community') so
      // ExpoFontLoader.queryCustomNativeFonts() and ReactFontManager resolve it natively.
      fs.copyFileSync(srcFont, path.join(dstDir, 'material-community.ttf'));
      return cfg;
    },
  ]);

  return config;
};
