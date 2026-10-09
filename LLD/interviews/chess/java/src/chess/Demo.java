package chess;

import java.util.List;

public class Demo {
    public static void main(String[] args) {
        Game g = new Game(new Player("Asha"), new Player("Ben"));
        System.out.println("> fool's mate: f3 e5 g4 Qh4#");
        for (String m : List.of("f3", "e5", "g4")) { System.out.println(g.toMove().name() + " plays " + m); g.play(m); }
        Rules.Validation bad = g.move(Sq.parse("g4"), Sq.parse("g5"), null);
        System.out.println("Ben tries to move White's pawn g4-g5: " + bad.error());
        bad = g.move(Sq.parse("d8"), Sq.parse("d4"), null);
        System.out.println("Ben tries Qd8-d4 (own pawn d7 in the way): " + bad.error());
        g.play("Qh4#");
        System.out.println("status: " + g.status() + "   movetext: " + g.movetext());
        System.out.println("FEN: " + g.fen());
        g.undo();
        System.out.println("after undo: " + g.status() + "   FEN: " + g.fen());

        Game g2 = new Game(new Player("Asha"), new Player("Ben"), "4k3/8/8/8/8/8/4r3/4K3 w - - 0 1");
        Rules.Validation v = g2.move(Sq.parse("e1"), Sq.parse("d2"), null);
        System.out.println("\n> king steps to d2 (rook on e2 guards the whole rank): " + (v.ok() ? "ok" : v.error()));
        v = g2.move(Sq.parse("e1"), Sq.parse("e2"), null);
        System.out.println("> king captures the undefended rook: " + (v.ok() ? "ok" : v.error()));

        System.out.println("\n> perft from the start position");
        Position p = Fen.parse(Fen.START);
        for (int d = 1; d <= 4; d++) {
            long t0 = System.nanoTime();
            long n = Perft.count(p, d);
            System.out.printf("  depth %d: %,d positions  (%d ms)%n", d, n, (System.nanoTime() - t0) / 1_000_000);
        }
    }
}
