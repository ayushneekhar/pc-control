// The cat cams on the laptop's go2rtc (same names as the /cams/ page's NAMES map).
export type CamId = 'cat' | 'cat2' | 'cat3';

export const CAMS: { id: CamId; name: string }[] = [
  { id: 'cat', name: 'Living room' },
  { id: 'cat2', name: 'Hall' },
  { id: 'cat3', name: 'Food bowl' },
];

export const NAMES: Record<string, string> = { cat: 'Living room', cat2: 'Hall', cat3: 'Food bowl', cat3hd: 'Food bowl' };

/** CP Plus: H.265 2304x1296 passthrough. Played only where the phone has a hardware HEVC decoder. */
export const HD_STREAM = 'cat3hd';
/** CP Plus mic (PCMA). Same source for HD and SD, so Listen survives HD/SD switches. */
export const CAM_MIC_STREAM = 'cat3hd';
/** Laptop mic (Opus). */
export const LAPTOP_MIC_STREAM = 'mic';
/** Phone mic -> laptop speakers (go2rtc backchannel). */
export const TALK_STREAM = 'talk';
/** No HD frame this long: fall back to SD (the first HD frame waits for a keyframe). */
export const HD_WAIT_MS = 12000;

/** The /cams/ web page (Replay, Music and anything else the app doesn't do natively). */
export const WEB_PATH = '/cams/';
