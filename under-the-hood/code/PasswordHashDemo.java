import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Why password hashes must be slow: fast SHA-256 vs PBKDF2 at growing cost. Run: java PasswordHashDemo.java */
public class PasswordHashDemo {
    static final char[] PASSWORD = "hunter2".toCharArray();

    public static void main(String[] args) throws Exception {
        // 1. Same password, different salt -> different stored hash.
        System.out.println("== 1. Salt: same password, three users ==");
        SecureRandom rnd = new SecureRandom();
        for (int i = 1; i <= 3; i++) {
            byte[] salt = new byte[16];
            rnd.nextBytes(salt);
            System.out.println("user" + i + " salt=" + HexFormat.of().formatHex(salt, 0, 4) + "..  hash="
                    + HexFormat.of().formatHex(pbkdf2(salt, 1000), 0, 12) + "..");
        }

        // 2. Speed: plain SHA-256 vs PBKDF2 at several iteration counts.
        System.out.println("\n== 2. Guesses per second on ONE core ==");
        byte[] salt = new byte[16];
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < 200_000; i++) sha.digest(("warm" + i).getBytes());   // JIT warm-up
        int n = 2_000_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) sha.digest(("guess" + i).getBytes());
        report("plain SHA-256", n, System.nanoTime() - t0);

        for (int iter : new int[] {1_000, 100_000, 600_000}) {
            pbkdf2(salt, iter);                                                 // warm-up
            int runs = Math.max(3, 300_000 / iter);
            t0 = System.nanoTime();
            for (int i = 0; i < runs; i++) pbkdf2(salt, iter);
            report("PBKDF2-SHA256 x " + iter, runs, System.nanoTime() - t0);
        }
    }

    static void report(String label, int count, long nanos) {
        double perSec = count / (nanos / 1e9);
        double days = 1e9 / perSec / 86_400;
        System.out.printf("%-24s %,14.0f guesses/s   1 billion guesses: %s%n", label, perSec,
                days < 1 ? String.format("%,.0f seconds", days * 86_400) : String.format("%,.1f days (%.1f years)", days, days / 365));
    }

    static byte[] pbkdf2(byte[] salt, int iterations) throws Exception {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(PASSWORD, salt, iterations, 256)).getEncoded();
    }
}
