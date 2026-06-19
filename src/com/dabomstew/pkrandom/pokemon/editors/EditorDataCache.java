package com.dabomstew.pkrandom.pokemon.editors;

import com.dabomstew.pkromio.gamedata.MoveLearnt;
import com.dabomstew.pkromio.gamedata.Species;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import java.util.List;
import java.util.Map;

/**
 * Per-RomHandler shared cache of the editable move/compatibility maps.
 *
 * The RomHandler getters ({@code getMovesLearnt()}, {@code getEggMoves()},
 * {@code getTMHMCompatibility()}, {@code getMoveTutorCompatibility()}) each
 * allocate a fresh detached structure on every call. If the card view and the
 * sheet panels each call them separately they end up editing independent
 * copies, which causes "last writer wins" on save.
 *
 * This cache hands every panel the SAME instance per data type (fetched lazily
 * once), so edits in any panel are seen live by every other panel and a single
 * setter call persists them. It mirrors {@link PokemonIconCache}'s per-handler
 * singleton: when a different ROM is loaded, a new cache replaces the old one.
 */
public final class EditorDataCache {

    private static EditorDataCache current;

    private final RomHandler romHandler;
    private Map<Integer, List<MoveLearnt>> movesLearnt;
    private Map<Integer, List<Integer>> eggMoves;
    private Map<Species, boolean[]> tmhmCompatibility;
    private Map<Species, boolean[]> moveTutorCompatibility;

    private EditorDataCache(RomHandler romHandler) {
        this.romHandler = romHandler;
    }

    public static synchronized EditorDataCache get(RomHandler romHandler) {
        if (current == null || current.romHandler != romHandler) {
            current = new EditorDataCache(romHandler);
        }
        return current;
    }

    /**
     * Drops the cached handler and its data maps. Call this when the loaded ROM is replaced
     * (new ROM / preset / post-randomize reload) so the previous {@link RomHandler} and its
     * cached structures can be garbage-collected instead of being pinned until the next editor
     * opens. A later editor lazily rebuilds the cache against the current ROM.
     */
    public static synchronized void clear() {
        current = null;
    }

    public synchronized Map<Integer, List<MoveLearnt>> getMovesLearnt() {
        if (movesLearnt == null) {
            movesLearnt = romHandler.getMovesLearnt();
        }
        return movesLearnt;
    }

    public synchronized Map<Integer, List<Integer>> getEggMoves() {
        if (eggMoves == null) {
            eggMoves = romHandler.getEggMoves();
        }
        return eggMoves;
    }

    public synchronized Map<Species, boolean[]> getTMHMCompatibility() {
        if (tmhmCompatibility == null) {
            tmhmCompatibility = romHandler.getTMHMCompatibility();
        }
        return tmhmCompatibility;
    }

    /** Returns the shared tutor compatibility map, or null if the ROM has no tutors. */
    public synchronized Map<Species, boolean[]> getMoveTutorCompatibility() {
        if (moveTutorCompatibility == null && romHandler.hasMoveTutors()) {
            moveTutorCompatibility = romHandler.getMoveTutorCompatibility();
        }
        return moveTutorCompatibility;
    }
}
