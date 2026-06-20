package com.dabomstew.pkrandom.gui;

import com.dabomstew.pkrandom.log.ManualEditRegistry;
import com.dabomstew.pkromio.constants.Gen3Constants;
import com.dabomstew.pkromio.constants.Gen5Constants;
import com.dabomstew.pkromio.gamedata.Trainer;
import com.dabomstew.pkromio.gamedata.TrainerPokemon;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reshapes the level curve of the progression bosses (gym leaders -> Elite Four
 * -> champion), keyed on each milestone's ace (highest) level.
 *
 * Milestones and their order come from the ROM's trainer tags, which already
 * encode the per-version fight order (e.g. the Diamond/Pearl vs Platinum
 * Fantina/Maylene swap). Dual-region games (GSC/HGSS) are stitched as
 * gyms 1-8 -> league -> gyms 9-16 -> Red; BW's trio-gym variants (GYM9-11) are
 * folded back into gym 1.
 *
 * Three ways to edit:
 *  - Scale: multiply every gap from an anchor by a factor (proportional widen/narrow).
 *  - Normalize: force the sequence non-decreasing (minimal fix) or an even ramp.
 *  - Manual: type each milestone's target level (per-gap control).
 * Edits apply to the loaded trainers via setTrainers and are written on ROM save.
 */
class TrainerLevelCurveDialog extends JDialog {

    private static final Pattern GYM_LEADER = Pattern.compile("GYM(\\d+)-LEADER");
    // Tolerate a "-<round>" suffix: FireRed/LeafGreen tag the Elite Four as
    // ELITE1-1..ELITE4-1 (first clear) and ELITE1-2..ELITE4-2 (rematch). Without
    // this the whole E4 was silently dropped from the curve. Rounds fold together
    // because the chain is keyed on the captured Elite number.
    private static final Pattern ELITE = Pattern.compile("ELITE(\\d+)(?:-\\d+)?");
    // Evil-team leaders and mini-boss admins are tagged THEMED:<NAME>-LEADER (the
    // team boss: Maxie/Archie/Cyrus/Lysandre/Guzma/Lusamine) or THEMED:<NAME>-STRONG
    // (admins/mini-bosses: Courtney, Tabitha, Mars, Jupiter, Saturn, Zinzolin,
    // Colress, Wally, Gladion, trial captains, ...). The captured group is the
    // character name; the suffix decides the role label. Non-suffixed THEMED:<NAME>
    // tags (SPROUTTOWER, JESSIE&JAMES, SHADOW1, Gen2 ARIANA/PETREL/PROTON) are plain
    // type-theme groups, NOT bosses, so they are deliberately excluded by requiring
    // the -LEADER / -STRONG suffix (this matches Trainer.isBoss()/isImportant()).
    private static final Pattern THEMED_BOSS = Pattern.compile("THEMED:(.+?)-(LEADER|STRONG)");
    // Giovanni's NON-gym boss fights: GIO1/GIO2 (RBY/Yellow) and GIO1-LEADER/
    // GIO2-LEADER (FireRed/LeafGreen). These are distinct trainers from his Viridian
    // gym (GYM8-LEADER) and were previously dropped from the chain entirely.
    private static final Pattern GIOVANNI = Pattern.compile("GIO(\\d+)(?:-LEADER)?");

    private final RomHandler romHandler;
    private final List<Trainer> trainers;
    private final List<Milestone> chain = new ArrayList<Milestone>();

    private JSlider scaleSlider;
    private JLabel scaleLabel;
    private JCheckBox includeGymTrainers;
    private JCheckBox scaleAllTrainers;
    private final List<JSpinner> targetSpinners = new ArrayList<JSpinner>();
    private boolean adjusting;
    private boolean applied;

    TrainerLevelCurveDialog(Window owner, RomHandler romHandler) {
        super(owner, "Adjust Gym / Elite Four / Champion Level Curve", ModalityType.APPLICATION_MODAL);
        this.romHandler = romHandler;
        this.trainers = romHandler.getTrainers();
        buildChain();
        initUI();
        setMinimumSize(new Dimension(520, 480));
        pack();
        setLocationRelativeTo(owner);
    }

    boolean wasApplied() {
        return applied;
    }

    // ------------------------------------------------------------------
    // Milestone chain
    // ------------------------------------------------------------------

    private static class Milestone {
        String label;
        final List<Trainer> bossTrainers = new ArrayList<Trainer>();   // leader / E4 member / champion battles
        final List<Trainer> gymTrainers = new ArrayList<Trainer>();    // that gym's regular trainers (optional scope)
        int currentAce;
        int sortKey;
        // Team-boss / Giovanni milestones already carry a self-describing label
        // (e.g. "Maxie (Team Leader)"), so the representative trainer's name must
        // not be appended — it would just repeat the character's name.
        boolean appendRepName = true;
    }

    private void buildChain() {
        int generation = romHandler.generationOfPokemon();
        int romType = romHandler.getROMType();
        // B2W2 reuses the GYM9/10/11-LEADER tags for the postgame "restaurant
        // brothers" (Cilan/Chili/Cress) — these are NOT alternate first-gym leaders
        // the way they are in BW1, so the trio-fold must not apply to B2W2 (they are
        // excluded from the curve entirely, like the other postgame fights).
        boolean isBW2 = generation == 5 && romType == Gen5Constants.Type_BW2;

        // Group gym leaders by (folded) gym number.
        Map<Integer, Milestone> gyms = new LinkedHashMap<Integer, Milestone>();
        Map<Integer, Milestone> elites = new LinkedHashMap<Integer, Milestone>();
        // Gen 7 reuses a single ELITE[n] tag for BOTH a kahuna's early grand-trial
        // battle AND that same character's late-game Elite Four appearance(s). We can't
        // tell them apart until we've seen every trainer for a given n, so collect them
        // here and split by index after the loop (lowest index = grand trial, the rest =
        // Elite Four). For all other generations ELITE tags are handled inline below.
        Map<Integer, List<Trainer>> gen7Elites = new LinkedHashMap<Integer, List<Trainer>>();
        Milestone champion = null;
        Milestone uber = null;
        // Evil-team leaders / mini-boss admins (THEMED:<NAME>-LEADER / -STRONG),
        // keyed on the character name so each character's multiple battles (incl.
        // rematches) fold into a single milestone, like the gym/E4 milestones do.
        Map<String, Milestone> teamBosses = new LinkedHashMap<String, Milestone>();
        // Giovanni's non-gym fights (GIO1/GIO2[-LEADER]), keyed on the GIO number so
        // the Hideout and Silph Co. battles stay as two separate milestones and never
        // collide with his Viridian gym (GYM8-LEADER), which is a different trainer.
        Map<Integer, Milestone> giovanni = new LinkedHashMap<Integer, Milestone>();
        Map<Integer, List<Trainer>> gymRegulars = new LinkedHashMap<Integer, List<Trainer>>();
        // Rival/friend fights kept aside in case this game has no CHAMPION tag and
        // the final rival IS the champion (FireRed/LeafGreen, and Gen 7).
        List<Trainer> rivalCandidates = new ArrayList<Trainer>();

        for (Trainer t : trainers) {
            if (t == null || t.getTag() == null || t.getPokemon().isEmpty()) {
                continue;
            }
            String tag = t.getTag();
            Matcher leader = GYM_LEADER.matcher(tag);
            Matcher elite = ELITE.matcher(tag);
            if (leader.matches()) {
                int n = Integer.parseInt(leader.group(1));
                if (generation == 5 && (n == 9 || n == 10 || n == 11)) {
                    if (isBW2) {
                        continue; // postgame restaurant brothers, not part of the curve
                    }
                    n = 1; // BW1 trio-gym variants are alternate versions of gym 1
                }
                gyms.computeIfAbsent(n, k -> newMilestone("Gym " + k, k)).bossTrainers.add(t);
            } else if (tag.equals("CHAMPION") || tag.startsWith("CHAMPION")) {
                if (champion == null) {
                    champion = newMilestone("Champion", 10000);
                }
                champion.bossTrainers.add(t);
            } else if (tag.startsWith("UBER")) {
                if (uber == null) {
                    uber = newMilestone("Red / Super-boss", 20000);
                }
                uber.bossTrainers.add(t);
            } else if (GIOVANNI.matcher(tag).matches()) {
                // Giovanni's Rocket Hideout (GIO1) / Silph Co. (GIO2) boss fights. Kept
                // separate from each other and from his Viridian gym (GYM8-LEADER). The
                // sortKey is provisional; final chain position is by ace (see below).
                Matcher gio = GIOVANNI.matcher(tag);
                gio.matches();
                int n = Integer.parseInt(gio.group(1));
                giovanni.computeIfAbsent(n, k -> newBossMilestone(giovanniLabel(k), 500 + k)).bossTrainers.add(t);
            } else if (THEMED_BOSS.matcher(tag).matches()) {
                // Evil-team leader / mini-boss admin. Group by character name so all of
                // that character's battles (and rematches) merge into one milestone.
                Matcher themed = THEMED_BOSS.matcher(tag);
                themed.matches();
                String name = themed.group(1);
                boolean isLeader = "LEADER".equals(themed.group(2));
                teamBosses.computeIfAbsent(name,
                        k -> newBossMilestone(teamBossLabel(k, isLeader), 600)).bossTrainers.add(t);
            } else if (elite.matches()) {
                int n = Integer.parseInt(elite.group(1));
                if (generation == 7) {
                    // Defer: the same ELITE[n] tag covers both the grand trial and the
                    // Elite Four appearance — split them after the loop (see below).
                    gen7Elites.computeIfAbsent(n, k -> new ArrayList<Trainer>()).add(t);
                } else {
                    elites.computeIfAbsent(n, k -> newMilestone(eliteLabel(generation, k), 1000 + k)).bossTrainers.add(t);
                }
            } else if (tag.startsWith("RIVAL") || tag.startsWith("FRIEND")) {
                rivalCandidates.add(t);
            } else {
                // regular gym trainer: tag like "GYM3" (no -LEADER)
                Matcher reg = Pattern.compile("GYM(\\d+)").matcher(tag);
                if (reg.matches()) {
                    int n = Integer.parseInt(reg.group(1));
                    if (generation == 5 && (n == 9 || n == 10 || n == 11)) {
                        if (isBW2) {
                            continue;
                        }
                        n = 1;
                    }
                    gymRegulars.computeIfAbsent(n, k -> new ArrayList<Trainer>()).add(t);
                }
            }
        }

        // Gen 7: split each shared ELITE[n] tag into two independent milestones. The
        // lowest-index appearance is the early island grand trial; any higher-index
        // appearance(s) are that kahuna's late-game Elite Four fight (and rematch). They
        // sit at very different levels, so folding them into one milestone would shift the
        // endgame E4 team by the grand-trial's delta. Key the grand trial at n; key the
        // Elite Four appearance in a SEPARATE, non-overlapping space (n + 1000) so it can
        // never collide with a higher ELITE tag's grand-trial key (e.g. ELITE1's Elite
        // Four fight at the old n+4=5 vs. ELITE5's grand trial at 5 merged two different
        // characters). The Elite Four label/sortKey are derived from n directly, not from
        // the (offset) map key, so the user-facing text ("Elite Four n") is unchanged.
        for (Map.Entry<Integer, List<Trainer>> e : gen7Elites.entrySet()) {
            int n = e.getKey();
            List<Trainer> group = e.getValue();
            group.sort(Comparator.comparingInt(Trainer::getIndex));
            for (int i = 0; i < group.size(); i++) {
                if (i == 0) {
                    // first (lowest index) = grand trial / early-island progression
                    elites.computeIfAbsent(n, k -> newMilestone(eliteLabel(generation, k), 1000 + k))
                            .bossTrainers.add(group.get(i));
                } else {
                    // late-game Elite Four fight (and rematch); separate key space so it
                    // sorts after every grand trial yet never collides with one.
                    int key = n + 1000;
                    String eliteFourLabel = "Elite Four " + n;
                    elites.computeIfAbsent(key, k -> newMilestone(eliteFourLabel, 2000 + n))
                            .bossTrainers.add(group.get(i));
                }
            }
        }

        // FireRed/LeafGreen and Gen 7 have no CHAMPION tag — their champion is the
        // final rival/friend fight. Promote the climactic rival battle(s) (those at
        // least as strong as the Elite Four) to a Champion milestone. Guarded to
        // those games so other titles' mid-story rival fights are never mislabeled.
        boolean rivalIsChampion = champion == null && !elites.isEmpty()
                && ((generation == 3 && romType == Gen3Constants.RomType_FRLG) || generation == 7);
        if (rivalIsChampion && !rivalCandidates.isEmpty()) {
            // Threshold = the strongest FIRST-ROUND Elite Four ace. Use each elite milestone's
            // representative (lowest-index = first encounter) team, not the max over all bundled
            // teams — otherwise a high-level E4 rematch inflates it and drops the real (round-1)
            // rival-champion fight from the curve.
            int maxEliteAce = 0;
            for (Milestone m : elites.values()) {
                Trainer rep = null;
                for (Trainer t : m.bossTrainers) {
                    if (rep == null || t.getIndex() < rep.getIndex()) {
                        rep = t;
                    }
                }
                if (rep != null) {
                    maxEliteAce = Math.max(maxEliteAce, aceLevel(rep));
                }
            }
            for (Trainer t : rivalCandidates) {
                if (aceLevel(t) >= maxEliteAce) {
                    if (champion == null) {
                        champion = newMilestone("Champion", 10000);
                    }
                    champion.bossTrainers.add(t);
                }
            }
        }

        // attach regular gym trainers to their gym milestone
        for (Map.Entry<Integer, List<Trainer>> e : gymRegulars.entrySet()) {
            Milestone m = gyms.get(e.getKey());
            if (m != null) {
                m.gymTrainers.addAll(e.getValue());
            }
        }

        List<Integer> gymNums = new ArrayList<Integer>(gyms.keySet());
        Collections.sort(gymNums);
        List<Integer> eliteNums = new ArrayList<Integer>(elites.keySet());
        Collections.sort(eliteNums);

        boolean dualRegion = (generation == 2 || generation == 4)
                && !gymNums.isEmpty() && gymNums.get(gymNums.size() - 1) > 8;

        if (dualRegion) {
            // gyms 1-8 -> league -> Kanto gyms 9-16 -> Red
            for (int n : gymNums) {
                if (n <= 8) {
                    chain.add(gyms.get(n));
                }
            }
            for (int n : eliteNums) {
                chain.add(elites.get(n));
            }
            if (champion != null) {
                chain.add(champion);
            }
            for (int n : gymNums) {
                if (n > 8) {
                    chain.add(gyms.get(n));
                }
            }
            if (uber != null) {
                chain.add(uber);
            }
        } else {
            for (int n : gymNums) {
                chain.add(gyms.get(n));
            }
            for (int n : eliteNums) {
                chain.add(elites.get(n));
            }
            if (champion != null) {
                chain.add(champion);
            }
            // Deliberately NOT adding generic UBER here: outside the dual-region
            // games the UBER tag is a grab-bag (e.g. BW1 lumps the N/Ghetsis story
            // finale with postgame fights like Cynthia / "Game Freak Guy", and
            // B2W2's UBER set is entirely postgame). Ordering it as a single
            // "final" milestone would be misleading, so the auto-curve stops at
            // the champion. Only HGSS/GSC append UBER (= Red) above.
        }

        // compute current ace + nicer labels from the representative (first) boss trainer
        for (Milestone m : chain) {
            finalizeMilestone(m);
        }

        // Splice the evil-team leaders / mini-boss admins and Giovanni's non-gym
        // fights into the chain. They have no fixed numeric slot in the gym -> E4 ->
        // champion progression, so each is inserted at the position dictated by its
        // ace level (the same key the curve interpolates on), via insertByAce which
        // slots a milestone before the FIRST existing one with a higher ace. This
        // works regardless of generation and preserves the dual-region layout
        // (gyms 1-8 -> league -> Kanto gyms 9-16): these are early/mid fights whose
        // aces are exceeded by an early gym or the league, so they always land in the
        // first-8-gyms section and never jump past the league into the Kanto gyms.
        // (The remap's monotonicity is enforced separately, by ace, in buildCurvePoints.)
        List<Milestone> extra = new ArrayList<Milestone>();
        extra.addAll(teamBosses.values());
        extra.addAll(giovanni.values());
        for (Milestone m : extra) {
            finalizeMilestone(m); // computes currentAce (needed for the insertion below)
        }
        // Insert lowest-ace first so equal-ace ties keep a stable, sensible order.
        extra.sort(Comparator.comparingInt(m -> m.currentAce));
        for (Milestone m : extra) {
            insertByAce(m);
        }
    }

    private Milestone newMilestone(String label, int sortKey) {
        Milestone m = new Milestone();
        m.label = label;
        m.sortKey = sortKey;
        return m;
    }

    /**
     * Like {@link #newMilestone}, but for milestones whose label is already complete
     * (team bosses / Giovanni) so the representative trainer's name is not appended.
     */
    private Milestone newBossMilestone(String label, int sortKey) {
        Milestone m = newMilestone(label, sortKey);
        m.appendRepName = false;
        return m;
    }

    /**
     * Sorts a milestone's boss trainers (lowest index = first encounter = the
     * representative), sets its current ace level from that representative, and —
     * unless the label is already self-describing (team bosses / Giovanni) — appends
     * the representative's display name for a friendlier row label.
     */
    private void finalizeMilestone(Milestone m) {
        m.bossTrainers.sort(Comparator.comparingInt(Trainer::getIndex));
        Trainer rep = m.bossTrainers.get(0);
        if (m.appendRepName) {
            if (rep.getFullDisplayName() != null && !rep.getFullDisplayName().trim().isEmpty()) {
                m.label = m.label + " — " + rep.getFullDisplayName().trim();
            } else if (rep.getName() != null && !rep.getName().trim().isEmpty()) {
                m.label = m.label + " — " + rep.getName().trim();
            }
        }
        m.currentAce = aceLevel(rep);
    }

    /**
     * Inserts a milestone into {@link #chain} at the position dictated by its current
     * ace, before the first existing milestone with a strictly higher ace. Equal-ace
     * milestones are placed after the existing ones (stable), so a team boss tied with
     * a gym sits just after that gym. Inserting at the earliest valid slot keeps these
     * early/mid fights in the first-8-gyms section of a dual-region chain (it never
     * pushes them past the league into the lower-ace Kanto gyms) and never moves the
     * lowest-ace gym off the front, so the Scale/Even-ramp anchor stays the first gym.
     */
    private void insertByAce(Milestone m) {
        int pos = chain.size();
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).currentAce > m.currentAce) {
                pos = i;
                break;
            }
        }
        // insertByAce is only ever called for the extra (team-boss / Giovanni admin)
        // milestones, after every gym/E4/champion progression milestone is already in
        // the chain. A THEMED:<NAME>-STRONG admin can have a representative battle with
        // an ace BELOW gym 1 (e.g. Wally's Lv5 tutorial), which would otherwise land it
        // at index 0 and pin the Scale/Even-ramp anchor (chain.get(0).currentAce) to a
        // tutorial battle. Clamp so an extra milestone is never placed ahead of the
        // first progression milestone, keeping the first gym/E4 as the curve floor.
        if (!chain.isEmpty() && pos == 0) {
            pos = 1;
        }
        chain.add(pos, m);
    }

    /** Label for Giovanni's non-gym boss fights (GIO1 = Rocket Hideout, GIO2 = Silph Co.). */
    private static String giovanniLabel(int n) {
        switch (n) {
            case 1:
                return "Giovanni (Hideout)";
            case 2:
                return "Giovanni (Silph Co.)";
            default:
                return "Giovanni (Boss " + n + ")";
        }
    }

    /**
     * Label for an evil-team leader / mini-boss admin, derived from the THEMED tag's
     * character name (e.g. THEMED:MAXIE-LEADER -> "Maxie (Team Leader)",
     * THEMED:COURTNEY-STRONG -> "Courtney (Admin)").
     */
    private static String teamBossLabel(String tagName, boolean isLeader) {
        return titleCase(tagName) + (isLeader ? " (Team Leader)" : " (Admin)");
    }

    /** "MAXIE" -> "Maxie", "JESSIE&JAMES" -> "Jessie&James" (per word-ish token). */
    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean newWord = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                sb.append(newWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
                newWord = false;
            } else {
                sb.append(c);
                newWord = true; // start a new word after any non-letter (space, &, -, digit)
            }
        }
        return sb.toString();
    }

    /**
     * Label for an ELITE milestone. Gen 7 has no gyms: its ELITE1-4 tags are the
     * early-island main-story bosses (the gym-equivalent progression) and ELITE5+
     * are the actual league Elite Four members, so calling the kahunas "Elite
     * Four 1" (as before) was wrong. We label the early-island slots generically
     * as "Island Challenge n" rather than asserting "Grand Trial" from the tag
     * index alone — that assertion is imprecise across SM vs USUM (e.g. in SM the
     * ELITE4 character Hapu is the Poni kahuna without a conventional grand-trial
     * battle, and some ELITE1-2 characters double as league Elite Four members).
     * The label is purely cosmetic; chain ordering is driven by sortKey, not text.
     */
    private static String eliteLabel(int generation, int n) {
        if (generation == 7) {
            return n <= 4 ? "Island Challenge " + n : "Elite Four " + (n - 4);
        }
        return "Elite Four " + n;
    }

    private static int aceLevel(Trainer t) {
        int max = 0;
        for (TrainerPokemon tp : t.getPokemon()) {
            if (tp.getLevel() > max) {
                max = tp.getLevel();
            }
        }
        return max;
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void initUI() {
        setLayout(new BorderLayout(10, 10));

        if (chain.isEmpty()) {
            JLabel none = new JLabel("This ROM has no tagged gym/Elite Four/champion battles to adjust.");
            none.setBorder(new EmptyBorder(20, 20, 20, 20));
            add(none, BorderLayout.CENTER);
            JButton close = new JButton("Close");
            close.addActionListener(e -> dispose());
            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            south.add(close);
            add(south, BorderLayout.SOUTH);
            return;
        }

        add(buildTopControls(), BorderLayout.NORTH);
        add(new JScrollPane(buildMilestoneList()), BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);
    }

    private JPanel buildTopControls() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(10, 10, 4, 10));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(2, 2, 2, 6);

        g.gridx = 0;
        g.gridy = 0;
        panel.add(new JLabel("Scale gaps:"), g);

        scaleSlider = new JSlider(25, 300, 100); // % factor
        scaleSlider.setMajorTickSpacing(25);
        scaleSlider.setPaintTicks(true);
        scaleSlider.setToolTipText("Dragging this overwrites any Target values you typed by hand.");
        scaleSlider.addChangeListener(e -> applyScalePreview());
        g.gridx = 1;
        g.weightx = 1.0;
        g.fill = GridBagConstraints.HORIZONTAL;
        panel.add(scaleSlider, g);

        scaleLabel = new JLabel("1.00x");
        g.gridx = 2;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        panel.add(scaleLabel, g);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton normalize = new JButton("Normalize (fix dips)");
        normalize.setToolTipText("Force the curve to never go down, nudging only the milestones that dip below the previous one.");
        normalize.addActionListener(e -> normalizeMinimal());
        JButton evenRamp = new JButton("Even ramp");
        evenRamp.setToolTipText("Spread levels evenly from the first milestone to the last.");
        evenRamp.addActionListener(e -> evenRamp());
        JButton reset = new JButton("Reset");
        reset.addActionListener(e -> resetTargets());
        buttons.add(normalize);
        buttons.add(evenRamp);
        buttons.add(reset);
        g.gridx = 0;
        g.gridy = 1;
        g.gridwidth = 3;
        panel.add(buttons, g);

        includeGymTrainers = new JCheckBox("Also shift each gym's regular trainers", true);
        g.gridy = 2;
        panel.add(includeGymTrainers, g);

        scaleAllTrainers = new JCheckBox("Also scale every other trainer (routes, rivals, optional fights)", false);
        scaleAllTrainers.setToolTipText("Remap EVERY remaining trainer's levels along the same curve, so the whole "
                + "game scales smoothly between milestones — not just the gym / Elite Four / champion fights.");
        g.gridy = 3;
        panel.add(scaleAllTrainers, g);

        JLabel hint = new JLabel("<html><i>Anchor is the first milestone. Type any target directly for per-gap control.</i></html>");
        hint.setForeground(mutedColor());
        g.gridy = 4;
        panel.add(hint, g);

        return panel;
    }

    /** Readable muted-label colour for the current theme (Color.GRAY is too dark on the dark LaF). */
    private static Color mutedColor() {
        return com.dabomstew.pkrandom.gui.ThemeManager.isDarkModeApplied()
                ? new Color(0xC0, 0xC0, 0xC0) : Color.GRAY;
    }

    private JPanel buildMilestoneList() {
        JPanel list = new JPanel(new GridBagLayout());
        list.setBorder(new EmptyBorder(4, 12, 4, 12));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(2, 4, 2, 8);

        // header
        g.gridy = 0;
        g.gridx = 0;
        list.add(boldLabel("Milestone"), g);
        g.gridx = 1;
        list.add(boldLabel("Current"), g);
        g.gridx = 2;
        list.add(boldLabel("Target"), g);

        targetSpinners.clear();
        for (int i = 0; i < chain.size(); i++) {
            Milestone m = chain.get(i);
            g.gridy = i + 1;

            g.gridx = 0;
            g.weightx = 1.0;
            g.fill = GridBagConstraints.HORIZONTAL;
            list.add(new JLabel(m.label), g);

            g.gridx = 1;
            g.weightx = 0;
            g.fill = GridBagConstraints.NONE;
            JLabel cur = new JLabel("Lv " + m.currentAce);
            cur.setForeground(mutedColor());
            list.add(cur, g);

            g.gridx = 2;
            JSpinner spinner = new JSpinner(new SpinnerNumberModel(m.currentAce, 1, 100, 1));
            spinner.addChangeListener(e -> {
                // Only react to genuine MANUAL edits. When adjusting is true the change
                // came from our own code (slider preview, normalize, ramp, reset) which
                // manages the slider itself — reacting then would fight those updates.
                if (adjusting) {
                    return;
                }
                // A manual edit: reset the Scale slider to a neutral 1.00x (without
                // firing applyScalePreview) so its value no longer claims a stale scale
                // factor. Otherwise a later 1-tick slider nudge would recompute the whole
                // curve from the milestones' original aces and silently throw away this
                // hand edit. From neutral, any slider drag is a deliberate, visible
                // re-scale (per the slider's own tooltip).
                syncSliderToNeutral();
            });
            targetSpinners.add(spinner);
            list.add(spinner, g);
        }
        return list;
    }

    private JPanel buildButtons() {
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton apply = new JButton("Apply");
        apply.addActionListener(e -> apply());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        south.add(cancel);
        south.add(apply);
        getRootPane().setDefaultButton(apply);
        return south;
    }

    private JLabel boldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    // ------------------------------------------------------------------
    // Curve operations (update the spinners)
    // ------------------------------------------------------------------

    private void applyScalePreview() {
        if (adjusting || chain.isEmpty()) {
            return;
        }
        double factor = scaleSlider.getValue() / 100.0;
        scaleLabel.setText(String.format("%.2fx", factor));
        int anchor = chain.get(0).currentAce;
        adjusting = true;
        for (int i = 0; i < chain.size(); i++) {
            int v = (int) Math.round(anchor + (chain.get(i).currentAce - anchor) * factor);
            targetSpinners.get(i).setValue(clamp(v));
        }
        adjusting = false;
    }

    /**
     * Resets the Scale slider + label to a neutral 1.00x WITHOUT firing
     * applyScalePreview (guarded by {@code adjusting}). Used after Normalize, Even
     * ramp and manual spinner edits so the slider never carries a stale scale factor
     * that a later 1-tick move would replay over the hand-edited targets.
     */
    private void syncSliderToNeutral() {
        boolean wasAdjusting = adjusting;
        adjusting = true;
        scaleSlider.setValue(100);
        scaleLabel.setText("1.00x");
        adjusting = wasAdjusting;
    }

    private void normalizeMinimal() {
        adjusting = true;
        int prev = 0;
        for (int i = 0; i < chain.size(); i++) {
            int cur = ((Number) targetSpinners.get(i).getValue()).intValue();
            int v = Math.max(cur, prev);
            v = clamp(v);
            targetSpinners.get(i).setValue(v);
            prev = v;
        }
        adjusting = false;
        // Keep the slider honest: its current value is no longer the factor that
        // produced these targets.
        syncSliderToNeutral();
    }

    private void evenRamp() {
        adjusting = true;
        int n = chain.size();
        int first = chain.get(0).currentAce;
        int last = chain.get(n - 1).currentAce;
        for (int i = 0; i < n; i++) {
            int v = n == 1 ? first : (int) Math.round(first + (double) (last - first) * i / (n - 1));
            targetSpinners.get(i).setValue(clamp(v));
        }
        adjusting = false;
        // Keep the slider honest: its current value is no longer the factor that
        // produced these targets.
        syncSliderToNeutral();
    }

    private void resetTargets() {
        adjusting = true;
        scaleSlider.setValue(100);
        scaleLabel.setText("1.00x");
        for (int i = 0; i < chain.size(); i++) {
            targetSpinners.get(i).setValue(chain.get(i).currentAce);
        }
        adjusting = false;
    }

    private static int clamp(int v) {
        return Math.max(1, Math.min(100, v));
    }

    // ------------------------------------------------------------------
    // Apply
    // ------------------------------------------------------------------

    private void apply() {
        boolean shiftGyms = includeGymTrainers.isSelected();
        boolean scaleAll = scaleAllTrainers.isSelected();
        int changed = 0;
        List<String> editLog = new ArrayList<String>();
        // Trainers handled by the milestone pass — excluded from the "scale everything
        // else" pass so they are never shifted twice.
        java.util.Set<Trainer> handled = new java.util.HashSet<Trainer>();
        for (int i = 0; i < chain.size(); i++) {
            Milestone m = chain.get(i);
            int target = ((Number) targetSpinners.get(i).getValue()).intValue();
            int delta = target - m.currentAce;
            handled.addAll(m.bossTrainers);
            if (shiftGyms) {
                handled.addAll(m.gymTrainers);
            }
            if (delta == 0) {
                continue;
            }
            for (Trainer t : m.bossTrainers) {
                shiftTeam(t, delta);
            }
            if (shiftGyms) {
                for (Trainer t : m.gymTrainers) {
                    shiftTeam(t, delta);
                }
            }
            // Disclose when a milestone shifts more than one team (some tags also
            // cover postgame rematch / challenge-mode teams that share the tag).
            int teams = m.bossTrainers.size();
            editLog.add(m.label + ": ace level " + m.currentAce + " -> " + target
                    + " (" + (delta > 0 ? "+" : "") + delta + ")"
                    + (teams > 1 ? ", " + teams + " teams (incl. rematches)" : "")
                    + (shiftGyms ? ", incl. gym trainers" : ""));
            changed++;
        }

        if (changed == 0) {
            JOptionPane.showMessageDialog(this, "No changes to apply.", "Level Curve",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Scale every other (non-milestone) trainer by remapping each level along the
        // piecewise-linear curve defined by the milestones' (current ace -> target) points.
        int otherTrainers = 0;
        if (scaleAll) {
            int[][] pts = buildCurvePoints();
            for (Trainer t : trainers) {
                if (t == null || t.getPokemon().isEmpty() || handled.contains(t)) {
                    continue;
                }
                boolean any = false;
                for (TrainerPokemon tp : t.getPokemon()) {
                    int nl = clamp(remapLevel(tp.getLevel(), pts));
                    if (nl != tp.getLevel()) {
                        tp.setLevel(nl);
                        any = true;
                    }
                }
                if (any) {
                    otherTrainers++;
                }
            }
            if (otherTrainers > 0) {
                editLog.add("Scaled " + otherTrainers + " other trainer(s) (routes / rivals / optional) along the curve");
            }
        }

        try {
            romHandler.setTrainers(trainers);
            applied = true;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to apply: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        ManualEditRegistry.getInstance().addEntries("Trainer Level Curve", editLog);

        JOptionPane.showMessageDialog(this,
                "Adjusted " + changed + " milestone(s)"
                        + (otherTrainers > 0 ? " and scaled " + otherTrainers + " other trainer(s)" : "")
                        + ".\nChanges are written when you save / randomize the ROM.",
                "Level Curve Applied", JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    /**
     * Curve control points (milestone current-ace -> chosen target), sorted by ace.
     *
     * <p>Two milestones can share the same current ace (e.g. an Elite Four member and
     * the champion both at Lv 50). A TreeMap keyed on the ace would collapse them with
     * last-wins, silently dropping one milestone's target. Instead, when several
     * milestones share an ace we MERGE them into one control point keeping the HIGHEST
     * chosen target — a tied milestone should pull non-milestone trainers up to the
     * strongest of the tied fights, never down to whichever happened to come last.
     *
     * <p>We also enforce a non-decreasing target sequence (running max over the sorted
     * points). The remap interpolates over these targets, so a milestone whose target
     * dips below an earlier (weaker) milestone would otherwise invert the curve and
     * hand non-milestone trainers a LOWER level than a weaker boss. Clamping y to the
     * running max guarantees the remapped curve never goes down.
     */
    private int[][] buildCurvePoints() {
        // Aggregate by ace, keeping the maximum target among milestones that share it.
        java.util.TreeMap<Integer, Integer> map = new java.util.TreeMap<Integer, Integer>();
        for (int i = 0; i < chain.size(); i++) {
            int x = chain.get(i).currentAce;
            int y = ((Number) targetSpinners.get(i).getValue()).intValue();
            Integer existing = map.get(x);
            map.put(x, existing == null ? y : Math.max(existing, y));
        }
        int[][] pts = new int[map.size()][2];
        int k = 0;
        int runningMax = Integer.MIN_VALUE;
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            runningMax = Math.max(runningMax, e.getValue());
            pts[k][0] = e.getKey();
            pts[k][1] = runningMax; // enforce non-decreasing targets across control points
            k++;
        }
        return pts;
    }

    /**
     * Remaps a level through the milestone curve. Below the first / above the last
     * milestone it shifts by that endpoint's delta; in between it interpolates
     * linearly, so untagged trainers scale smoothly with the gyms around them.
     *
     * <p>Precondition: {@code pts} has a non-decreasing target (y) sequence — see
     * {@link #buildCurvePoints()}, which enforces a running max. With that guarantee
     * the linear interpolation here can never produce an inverted (lower-than-a-
     * weaker-milestone) result.
     */
    private static int remapLevel(int level, int[][] pts) {
        if (pts.length == 0) {
            return level;
        }
        if (level <= pts[0][0]) {
            return level + (pts[0][1] - pts[0][0]);
        }
        int last = pts.length - 1;
        if (level >= pts[last][0]) {
            return level + (pts[last][1] - pts[last][0]);
        }
        for (int i = 0; i < last; i++) {
            int x0 = pts[i][0], x1 = pts[i + 1][0];
            if (level >= x0 && level <= x1) {
                if (x1 == x0) {
                    return pts[i][1];
                }
                double tt = (double) (level - x0) / (x1 - x0);
                return (int) Math.round(pts[i][1] + tt * (pts[i + 1][1] - pts[i][1]));
            }
        }
        return level;
    }

    private static void shiftTeam(Trainer t, int delta) {
        if (t.getPokemon().isEmpty()) {
            return;
        }
        // Cap the flat delta so no member crosses the [1,100] boundary, preserving
        // the team's internal level spread instead of flattening it against the
        // clamp. A naive per-member clamp turns a 95/98/100 team shifted +5 into
        // 100/100/100; capping the shared delta to what the highest (for a positive
        // delta) / lowest (for a negative delta) member can absorb keeps the gaps.
        int maxLevel = 1, minLevel = 100;
        for (TrainerPokemon tp : t.getPokemon()) {
            maxLevel = Math.max(maxLevel, tp.getLevel());
            minLevel = Math.min(minLevel, tp.getLevel());
        }
        if (delta > 0) {
            delta = Math.min(delta, 100 - maxLevel);
        } else if (delta < 0) {
            delta = Math.max(delta, 1 - minLevel);
        }
        if (delta == 0) {
            return;
        }
        for (TrainerPokemon tp : t.getPokemon()) {
            tp.setLevel(Math.max(1, Math.min(100, tp.getLevel() + delta)));
        }
    }
}
