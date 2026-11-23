import { PcNative } from '../../modules/pc-native';
import { ApiError, post, RootRequest } from './api';
import { applyRequest } from './rootStore';
import { argvLooksSafe, commandLine, Decision } from './rootPayload';

export { commandLine };

export type DecideResult = { ok: true } | { ok: false; cancelled?: boolean; reenroll?: boolean; message: string };

/**
 * Sign a decision for the request AS SHOWN. Native Kotlin builds the rootd-v2 payload and renders
 * BiometricPrompt from the exact same fields so JS cannot decouple what is shown from what is signed.
 */
export async function decide(req: RootRequest, decision: Decision): Promise<DecideResult> {
  if (!PcNative) return { ok: false, message: 'Native module missing (old build?)' };
  const fp = PcNative.getSettings().deviceFingerprint;
  if (!fp || !PcNative.keyInfo()) return { ok: false, reenroll: true, message: "This phone isn't set up for root approvals yet. Open Settings > Root approvals." };
  if (req.expires * 1000 <= Date.now()) return { ok: false, message: 'This request already expired.' };
  if (!argvLooksSafe(req.argv)) {
    return { ok: false, message: 'Refusing to sign: argv contains characters that cannot be shown faithfully' };
  }
  let signature: string;
  try {
    signature = await PcNative.approveRoot({
      decision,
      id: req.id,
      nonce: req.nonce,
      expires: req.expires,
      argv: req.argv,
      reason: req.reason || '',
      timeout: req.timeout,
      snapSha256: req.snap_sha256 || '',
    });
  } catch (e: any) {
    const code = e?.code as string | undefined;
    if (code === 'E_CANCELLED') return { ok: false, cancelled: true, message: 'Cancelled' };
    if (code === 'E_KEY_INVALIDATED' || code === 'E_NO_KEY')
      return { ok: false, reenroll: true, message: e?.message ?? 'Approval key is gone. Re-enroll this phone.' };
    if (code === 'E_LOCKOUT') return { ok: false, message: 'Too many attempts. Unlock your phone with your PIN, then try again.' };
    return { ok: false, message: e?.message ?? String(e) };
  }
  try {
    const res = await post<{ ok: boolean; request?: RootRequest }>(`/pc/api/root/${decision}`, {
      id: req.id,
      device: fp,
      signature,
    });
    if (res.request) applyRequest(res.request);
    return { ok: true };
  } catch (e) {
    const err = e as ApiError;
    if (err.status === 403)
      return { ok: false, reenroll: true, message: 'rootd rejected the signature. Is this phone still enrolled? (sudo rootd-admin devices)' };
    return { ok: false, message: err.message };
  }
}
