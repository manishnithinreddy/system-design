import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.util.*;

/** TOY double ratchet (Signal-style idea). NOT secure, NOT the real protocol. Run: java RatchetDemo.java */
public class RatchetDemo {
    static byte[] hmac(byte[] key, String label) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(label.getBytes());
    }
    static byte[] dh(KeyPair mine, PublicKey theirs) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(mine.getPrivate()); ka.doPhase(theirs, true);
        return ka.generateSecret();
    }
    static KeyPair newPair() throws Exception { return KeyPairGenerator.getInstance("X25519").generateKeyPair(); }
    static String fp(byte[] k) { return HexFormat.of().formatHex(k, 0, 4); }  // first 8 hex chars

    /** One side's state: root key, sending/receiving chain keys, current DH pair. */
    static class Party {
        String name; byte[] root, sendCk, recvCk; KeyPair dhPair; PublicKey theirDh;
        Party(String n, byte[] root) { name = n; this.root = root; }
        /** DH ratchet step: mix a fresh DH output into the root key -> new chain key. */
        byte[] dhStep() throws Exception {
            byte[] out = dh(dhPair, theirDh);
            byte[] mixed = hmac(root, HexFormat.of().formatHex(out));
            root = hmac(mixed, "root"); return hmac(mixed, "chain");
        }
        /** Symmetric ratchet: message key = f(chain), next chain = g(chain); old chain key is overwritten. */
        byte[] nextMessageKey(boolean sending) throws Exception {
            byte[] ck = sending ? sendCk : recvCk;
            byte[] mk = hmac(ck, "msg"); byte[] next = hmac(ck, "chain");
            if (sending) sendCk = next; else recvCk = next;
            return mk;
        }
    }
    static byte[] aes(int mode, byte[] mk, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(mk, 0, 16, "AES"), new GCMParameterSpec(128, new byte[12])); // toy: fixed nonce is OK only because each key is used once
        return c.doFinal(data);
    }
    static String tryDecrypt(byte[] mk, byte[] ct) {
        try { return "READ: \"" + new String(aes(Cipher.DECRYPT_MODE, mk, ct)) + "\""; }
        catch (Exception e) { return "cannot decrypt"; }
    }

    public static void main(String[] a) throws Exception {
        byte[] shared = hmac("pretend-X3DH-result".getBytes(), "seed");   // stand-in for X3DH
        Party alice = new Party("Alice", shared), bob = new Party("Bob", shared);
        bob.dhPair = newPair(); alice.theirDh = bob.dhPair.getPublic();
        alice.dhPair = newPair(); bob.theirDh = alice.dhPair.getPublic();
        alice.sendCk = alice.dhStep(); bob.recvCk = bob.dhStep();        // both derive the same first chain
        bob.dhPair = newPair(); alice.theirDh = bob.dhPair.getPublic();   // placeholder, replaced on reply below

        byte[] stolenChainKey = null; List<byte[]> oldCts = new ArrayList<>();
        // Alice sends 3 messages on one chain
        for (String t : new String[]{"hi Bob", "are you there?", "lunch at 1?"}) {
            byte[] mk = alice.nextMessageKey(true); byte[] ct = aes(Cipher.ENCRYPT_MODE, mk, t.getBytes());
            byte[] mkB = bob.nextMessageKey(false);
            System.out.printf("Alice->Bob key=%s  bob decrypts: %s%n", fp(mk), tryDecrypt(mkB, ct));
            oldCts.add(ct);
        }
        // ATTACK: attacker steals Bob's current receiving chain key (a snapshot of device memory)
        stolenChainKey = bob.recvCk.clone();
        System.out.println("-- attacker steals Bob's chain key " + fp(stolenChainKey) + " --");
        for (byte[] ct : oldCts) {  // past keys were derived forward only; stolen key cannot go backwards
            byte[] guess = hmac(stolenChainKey, "msg");
            System.out.println("  old message with stolen-key-derived guess " + fp(guess) + ": " + tryDecrypt(guess, ct));
        }
        // Next Alice message, still the same chain: attacker can follow the chain forward
        byte[] mk = alice.nextMessageKey(true); byte[] ct = aes(Cipher.ENCRYPT_MODE, mk, "my PIN is 4321".getBytes());
        System.out.println("  NEXT message (same chain) key=" + fp(mk) + " attacker: " + tryDecrypt(hmac(stolenChainKey, "msg"), ct));
        bob.nextMessageKey(false);

        // Bob replies: DH ratchet step (new key pairs on both sides)
        bob.dhPair = newPair(); alice.theirDh = bob.dhPair.getPublic(); bob.theirDh = alice.dhPair.getPublic();
        bob.sendCk = bob.dhStep(); alice.recvCk = alice.dhStep();
        byte[] rk = bob.nextMessageKey(true); byte[] rct = aes(Cipher.ENCRYPT_MODE, rk, "ok, 1pm".getBytes());
        System.out.printf("Bob->Alice (DH ratchet) key=%s  alice decrypts: %s%n", fp(rk), tryDecrypt(alice.nextMessageKey(false), rct));
        // Alice replies with another new DH pair
        alice.dhPair = newPair(); bob.theirDh = alice.dhPair.getPublic();
        alice.sendCk = alice.dhStep(); bob.recvCk = bob.dhStep();
        mk = alice.nextMessageKey(true); ct = aes(Cipher.ENCRYPT_MODE, mk, "great, see you".getBytes());
        byte[] attackerKey = hmac(hmac(stolenChainKey, "chain"), "msg"); // attacker keeps ratcheting the stolen chain
        System.out.println("Alice->Bob after 2nd DH step key=" + fp(mk));
        System.out.println("  attacker (stolen chain): " + tryDecrypt(attackerKey, ct) + "   <- healed");
    }
}
