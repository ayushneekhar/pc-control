// Crash reports from the previous runs (pc-native CrashReporter: Java stacks it wrote itself, Android's exit
// records for native crashes / ANRs with the tombstone) go to pc-api (POST /pc/api/crash) on the next start,
// then are acked so each one is sent once. Best effort: offline means they wait for the next start.
import { Platform } from 'react-native';
import { PcNative } from '../../modules/pc-native';
import { post } from './api';

let started = false;

export async function uploadCrashes(): Promise<void> {
  if (started || !PcNative) return;
  started = true;
  try {
    const list = await PcNative.crashPending();
    if (!list.length) return;
    const app = PcNative.otaBinaryTarget();
    const device = PcNative.deviceName();
    const done: string[] = [];
    for (const c of list) {
      try {
        await post('/pc/api/crash', { ...c, app, device, android: String(Platform.Version) }, 30000);
        done.push(c.id);
      } catch {
        break; // pc-api unreachable: try again next start
      }
    }
    if (done.length) PcNative.crashAck(done);
  } catch {
    // never let crash reporting break the app
  }
}
