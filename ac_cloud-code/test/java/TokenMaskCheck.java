import com.diegonmarcos.cloudcode.chat.TokenMask;

// test/test-chat.sh compiles the plugin's REAL TokenMask.java with this and runs it: one line per check.
public class TokenMaskCheck {
    static void check(boolean ok, String what) {
        System.out.println("CHECK " + (ok ? "ok " : "bad ") + what);
    }

    public static void main(String[] a) {
        String t = "or-test-0123456789abcdefWXYZ";
        String m = TokenMask.of(t);
        check(m.endsWith("WXYZ") && !m.contains("or-test") && !m.contains("0123"), "a long token shows only its last four characters");
        check(TokenMask.of("short-one").equals(TokenMask.MASK), "a short token shows nothing of itself");
        check(TokenMask.of(null).isEmpty() && TokenMask.of("  ").isEmpty(), "no token shows as nothing");
        check(TokenMask.of(t).length() == TokenMask.of(t + "0000000000").length(), "the mask does not reveal the length");
    }
}
