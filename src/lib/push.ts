// Push notifications: Firebase Cloud Messaging token, registered with pc-api over the tailnet.
// The native PushService also re-registers by itself whenever FCM rotates the token.
import { PermissionsAndroid, Platform } from 'react-native';
import { PcNative } from '../../modules/pc-native';

export async function askNotificationPermission(): Promise<boolean> {
  if (!PcNative) return false;
  if (PcNative.notificationsEnabled()) return true;
  if (Platform.OS === 'android' && Number(Platform.Version) >= 33) {
    const r = await PermissionsAndroid.request('android.permission.POST_NOTIFICATIONS' as any);
    return r === PermissionsAndroid.RESULTS.GRANTED;
  }
  return false;
}

export type PushResult = { ok: true; token: string } | { ok: false; error: string };

export async function registerPush(): Promise<PushResult> {
  if (!PcNative) return { ok: false, error: 'Native module missing' };
  try {
    const r = await PcNative.pushRegister();
    return r.registered ? { ok: true, token: r.token } : { ok: false, error: r.error ?? 'The laptop did not accept the token' };
  } catch (e) {
    return { ok: false, error: (e as Error).message };
  }
}
