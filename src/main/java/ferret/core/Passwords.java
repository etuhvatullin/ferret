package ferret.core;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// Паролі підключень — власний зашифрований файл, як у Swift-Ferret (і в DataGrip на Windows): passwords.enc — AES-GCM у
// форматі CryptoKit «nonce (12 байт) ‖ шифр ‖ тег (16 байт)», ключ — passwords.key (32 байти) поруч; обидва — лише для
// власника. Усередині — JSON {"id підключення": "пароль"}. Той самий формат — тож файл Swift-версії читається як є.
// Захист — як у будь-якого файлу в домашній теці користувача (на Windows сильніший варіант, DPAPI, — у беклозі).
public final class Passwords {
    private Passwords() { }

    private static Map<String, String> loaded;

    static Path fileURL() { return Store.file("passwords.enc"); }

    static Path keyURL() { return Store.file("passwords.key"); }

    public static synchronized String get(UUID id) { return load().get(id.toString().toUpperCase()); }

    public static synchronized boolean set(UUID id, String password) {
        Map<String, String> all = new LinkedHashMap<>(load());
        String k = id.toString().toUpperCase();
        if (password == null || password.isEmpty()) all.remove(k); else all.put(k, password);
        return save(all);
    }

    public static void remove(UUID id) { set(id, ""); }

    // Перечитати файл (для перевірок — «як після перезапуску»).
    public static synchronized void reload() { loaded = null; }

    private static byte[] key(boolean create) throws IOException {
        Path k = keyURL();
        if (Files.exists(k)) {
            byte[] b = Files.readAllBytes(k);
            if (b.length == 32) return b;
        }
        if (!create) return null;
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        Store.writeAtomic(k, b, true);
        return b;
    }

    private static Map<String, String> load() {
        if (loaded != null) return loaded;
        Map<String, String> all = new LinkedHashMap<>();
        Path f = fileURL();
        if (Files.exists(f)) {
            try {
                byte[] k = key(false);
                if (k == null) throw new IOException("немає ключа");
                for (Map.Entry<String, Object> e : Json.map(Json.parse(new String(open(Files.readAllBytes(f), k), StandardCharsets.UTF_8))).entrySet()) {
                    if (e.getValue() != null) all.put(e.getKey().toUpperCase(), String.valueOf(e.getValue()));
                }
            } catch (Exception e) {
                // не розшифровується (зник ключ?) — відкласти вбік, а не перезаписати поверх
                try { Files.move(f, f.resolveSibling("passwords.enc.unreadable-" + System.currentTimeMillis() / 1000)); } catch (IOException ignored) { }
            }
        }
        loaded = all;
        return all;
    }

    private static boolean save(Map<String, String> all) {
        try {
            byte[] k = key(true);
            Store.writeAtomic(fileURL(), seal(Json.write(all).getBytes(StandardCharsets.UTF_8), k), true);
            loaded = all;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] seal(byte[] plain, byte[] key) throws Exception {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] body = c.doFinal(plain);   // шифр ‖ тег — як у CryptoKit
        byte[] out = Arrays.copyOf(nonce, 12 + body.length);
        System.arraycopy(body, 0, out, 12, body.length);
        return out;
    }

    static byte[] open(byte[] combined, byte[] key) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, combined, 0, 12));
        return c.doFinal(combined, 12, combined.length - 12);
    }
}
