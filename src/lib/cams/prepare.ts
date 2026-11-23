import { PcNative } from '../../../modules/pc-native';

let prepared = false;

/** WebRTC options + crash marker, once, before react-native-webrtc is first required. */
export function prepareCamsOnce() {
  if (prepared) return;
  prepared = true;
  try {
    PcNative?.camsPrepare();
  } catch {
    /* keep the defaults */
  }
}
