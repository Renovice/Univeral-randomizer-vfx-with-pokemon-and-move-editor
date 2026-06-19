package com.dabomstew.pkrandom.randomizers;

import com.dabomstew.pkrandom.Settings;
import com.dabomstew.pkromio.constants.SpeciesIDs;
import com.dabomstew.pkromio.gamedata.Evolution;
import com.dabomstew.pkromio.gamedata.ExpCurve;
import com.dabomstew.pkromio.gamedata.MegaEvolution;
import com.dabomstew.pkromio.gamedata.Species;
import com.dabomstew.pkromio.gamedata.SpeciesSet;
import com.dabomstew.pkromio.gamedata.cueh.BasicSpeciesAction;
import com.dabomstew.pkromio.gamedata.cueh.EvolvedSpeciesAction;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import java.util.*;

public class SpeciesBaseStatRandomizer extends Randomizer {

    private static final int SHEDINJA_HP = 1;
    protected static final int MIN_HP = 20;
    protected static final int MIN_NON_HP_STAT = 10;

    public SpeciesBaseStatRandomizer(RomHandler romHandler, Settings settings, Random random) {
        super(romHandler, settings, random);
    }

    Map<Species, List<Integer>> shuffledStatsOrders;

    public void shuffleSpeciesStats() {
        boolean evolutionSanity = settings.isBaseStatsFollowEvolutions();
        boolean megaEvolutionSanity = settings.isBaseStatsFollowMegaEvolutions();

        shuffledStatsOrders = new HashMap<>();

        romHandler.getSpeciesSetInclFormes().forEach(this::clampBaseStatsToByte);

        copyUpEvolutionsHelper.apply(evolutionSanity, false,
                this::putShuffledStatsOrder, this::copyUpShuffledStatsOrder);

        romHandler.getSpeciesSetInclFormes().filter(Species::isActuallyCosmetic)
                .forEach(pk -> copyUpShuffledStatsOrder(pk.getBaseForme(), pk));

        if (megaEvolutionSanity) {
            for (MegaEvolution megaEvo : romHandler.getMegaEvolutions()) {
                if (megaEvo.getFrom().getMegaEvolutionsFrom().size() == 1) {
                    copyUpShuffledStatsOrder(megaEvo.getFrom(), megaEvo.getTo());
                }
            }
        }

        romHandler.getSpeciesSetInclFormes().forEach(this::applyShuffledOrderToStats);

        changesMade = true;
    }

    protected void putShuffledStatsOrder(Species pk) {
        List<Integer> order = Arrays.asList(0, 1, 2, 3, 4, 5);
        Collections.shuffle(order, random);
        shuffledStatsOrders.put(pk, order);
    }

    private void copyUpShuffledStatsOrder(Species from, Species to) {
        copyUpShuffledStatsOrder(from, to, false);
    }

    private void copyUpShuffledStatsOrder(Species from, Species to, boolean unused) {
        // has a third boolean parameter just so it can fit the EvolvedSpeciesAction functional interface
        shuffledStatsOrders.put(to, shuffledStatsOrders.get(from));
    }

    protected void applyShuffledOrderToStats(Species pk) {
        if (shuffledStatsOrders.containsKey(pk)) {
            List<Integer> order = shuffledStatsOrders.get(pk);
            List<Integer> stats = Arrays.asList(
                    clampStat(pk.getHp(), 1, 255),
                    clampStat(pk.getAttack(), 1, 255),
                    clampStat(pk.getDefense(), 1, 255),
                    clampStat(pk.getSpatk(), 1, 255),
                    clampStat(pk.getSpdef(), 1, 255),
                    clampStat(pk.getSpeed(), 1, 255)
            );
            pk.setHp(stats.get(order.get(0)));
            pk.setAttack(stats.get(order.get(1)));
            pk.setDefense(stats.get(order.get(2)));
            pk.setSpatk(stats.get(order.get(3)));
            pk.setSpdef(stats.get(order.get(4)));
            pk.setSpeed(stats.get(order.get(5)));
        }
    }

    public void randomizeSpeciesStats() {
        boolean evolutionSanity = settings.isBaseStatsFollowEvolutions();
        boolean megaEvolutionSanity = settings.isBaseStatsFollowMegaEvolutions();
        boolean assignEvoStatsRandomly = settings.isAssignEvoStatsRandomly();

        romHandler.getSpeciesSetInclFormes().forEach(this::clampBaseStatsToByte);

        BasicSpeciesAction<Species> bpAction = this::randomizeStatsWithinBST;
        EvolvedSpeciesAction<Species> randomEpAction = (evFrom, evTo, toMonIsFinalEvo) ->
                assignNewStatsForEvolution(evFrom, evTo);
        EvolvedSpeciesAction<Species> copyEpAction = (evFrom, evTo, toMonIsFinalEvo) ->
                copyRandomizedStatsUpEvolution(evFrom, evTo);

        copyUpEvolutionsHelper.apply(evolutionSanity, true, bpAction,
                assignEvoStatsRandomly ? randomEpAction : copyEpAction, randomEpAction, bpAction);

        romHandler.getSpeciesSetInclFormes().filter(Species::isActuallyCosmetic)
                .forEach(pk -> pk.copyBaseFormeBaseStats(pk.getBaseForme()));

        if (megaEvolutionSanity) {
            for (MegaEvolution megaEvo : romHandler.getMegaEvolutions()) {
                if (megaEvo.getFrom().getMegaEvolutionsFrom().size() > 1 || assignEvoStatsRandomly) {
                    assignNewStatsForEvolution(megaEvo.getFrom(), megaEvo.getTo());
                } else {
                    copyRandomizedStatsUpEvolution(megaEvo.getFrom(), megaEvo.getTo());
                }
            }
        }
        changesMade = true;
    }

    // ------------------------------------------------------------------
    // "Buff / gate base stats" (BaseStatisticsMod.GATED)
    // ------------------------------------------------------------------

    public void gateSpeciesStats() {
        Settings.GateDirection direction = settings.getGateDirection();
        Settings.GateDistribution distribution = settings.getGateDistribution();
        Settings.GateCeiling ceiling = settings.getGateCeiling();
        Settings.GateEvoHandling evoHandling = settings.getGateEvoHandling();
        int threshold = settings.getGateThreshold();
        int amount = settings.getGateAmount();
        boolean amountRandom = settings.getGateAmountStyle() == Settings.GateAmountStyle.RANDOM;

        // Always normalise to byte-range first, like the other base-stat modes.
        romHandler.getSpeciesSetInclFormes().forEach(this::clampBaseStatsToByte);

        switch (evoHandling) {
            case WHOLE_LINE:
                gateWholeLine(direction, distribution, ceiling, threshold, amount, amountRandom);
                break;
            case KEEP_BELOW_EVO:
                gateKeepBelowEvo(direction, distribution, ceiling, threshold, amount, amountRandom);
                break;
            case PER_STAGE:
            default:
                for (Species pk : romHandler.getSpeciesSetInclFormes()) {
                    gateSingleSpecies(pk, direction, distribution, ceiling, threshold, amount, amountRandom);
                }
                break;
        }

        // Keep cosmetic formes consistent with their base forme.
        romHandler.getSpeciesSetInclFormes().filter(Species::isActuallyCosmetic)
                .forEach(pk -> pk.copyBaseFormeBaseStats(pk.getBaseForme()));

        changesMade = true;
    }

    /**
     * PER_STAGE handling: each species is gated independently by its own BST.
     * Returns true if the species qualified and was changed.
     */
    private boolean gateSingleSpecies(Species pk, Settings.GateDirection direction,
                                      Settings.GateDistribution distribution, Settings.GateCeiling ceiling,
                                      int threshold, int amount, boolean amountRandom) {
        int currentBST = pk.getBST();
        int sign = gateSignFor(currentBST, direction, threshold);
        if (sign == 0) {
            return false;
        }

        int magnitude = amountRandom ? (1 + random.nextInt(Math.max(1, amount))) : amount;
        int newBST = applyCeiling(currentBST, sign * magnitude, sign, ceiling, threshold);
        if (newBST == currentBST) {
            return false;
        }

        applyNewBST(pk, newBST, distribution);
        return true;
    }

    /**
     * WHOLE_LINE handling: gate by the line's base-form BST. If the base qualifies, the
     * whole line is scaled consistently using a proportional copy-up (so it stays coherent).
     */
    private void gateWholeLine(Settings.GateDirection direction, Settings.GateDistribution distribution,
                               Settings.GateCeiling ceiling, int threshold, int amount, boolean amountRandom) {
        // Buff/nerf the basic species (gated by its own BST), then copy the change up the
        // evolution line proportionally so every stage moves together.
        BasicSpeciesAction<Species> bpAction = pk ->
                gateSingleSpecies(pk, direction, distribution, ceiling, threshold, amount, amountRandom);
        EvolvedSpeciesAction<Species> copyAction = (evFrom, evTo, toMonIsFinalEvo) ->
                copyRandomizedStatsUpEvolution(evFrom, evTo);

        // evolutionSanity=true so we walk lines; copySplitEvos=false so split evos copy from
        // their (already gated) pre-evo too, keeping the whole family consistent.
        copyUpEvolutionsHelper.apply(true, false, bpAction, copyAction);
    }

    /**
     * KEEP_BELOW_EVO handling: buff each stage independently, then walk each evolution line
     * from the base up and cap each lower stage so its BST stays strictly below the BST of
     * what it evolves into. Scales the lower stage down proportionally if it would reach or
     * exceed its evolution.
     */
    private void gateKeepBelowEvo(Settings.GateDirection direction, Settings.GateDistribution distribution,
                                  Settings.GateCeiling ceiling, int threshold, int amount, boolean amountRandom) {
        // First, gate every species independently (per-stage).
        for (Species pk : romHandler.getSpeciesSetInclFormes()) {
            gateSingleSpecies(pk, direction, distribution, ceiling, threshold, amount, amountRandom);
        }

        // Then enforce "stage strictly below its evolution" across each line, processing
        // from basic species upward so a capped lower stage constrains the next one.
        SpeciesSet allSpecs = romHandler.getSpeciesSetInclFormes();
        Set<Species> processed = new HashSet<>();
        Deque<Species> queue = new ArrayDeque<>();
        for (Species pk : allSpecs) {
            if (isGateBasicSpecies(allSpecs, pk)) {
                queue.add(pk);
            }
        }
        while (!queue.isEmpty()) {
            Species from = queue.poll();
            if (!processed.add(from)) {
                continue;
            }
            for (Evolution evo : from.getEvolutionsFrom()) {
                Species to = evo.getTo();
                if (to == null || !allSpecs.contains(to) || to == from) {
                    continue;
                }
                capStageBelowEvolution(from, to);
                queue.add(to);
            }
        }
    }

    private boolean isGateBasicSpecies(SpeciesSet allSpecs, Species spec) {
        for (Evolution evo : spec.getEvolutionsTo()) {
            if (allSpecs.contains(evo.getFrom())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Ensures {@code from}'s BST is strictly below {@code to}'s BST. If it isn't, the
     * lower stage ({@code from}) is scaled down proportionally so it lands just below.
     */
    private void capStageBelowEvolution(Species from, Species to) {
        int fromBST = from.getBST();
        int evoBST = to.getBST();
        if (fromBST < evoBST) {
            return;
        }
        // Target: one full point below the evolution's BST (but never below the line minimum).
        int minSum = (from.getNumber() == SpeciesIDs.shedinja)
                ? (SHEDINJA_HP + 5 * MIN_NON_HP_STAT)
                : (MIN_HP + 5 * MIN_NON_HP_STAT);
        int targetBST = Math.max(minSum, evoBST - 1);
        if (targetBST >= fromBST) {
            // Evolution is at/below the floor; nothing sensible to do.
            return;
        }
        // Reuse the proportional path: distribute targetBST across current stats as weights.
        applyNewBST(from, targetBST, Settings.GateDistribution.PROPORTIONAL);
    }

    /**
     * Decides the direction of change for a species with the given BST.
     * @return +1 to buff, -1 to nerf, 0 to leave unchanged.
     */
    private int gateSignFor(int currentBST, Settings.GateDirection direction, int threshold) {
        boolean buffsBelow = direction == Settings.GateDirection.BUFF_BELOW
                || direction == Settings.GateDirection.BOTH;
        boolean nerfsAbove = direction == Settings.GateDirection.NERF_ABOVE
                || direction == Settings.GateDirection.BOTH;
        if (buffsBelow && currentBST <= threshold) {
            return 1;
        }
        if (nerfsAbove && currentBST >= threshold) {
            return -1;
        }
        return 0;
    }

    /**
     * Applies the ceiling/floor rule to a proposed BST change.
     * For buffs (sign &gt; 0) with CAP_AT_THRESHOLD, the new BST never exceeds the threshold.
     * For nerfs (sign &lt; 0) with CAP_AT_THRESHOLD, the new BST never drops below the threshold.
     */
    private int applyCeiling(int currentBST, int signedDelta, int sign, Settings.GateCeiling ceiling, int threshold) {
        int newBST = currentBST + signedDelta;
        if (ceiling == Settings.GateCeiling.CAP_AT_THRESHOLD) {
            if (sign > 0 && newBST > threshold) {
                newBST = threshold;
            } else if (sign < 0 && newBST < threshold) {
                newBST = threshold;
            }
        }
        // Never let the BST fall below the per-species minimum sum, nor exceed 6*255.
        newBST = Math.max(newBST, 6);
        newBST = Math.min(newBST, 6 * 255);
        return newBST;
    }

    /**
     * Sets a species' 6 stats so they sum (as closely as possible) to {@code newBST},
     * using the chosen distribution. Honors the Shedinja HP=1 special case.
     */
    protected void applyNewBST(Species pk, int newBST, Settings.GateDistribution distribution) {
        boolean shedinja = pk.getNumber() == SpeciesIDs.shedinja;
        int[] baseMins = shedinja
                ? new int[]{SHEDINJA_HP, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT}
                : new int[]{MIN_HP, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT};
        int[] maxes = shedinja
                ? new int[]{SHEDINJA_HP, 255, 255, 255, 255, 255}
                : new int[]{255, 255, 255, 255, 255, 255};

        int[] current = new int[]{pk.getHp(), pk.getAttack(), pk.getDefense(),
                pk.getSpatk(), pk.getSpdef(), pk.getSpeed()};

        double[] weights = new double[6];
        switch (distribution) {
            case EVEN: {
                // Split the *delta* equally across the stats by weighting the current
                // stats plus an equal share, i.e. distribute the new total with equal weights.
                for (int i = 0; i < 6; i++) {
                    weights[i] = 1.0;
                }
                if (shedinja) {
                    weights[0] = 0.0;
                }
                break;
            }
            case RANDOM: {
                for (int i = 0; i < 6; i++) {
                    weights[i] = random.nextDouble();
                }
                if (shedinja) {
                    weights[0] = 0.0;
                }
                break;
            }
            case PROPORTIONAL:
            default: {
                // Scale the whole spread by newBST/oldBST: weight each stat by its current value.
                for (int i = 0; i < 6; i++) {
                    weights[i] = Math.max(current[i], 1);
                }
                if (shedinja) {
                    weights[0] = 0.0;
                }
                break;
            }
        }

        int[] stats = distributeStatsWithCaps(newBST, baseMins, maxes, weights);
        if (shedinja) {
            stats[0] = SHEDINJA_HP;
        }
        pk.setHp(clampStat(stats[0], shedinja ? SHEDINJA_HP : MIN_HP, 255));
        pk.setAttack(clampStat(stats[1], 1, 255));
        pk.setDefense(clampStat(stats[2], 1, 255));
        pk.setSpatk(clampStat(stats[3], 1, 255));
        pk.setSpdef(clampStat(stats[4], 1, 255));
        pk.setSpeed(clampStat(stats[5], 1, 255));
        // Note: PROPORTIONAL keeps shape because the weights are the current stats, so the
        // result is approximately current * (newBST / oldBST) up to rounding and stat caps.
    }

    protected void randomizeStatsWithinBST(Species pk) {
        if (pk.getNumber() == SpeciesIDs.shedinja) {
            randomizeShedinjaStatsWithinBST(pk);
        } else {
            randomizeRegularStatsWithinBST(pk);
        }
    }

    private void randomizeShedinjaStatsWithinBST(Species pk) {
        pk.setHp(SHEDINJA_HP);
        int[] baseMins = new int[]{SHEDINJA_HP, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT};
        int[] maxes = new int[]{SHEDINJA_HP, 255, 255, 255, 255, 255};
        double[] weights = new double[]{0.0, random.nextDouble(), random.nextDouble(),
                random.nextDouble(), random.nextDouble(), random.nextDouble()};
        int[] stats = distributeStatsWithCaps(pk.getBST(), baseMins, maxes, weights);
        pk.setAttack(stats[1]);
        pk.setDefense(stats[2]);
        pk.setSpatk(stats[3]);
        pk.setSpdef(stats[4]);
        pk.setSpeed(stats[5]);
    }

    private void randomizeRegularStatsWithinBST(Species pk) {
        int[] baseMins = new int[]{MIN_HP, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT};
        int[] maxes = new int[]{255, 255, 255, 255, 255, 255};
        double[] weights = new double[]{random.nextDouble(), random.nextDouble(), random.nextDouble(),
                random.nextDouble(), random.nextDouble(), random.nextDouble()};
        int[] stats = distributeStatsWithCaps(pk.getBST(), baseMins, maxes, weights);
        pk.setHp(stats[0]);
        pk.setAttack(stats[1]);
        pk.setDefense(stats[2]);
        pk.setSpatk(stats[3]);
        pk.setSpdef(stats[4]);
        pk.setSpeed(stats[5]);
    }

    protected void assignNewStatsForEvolution(Species from, Species to) {
        double bstDiff = to.getBST() - from.getBST();

        // Make weightings
        double hpW = random.nextDouble(), atkW = random.nextDouble(), defW = random.nextDouble();
        double spaW = random.nextDouble(), spdW = random.nextDouble(), speW = random.nextDouble();

        double totW = hpW + atkW + defW + spaW + spdW + speW;

        double hpDiff = Math.round((hpW / totW) * bstDiff);
        double atkDiff = Math.round((atkW / totW) * bstDiff);
        double defDiff = Math.round((defW / totW) * bstDiff);
        double spaDiff = Math.round((spaW / totW) * bstDiff);
        double spdDiff = Math.round((spdW / totW) * bstDiff);
        double speDiff = Math.round((speW / totW) * bstDiff);

        to.setHp((int) Math.min(255, Math.max(1, from.getHp() + hpDiff)));
        to.setAttack((int) Math.min(255, Math.max(1, from.getAttack() + atkDiff)));
        to.setDefense((int) Math.min(255, Math.max(1, from.getDefense() + defDiff)));
        to.setSpatk((int) Math.min(255, Math.max(1, from.getSpatk() + spaDiff)));
        to.setSpdef((int) Math.min(255, Math.max(1, from.getSpdef() + spdDiff)));
        to.setSpeed((int) Math.min(255, Math.max(1, from.getSpeed() + speDiff)));
    }

    protected void copyRandomizedStatsUpEvolution(Species from, Species to) {
        double bstRatio = (double) to.getBST() / (double) from.getBST();

        to.setHp((int) Math.min(255, Math.max(1, Math.round(from.getHp() * bstRatio))));
        to.setAttack((int) Math.min(255, Math.max(1, Math.round(from.getAttack() * bstRatio))));
        to.setDefense((int) Math.min(255, Math.max(1, Math.round(from.getDefense() * bstRatio))));
        to.setSpatk((int) Math.min(255, Math.max(1, Math.round(from.getSpatk() * bstRatio))));
        to.setSpdef((int) Math.min(255, Math.max(1, Math.round(from.getSpdef() * bstRatio))));
        to.setSpeed((int) Math.min(255, Math.max(1, Math.round(from.getSpeed() * bstRatio))));
    }

    protected int[] distributeStatsWithCaps(int target, int[] baseMins, int[] maxes, double[] weights) {
        int len = baseMins.length;
        int[] mins = computeMinimums(target, baseMins, maxes);
        int totalMax = Arrays.stream(maxes).sum();
        if (target >= totalMax) {
            return Arrays.copyOf(maxes, len);
        }

        int[] result = Arrays.copyOf(mins, len);
        int sum = Arrays.stream(result).sum();
        if (sum >= target) {
            if (sum > target) {
                reduceValuesToTarget(result, target);
            }
            return result;
        }

        int remaining = target - sum;
        boolean[] saturated = new boolean[len];
        for (int i = 0; i < len; i++) {
            if (result[i] >= maxes[i]) {
                saturated[i] = true;
            }
        }

        final double epsilon = 1e-6;
        while (remaining > 0) {
            double weightSum = 0.0;
            for (int i = 0; i < len; i++) {
                if (saturated[i]) {
                    continue;
                }
                double w = weights[i] > 0 ? weights[i] : epsilon;
                weightSum += w;
            }
            if (weightSum <= 0) {
                break;
            }

            int allocatedThisPass = 0;
            double[] desired = new double[len];
            double[] remainders = new double[len];
            for (int i = 0; i < len; i++) {
                if (saturated[i]) {
                    continue;
                }
                double w = weights[i] > 0 ? weights[i] : epsilon;
                desired[i] = (w / weightSum) * remaining;
            }

            for (int i = 0; i < len; i++) {
                if (saturated[i]) {
                    continue;
                }
                int capacity = maxes[i] - result[i];
                if (capacity <= 0) {
                    saturated[i] = true;
                    continue;
                }
                int add = (int) Math.floor(desired[i]);
                if (add > capacity) {
                    add = capacity;
                }
                if (add > remaining) {
                    add = remaining;
                }
                if (add > 0) {
                    result[i] += add;
                    remaining -= add;
                    allocatedThisPass += add;
                    if (result[i] >= maxes[i]) {
                        saturated[i] = true;
                    }
                }
                remainders[i] = desired[i] - add;
            }

            if (remaining <= 0) {
                break;
            }

            if (allocatedThisPass == 0) {
                int bestIdx = -1;
                double bestRem = -1.0;
                for (int i = 0; i < len; i++) {
                    if (saturated[i]) {
                        continue;
                    }
                    int capacity = maxes[i] - result[i];
                    if (capacity <= 0) {
                        saturated[i] = true;
                        continue;
                    }
                    double rem = remainders[i];
                    if (rem < 0) {
                        rem = 0;
                    }
                    if (rem > bestRem) {
                        bestRem = rem;
                        bestIdx = i;
                    }
                }
                if (bestIdx == -1) {
                    break;
                }
                result[bestIdx]++;
                remaining--;
                if (result[bestIdx] >= maxes[bestIdx]) {
                    saturated[bestIdx] = true;
                }
            }
        }
        return result;
    }

    private int[] computeMinimums(int target, int[] baseMins, int[] maxes) {
        int len = baseMins.length;
        int[] mins = new int[len];
        double[] fractional = new double[len];
        int[] cappedBase = new int[len];
        int baseSum = 0;
        for (int i = 0; i < len; i++) {
            int capped = Math.min(Math.max(1, baseMins[i]), maxes[i]);
            cappedBase[i] = capped;
            baseSum += capped;
        }

        int adjustedTarget = Math.max(target, len);
        if (adjustedTarget >= baseSum) {
            for (int i = 0; i < len; i++) {
                mins[i] = cappedBase[i];
            }
            return mins;
        }

        double scale = (double) adjustedTarget / (double) baseSum;
        int sum = 0;
        for (int i = 0; i < len; i++) {
            double scaled = cappedBase[i] * scale;
            if (scaled < 1) {
                scaled = 1;
            }
            if (scaled > maxes[i]) {
                scaled = maxes[i];
            }
            mins[i] = (int) Math.floor(scaled);
            if (mins[i] < 1) {
                mins[i] = 1;
            }
            if (mins[i] > maxes[i]) {
                mins[i] = maxes[i];
            }
            fractional[i] = scaled - mins[i];
            sum += mins[i];
        }

        while (sum < adjustedTarget) {
            int bestIdx = -1;
            double bestFrac = -1.0;
            for (int i = 0; i < len; i++) {
                if (mins[i] >= maxes[i]) {
                    continue;
                }
                double frac = fractional[i];
                if (frac > bestFrac) {
                    bestFrac = frac;
                    bestIdx = i;
                }
            }
            if (bestIdx == -1) {
                break;
            }
            mins[bestIdx]++;
            sum++;
        }

        while (sum > adjustedTarget) {
            int idx = -1;
            int highest = Integer.MIN_VALUE;
            for (int i = 0; i < len; i++) {
                if (mins[i] > 1 && mins[i] > highest) {
                    highest = mins[i];
                    idx = i;
                }
            }
            if (idx == -1) {
                break;
            }
            mins[idx]--;
            sum--;
        }

        return mins;
    }

    private void reduceValuesToTarget(int[] values, int target) {
        int sum = Arrays.stream(values).sum();
        while (sum > target) {
            int idx = -1;
            int highest = Integer.MIN_VALUE;
            for (int i = 0; i < values.length; i++) {
                if (values[i] > 1 && values[i] > highest) {
                    highest = values[i];
                    idx = i;
                }
            }
            if (idx == -1) {
                break;
            }
            values[idx]--;
            sum--;
        }
    }

    private void clampBaseStatsToByte(Species pk) {
        pk.setHp(clampStat(pk.getHp(), 1, 255));
        pk.setAttack(clampStat(pk.getAttack(), 1, 255));
        pk.setDefense(clampStat(pk.getDefense(), 1, 255));
        pk.setSpatk(clampStat(pk.getSpatk(), 1, 255));
        pk.setSpdef(clampStat(pk.getSpdef(), 1, 255));
        pk.setSpeed(clampStat(pk.getSpeed(), 1, 255));
        int special = pk.getSpecial();
        if (special != 0) {
            pk.setSpecial(clampStat(special, 1, 255));
        }
    }

    protected int clampStat(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    public void standardizeEXPCurves() {
        Settings.ExpCurveMod mod = settings.getExpCurveMod();
        ExpCurve expCurve = settings.getSelectedEXPCurve();

        SpeciesSet pokes = romHandler.getSpeciesSetInclFormes();
        switch (mod) {
            case LEGENDARIES:
                for (Species pk : pokes) {
                    pk.setGrowthCurve(pk.isLegendary() ? ExpCurve.SLOW : expCurve);
                }
                break;
            case STRONG_LEGENDARIES:
                for (Species pk : pokes) {
                    pk.setGrowthCurve(pk.isStrongLegendary() ? ExpCurve.SLOW : expCurve);
                }
                break;
            case ALL:
                for (Species pk : pokes) {
                    pk.setGrowthCurve(expCurve);
                }
                break;
        }
    }

}
