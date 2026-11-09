// Adds the Firebase config at build time from OUTSIDE the repo (~/.config/pc-control/google-services.json,
// or $PCC_GOOGLE_SERVICES). Without it the app still builds, but push notifications are unavailable.
const fs = require('fs');
const os = require('os');
const path = require('path');

module.exports = ({ config }) => {
  const gs = process.env.PCC_GOOGLE_SERVICES || path.join(os.homedir(), '.config', 'pc-control', 'google-services.json');
  if (fs.existsSync(gs)) config.android = { ...config.android, googleServicesFile: gs };
  else console.warn(`[pc-control] ${gs} not found: building without FCM push`);
  return config;
};
