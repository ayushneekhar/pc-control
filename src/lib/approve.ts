import { PcNative } from '../../modules/pc-native';
import { ApiError, post, RootRequest } from './api';
import { applyRequest } from './rootStore';
import { buildPayload, Decision, showArg } from './rootPayload';

export type DecideResult = { ok: true } | { ok: false; cancelled?: boolean; reenroll?: boolean; message: string };

export function commandLine(argv: string[]): string {
  return argv
    .map((a) => {
      const s = showArg(a);
      return s === '' || /[\s"'$`;&|<>(){}*?!#~]/.test(s) ? `"${s.replace(/"/g, '\\"')}"` : s;
    })
    .join(' ');
}

/**
 * Sign a decision for the request AS SHOWN (the same object the screen rendered) and relay it.
 * The payload is built here on the phone from req.argv; rootd recomputes it from the argv it stored.
 */
export async function decide(req: RootRequest, decision: Decision): Promise<DecideResult> {
  if (!PcNative) return { ok: false, message: 'Native module missing (old build?)' };
  const fp = PcNative.getSettings().deviceFingerprint;
  if (!fp || !PcNative.keyInfo()) return { ok: false, reenroll: true, message: "This phone isn't set up for root approvals yet. Open Settings > Root approvals." };
  if (req.expires * 1000 <= Date.now()) return { ok: false, message: 'This request already expired.' };
  let payload: string;
  try {
    payload = buildPayload(decision, req);
  } catch (e) {
    return { ok: false, message: `Refusing to sign: ${(e as Error).message}` };
  }
  const cmd = commandLine(req.argv);
  let signature: string;
  try {
    signature = await PcNative.sign(
      payload,
      decision === 'approve' ? 'Run as root?' : 'Deny this command?',
      cmd.length > 80 ? cmd.slice(0, 77) + '…' : cmd,
      (req.reason ? `Reason: ${req.reason}\n\n` : '') + (cmd.length > 80 ? cmd.slice(0, 600) : '') ,
    );
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
