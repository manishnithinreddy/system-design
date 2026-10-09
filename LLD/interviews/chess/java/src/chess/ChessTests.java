package chess;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/** Tiny test runner (no JUnit, to keep "plain javac"). Run: java -cp out chess.ChessTests */
public class ChessTests {
    static int passed, failed;

    static void test(String name, Runnable body) {
        try { body.run(); passed++; System.out.println("  ok   " + name); }
        catch (Throwable t) { failed++; System.out.println("  FAIL " + name + " -> " + t); }
    }

    static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but was " + actual);
    }

    static void yes(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }

    static Position pos(String fen) { return Fen.parse(fen); }

    /** Destination squares of legal moves from one square, sorted, e.g. [a3, c3]. */
    static Set<String> targets(Position p, String from) {
        Set<String> out = new TreeSet<>();
        for (Move m : Rules.legal(p)) if (m.from() == Sq.parse(from)) out.add(Sq.name(m.to()));
        return out;
    }

    static boolean canPlay(Position p, String uci) {
        return Rules.legal(p).stream().anyMatch(m -> m.uci().equals(uci));
    }

    static Game game(String fen) { return new Game(new Player("W"), new Player("B"), fen); }

    public static void main(String[] args) {
        System.out.println("== Piece moves and blocking ==");
        test("start position has 20 legal moves", () -> eq(20, Rules.legal(pos(Fen.START)).size()));
        test("knight jumps over pieces", () -> eq(Set.of("a3", "c3"), targets(pos(Fen.START), "b1")));
        test("rook boxed in by own pieces has no moves", () -> eq(Set.of(), targets(pos(Fen.START), "a1")));
        test("rook stops at first enemy piece and captures it", () ->
                eq(Set.of("b4", "c4", "d4"), targets(pos("4k3/8/8/8/R2p4/8/8/4K3 w - - 0 1"), "a4").stream()
                        .filter(s -> s.endsWith("4")).collect(java.util.stream.Collectors.toCollection(TreeSet::new))));
        test("queen on an empty board has 27 moves", () -> eq(27, targets(pos("4k3/8/8/8/3Q4/8/8/4K3 w - - 0 1"), "d4").size()));
        test("bishop cannot jump over a blocker", () ->
                eq(Set.of("b2", "c3", "d4"), targets(pos("4k3/8/8/8/3p4/8/8/B3K3 w - - 0 1"), "a1")));
        test("pawn: one or two squares from start, only one after, none when blocked", () -> {
            eq(Set.of("e3", "e4"), targets(pos(Fen.START), "e2"));
            eq(Set.of("e4"), targets(pos("4k3/8/8/8/8/4P3/8/4K3 w - - 0 1"), "e3"));
            eq(Set.of(), targets(pos("4k3/8/8/8/8/4n3/4P3/4K3 w - - 0 1"), "e2"));
        });
        test("pawn captures diagonally only", () ->
                eq(Set.of("e5", "d5"), targets(pos("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1"), "e4")));

        System.out.println("== Check and pins ==");
        test("pinned piece cannot move (would expose king)", () -> {
            Position p = pos("4r1k1/8/8/8/8/8/4B3/4K3 w - - 0 1");
            eq(Set.of(), targets(p, "e2"));
            yes(Rules.pseudoLegal(p).stream().anyMatch(m -> m.from() == Sq.parse("e2")), "pseudo-legal should still list it");
        });
        test("king cannot step onto an attacked square", () ->
                yes(!targets(pos("7k/8/8/8/8/8/r7/4K3 w - - 0 1"), "e1").contains("e2"), "e2 is attacked by the rook"));
        test("validate gives a reason for each kind of failure", () -> {
            Position p = pos("4r1k1/8/8/8/8/8/4B3/4K3 w - - 0 1");
            eq("move would leave your king in check", Rules.validate(p, Sq.parse("e2"), Sq.parse("d3"), null).error());
            eq("there is no piece on c3", Rules.validate(p, Sq.parse("c3"), Sq.parse("c4"), null).error());
            eq("that piece belongs to the other player", Rules.validate(p, Sq.parse("e8"), Sq.parse("d8"), null).error());
            eq("BISHOP cannot move from e2 to e3", Rules.validate(p, Sq.parse("e2"), Sq.parse("e3"), null).error());
        });
        test("in check, only moves that resolve the check are legal", () -> {
            Position p = pos("4r2k/8/8/8/8/8/3B4/4K3 w - - 0 1");
            yes(Rules.inCheck(p, Color.WHITE), "rook gives check");
            Set<String> all = new TreeSet<>();
            for (Move m : Rules.legal(p)) all.add(m.uci());
            eq(Set.of("e1d1", "e1f1", "e1f2", "d2e3"), all);   // 3 king steps + the bishop blocking on e3
        });

        System.out.println("== Castling ==");
        test("both castles available when clear and safe", () -> {
            Position p = pos("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
            yes(canPlay(p, "e1g1") && canPlay(p, "e1c1"), "expected both castles");
        });
        test("cannot castle through an attacked square", () -> {
            Position p = pos("5r1k/8/8/8/8/8/8/R3K2R w KQ - 0 1");   // rook on f8 attacks f1
            yes(!canPlay(p, "e1g1"), "kingside crosses f1");
            yes(canPlay(p, "e1c1"), "queenside is unaffected");
        });
        test("cannot castle out of check", () -> {
            Position p = pos("4r2k/8/8/8/8/8/8/R3K2R w KQ - 0 1");
            yes(!canPlay(p, "e1g1") && !canPlay(p, "e1c1"), "king is in check");
        });
        test("cannot castle with a piece in between", () ->
                yes(!canPlay(pos("r3k2r/8/8/8/8/8/8/R3KB1R w KQkq - 0 1"), "e1g1"), "bishop on f1"));
        test("queenside castling allowed when only the rook's b-square is attacked", () ->
                yes(canPlay(pos("1r5k/8/8/8/8/8/8/R3K2R w KQ - 0 1"), "e1c1"), "b1 attacked is fine"));
        test("castling moves the rook too", () -> {
            Game g = game("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
            g.play("O-O");
            eq("r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1", g.fen());
        });
        test("castling right is lost for good once the rook moves", () -> {
            Game g = game("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
            g.play("Rg1"); g.play("Kd7"); g.play("Rh1"); g.play("Ke8");
            Position p = g.position();
            yes(!canPlay(p, "e1g1"), "rook went away and came back");
            yes(canPlay(p, "e1c1"), "queenside right is still held");
        });

        System.out.println("== En passant and promotion ==");
        test("en passant captures the pawn that is NOT on the target square", () -> {
            Game g = new Game(new Player("W"), new Player("B"));
            for (String m : List.of("e4", "a6", "e5", "d5")) g.play(m);
            eq("rnbqkbnr/1pp1pppp/p7/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3", g.fen());
            g.play("exd6");
            eq("rnbqkbnr/1pp1pppp/p2P4/8/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 3", g.fen());
        });
        test("en passant is only available immediately", () -> {
            Game g = new Game(new Player("W"), new Player("B"));
            for (String m : List.of("e4", "a6", "e5", "d5", "Nf3", "a5")) g.play(m);
            yes(!canPlay(g.position(), "e5d6"), "the chance has passed");
        });
        test("en passant that exposes the king along the rank is illegal", () ->
                yes(!canPlay(pos("7k/8/8/K2pP2r/8/8/8/8 w - d6 0 1"), "e5d6"), "both pawns leave rank 5"));
        test("promotion offers four pieces; undo gives the pawn back", () -> {
            Game g = game("8/P6k/8/8/8/8/8/K7 w - - 0 1");
            eq(4L, g.legalMoves().stream().filter(m -> m.from() == Sq.parse("a7")).count());
            String before = g.fen();
            g.play("a8=N");
            eq(PieceType.KNIGHT, g.position().at(Sq.parse("a8")).type());
            g.undo();
            eq(before, g.fen());
        });
        test("promotion defaults to a queen when none is given", () -> {
            Game g = game("8/P6k/8/8/8/8/8/K7 w - - 0 1");
            g.move(Sq.parse("a7"), Sq.parse("a8"), null);
            eq(PieceType.QUEEN, g.position().at(Sq.parse("a8")).type());
        });

        System.out.println("== Game end ==");
        test("fool's mate is checkmate", () -> {
            Game g = new Game(new Player("W"), new Player("B"));
            for (String m : List.of("f3", "e5", "g4", "Qh4#")) g.play(m);
            eq(Status.CHECKMATE, g.status());
            eq("1. f3 e5 2. g4 Qh4#", g.movetext());
            boolean threw = false;
            try { g.play("a3"); } catch (IllegalStateException e) { threw = true; }
            yes(threw, "no moves after the game is over");
        });
        test("stalemate: no legal move but not in check", () -> {
            Game g = game("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
            yes(!g.inCheck(), "not in check");
            eq(Status.STALEMATE, g.status());
        });
        test("threefold repetition via Zobrist hash", () -> {
            Game g = new Game(new Player("W"), new Player("B"));
            for (String m : List.of("Nf3", "Nf6", "Ng1", "Ng8")) g.play(m);
            eq(Status.ONGOING, g.status());                      // start position seen twice
            for (String m : List.of("Nf3", "Nf6", "Ng1", "Ng8")) g.play(m);
            eq(Status.DRAW_THREEFOLD, g.status());
        });
        test("fifty-move rule: 100 plies without capture or pawn move", () -> {
            Game g = game("4k3/8/8/8/8/8/8/R3K3 w - - 99 80");
            eq(Status.ONGOING, g.status());
            g.play("Ra2");
            eq(Status.DRAW_FIFTY_MOVE, g.status());
        });
        test("king vs king is a draw by insufficient material", () ->
                eq(Status.DRAW_INSUFFICIENT, game("4k3/8/8/8/8/8/8/4K3 w - - 0 1").status()));

        System.out.println("== Notation ==");
        test("SAN disambiguation: Rad1 / Rgd1; bare Rd1 is rejected", () -> {
            Game g = game("4k3/8/8/8/8/8/4K3/R5R1 w - - 0 1");
            eq("Rad1", Notation.toSan(g.position(), Notation.parse(g.position(), "a1d1")));
            eq("Rgd1", Notation.toSan(g.position(), Notation.parse(g.position(), "g1d1")));
            boolean threw = false;
            try { g.play("Rd1"); } catch (IllegalArgumentException e) { threw = true; }
            yes(threw, "ambiguous");
        });
        test("coordinate and SAN forms mean the same move", () -> {
            Game a = new Game(new Player("W"), new Player("B")), b = new Game(new Player("W"), new Player("B"));
            a.play("Nf3"); b.play("g1f3");
            eq(a.fen(), b.fen());
        });

        System.out.println("== Undo / redo ==");
        test("undo restores the exact position (FEN and hash), redo replays it", () -> {
            Game g = game("r3k2r/1P6/8/3pP3/8/8/8/R3K2R w KQkq d6 0 1");
            String startFen = g.fen(); long startHash = g.hash();
            // en passant, castling, a capture and a promotion all in one line
            for (String m : List.of("exd6", "O-O", "bxa8=Q", "Kg7", "O-O-O")) g.play(m);
            String endFen = g.fen(); long endHash = g.hash();
            while (g.undo()) { }
            eq(startFen, g.fen());
            eq(startHash, g.hash());
            while (g.redo()) { }
            eq(endFen, g.fen());
            eq(endHash, g.hash());
        });
        test("a new move clears the redo stack", () -> {
            Game g = new Game(new Player("W"), new Player("B"));
            g.play("e4"); g.undo(); g.play("d4");
            yes(!g.redo(), "nothing to redo");
        });
        test("undo also un-counts repetitions", () -> {
            Game g = new Game(new Player("W"), new Player("B"));
            for (int i = 0; i < 2; i++) for (String m : List.of("Nf3", "Nf6", "Ng1", "Ng8")) g.play(m);
            g.undo();
            eq(Status.ONGOING, g.status());
        });

        System.out.println("== Perft (counts of all legal move sequences) ==");
        test("perft start position depth 1..4 = 20, 400, 8902, 197281", () -> {
            Position p = pos(Fen.START);
            long[] expected = {20, 400, 8_902, 197_281};
            for (int d = 1; d <= 4; d++) eq(expected[d - 1], Perft.count(p, d));
            eq(Fen.START, Fen.format(p));   // make/unmake left the board untouched
        });
        test("perft 'Kiwipete' (castling, en passant, promotion, pins) depth 1..3 = 48, 2039, 97862", () -> {
            Position p = pos("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
            long[] expected = {48, 2_039, 97_862};
            for (int d = 1; d <= 3; d++) eq(expected[d - 1], Perft.count(p, d));
        });
        test("perft endgame with en-passant pins depth 1..4 = 14, 191, 2812, 43238", () -> {
            Position p = pos("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1");
            long[] expected = {14, 191, 2_812, 43_238};
            for (int d = 1; d <= 4; d++) eq(expected[d - 1], Perft.count(p, d));
        });

        System.out.println("== Clock ==");
        test("clock charges thinking time and adds the increment", () -> {
            long[] now = {0};
            LongSupplier t = () -> now[0];
            ChessClock c = new ChessClock(60_000, 2_000, t);
            c.start(Color.WHITE);
            now[0] = 5_000; c.press();                          // white thought 5 s, gets +2 s
            eq(57_000L, c.remainingMs(Color.WHITE));
            now[0] = 8_000;
            eq(57_000L, c.remainingMs(Color.BLACK));            // black has used 3 s so far
        });
        test("flag falls when time runs out", () -> {
            long[] now = {0};
            ChessClock c = new ChessClock(1_000, 0, () -> now[0]);
            c.start(Color.BLACK);
            yes(c.flagged() == null, "not yet");
            now[0] = 1_000;
            eq(Color.BLACK, c.flagged());
        });

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
