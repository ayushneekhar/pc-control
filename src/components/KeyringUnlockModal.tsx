import { useState } from 'react';
import { KeyboardAvoidingView, Modal, Platform, Pressable, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { PcNative } from '../../modules/pc-native';
import { keyringVaultSupported, unlockKeyringManual } from '../lib/keyring';
import { radius, space, useTheme } from '../theme';
import { Banner, Button, Icon } from './ui';

/**
 * One-time password prompt used when unlocking the laptop's GNOME keyring without a saved password.
 * Unmounts its form state when closed and clears the password immediately on submit or cancel.
 */
export function KeyringUnlockModal({
  visible,
  onClose,
}: {
  visible: boolean;
  onClose: () => void;
}) {
  if (!visible) return null;
  return <KeyringUnlockModalBody onClose={onClose} />;
}

function KeyringUnlockModalBody({ onClose }: { onClose: () => void }) {
  const t = useTheme();
  const [pw, setPw] = useState('');
  const [saveOnPhone, setSaveOnPhone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const canSave = keyringVaultSupported() && PcNative?.biometricStatus?.() === 'ok';

  const close = () => {
    if (busy) return;
    setPw('');
    setErr(null);
    onClose();
  };

  const submit = async () => {
    if (busy) return;
    let entered: string | null = pw;
    setPw('');
    if (!entered) {
      setErr('Enter your keyring password.');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      const res = await unlockKeyringManual(entered, canSave && saveOnPhone);
      if (res.ok) {
        onClose();
      } else {
        setErr(res.message);
      }
    } finally {
      entered = null;
      setBusy(false);
    }
  };

  return (
    <Modal visible transparent animationType="fade" onRequestClose={close}>
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={st.backdrop}>
        <Pressable style={StyleSheet.absoluteFill} onPress={close} />
        <View style={[st.sheet, { backgroundColor: t.card }]}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(2.5) }}>
            <Icon name="lock-open-outline" size={22} color={t.accent} />
            <Text style={{ color: t.text, fontSize: 17, fontWeight: '600', flex: 1 }}>Unlock keyring</Text>
          </View>
          <Text style={{ color: t.textDim, fontSize: 13.5, lineHeight: 19 }}>
            Enter your laptop login keyring password to unlock GNOME keyring and restart RDP.
          </Text>

          <TextInput
            value={pw}
            onChangeText={setPw}
            secureTextEntry
            autoCapitalize="none"
            autoCorrect={false}
            autoComplete="off"
            textContentType="none"
            editable={!busy}
            autoFocus
            onSubmitEditing={submit}
            placeholder="Keyring password"
            placeholderTextColor={t.textFaint}
            selectionColor={t.accent}
            style={[st.input, { color: t.text, backgroundColor: t.cardAlt, borderColor: t.border }]}
          />

          {canSave ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: space(3) }}>
              <View style={{ flex: 1 }}>
                <Text style={{ color: t.text, fontSize: 14, fontWeight: '500' }}>Save on this phone</Text>
                <Text style={{ color: t.textFaint, fontSize: 12 }}>Encrypted with your fingerprint for future reboots</Text>
              </View>
              <Switch
                value={saveOnPhone}
                onValueChange={setSaveOnPhone}
                disabled={busy}
                accessibilityLabel="Save keyring password on this phone"
                trackColor={{ true: t.accent, false: t.cardAlt }}
                thumbColor={saveOnPhone ? t.onMedia : t.textFaint}
              />
            </View>
          ) : null}

          {err ? <Banner tone="bad" title="Unlock failed">{err}</Banner> : null}

          <View style={{ flexDirection: 'row', gap: space(2), marginTop: space(1) }}>
            <Button label="Cancel" kind="subtle" style={{ flex: 1 }} disabled={busy} onPress={close} />
            <Button label="Unlock" icon="lock-open-outline" style={{ flex: 1 }} busy={busy} onPress={submit} />
          </View>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const st = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.6)',
    justifyContent: 'center',
    padding: space(5),
  },
  sheet: {
    borderRadius: radius.md,
    padding: space(4),
    gap: space(3),
    elevation: 10,
  },
  input: {
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
    paddingHorizontal: space(3),
    height: 46,
    fontSize: 14,
  },
});
