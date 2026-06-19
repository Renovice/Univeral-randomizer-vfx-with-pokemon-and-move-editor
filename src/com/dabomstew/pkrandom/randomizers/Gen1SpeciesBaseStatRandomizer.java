package com.dabomstew.pkrandom.randomizers;

import com.dabomstew.pkrandom.Settings;
import com.dabomstew.pkromio.gamedata.Species;
import com.dabomstew.pkromio.romhandlers.RomHandler;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * A {@link SpeciesBaseStatRandomizer} for Generation 1, taking the unified Special stat into account.
 */
public class Gen1SpeciesBaseStatRandomizer extends SpeciesBaseStatRandomizer {

    public Gen1SpeciesBaseStatRandomizer(RomHandler romHandler, Settings settings, Random random) {
        super(romHandler, settings, random);
    }

    @Override
    protected void putShuffledStatsOrder(Species pk) {
        List<Integer> order = Arrays.asList(0, 1, 2, 3, 4);
        Collections.shuffle(order, random);
        shuffledStatsOrders.put(pk, order);
    }

    @Override
    protected void applyShuffledOrderToStats(Species pk) {
        if (shuffledStatsOrders.containsKey(pk)) {
            List<Integer> order = shuffledStatsOrders.get(pk);
            List<Integer> stats = Arrays.asList(
                    clampStat(pk.getHp(), 1, 255),
                    clampStat(pk.getAttack(), 1, 255),
                    clampStat(pk.getDefense(), 1, 255),
                    clampStat(pk.getSpeed(), 1, 255),
                    clampStat(pk.getSpecial(), 1, 255)
            );
            pk.setHp(stats.get(order.get(0)));
            pk.setAttack(stats.get(order.get(1)));
            pk.setDefense(stats.get(order.get(2)));
            pk.setSpeed(stats.get(order.get(3)));
            pk.setSpecial(stats.get(order.get(4)));
        }
    }

    @Override
    protected void randomizeStatsWithinBST(Species pk) {
        int[] baseMins = new int[]{MIN_HP, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT};
        int[] maxes = new int[]{255, 255, 255, 255, 255};
        double[] weights = new double[]{random.nextDouble(), random.nextDouble(), random.nextDouble(),
                random.nextDouble(), random.nextDouble()};
        int[] stats = distributeStatsWithCaps(pk.getBST(), baseMins, maxes, weights);
        pk.setHp(stats[0]);
        pk.setAttack(stats[1]);
        pk.setDefense(stats[2]);
        pk.setSpeed(stats[3]);
        pk.setSpecial(stats[4]);
    }

    @Override
    protected void assignNewStatsForEvolution(Species from, Species to) {
        double bstDiff = to.getBST() - from.getBST();

        // Make weightings
        double hpW = random.nextDouble(), atkW = random.nextDouble(), defW = random.nextDouble();
        double specW = random.nextDouble(), speW = random.nextDouble();

        double totW = hpW + atkW + defW + specW + speW;

        double hpDiff = Math.round((hpW / totW) * bstDiff);
        double atkDiff = Math.round((atkW / totW) * bstDiff);
        double defDiff = Math.round((defW / totW) * bstDiff);
        double specDiff = Math.round((specW / totW) * bstDiff);
        double speDiff = Math.round((speW / totW) * bstDiff);

        to.setHp((int) Math.min(255, Math.max(1, from.getHp() + hpDiff)));
        to.setAttack((int) Math.min(255, Math.max(1, from.getAttack() + atkDiff)));
        to.setDefense((int) Math.min(255, Math.max(1, from.getDefense() + defDiff)));
        to.setSpeed((int) Math.min(255, Math.max(1, from.getSpeed() + speDiff)));
        to.setSpecial((int) Math.min(255, Math.max(1, from.getSpecial() + specDiff)));
    }

    @Override
    protected void copyRandomizedStatsUpEvolution(Species from, Species to) {
        double bstRatio = (double) to.getBST() / (double) from.getBST();

        to.setHp((int) Math.min(255, Math.max(1, Math.round(from.getHp() * bstRatio))));
        to.setAttack((int) Math.min(255, Math.max(1, Math.round(from.getAttack() * bstRatio))));
        to.setDefense((int) Math.min(255, Math.max(1, Math.round(from.getDefense() * bstRatio))));
        to.setSpeed((int) Math.min(255, Math.max(1, Math.round(from.getSpeed() * bstRatio))));
        to.setSpecial((int) Math.min(255, Math.max(1, Math.round(from.getSpecial() * bstRatio))));
    }

    @Override
    protected void applyNewBST(Species pk, int newBST, Settings.GateDistribution distribution) {
        // Gen 1 uses the unified Special stat instead of Sp.Atk/Sp.Def, and BST is the sum
        // of HP/Atk/Def/Special/Speed. Redistribute onto those 5 stats.
        int[] baseMins = new int[]{MIN_HP, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT, MIN_NON_HP_STAT};
        int[] maxes = new int[]{255, 255, 255, 255, 255};
        int[] current = new int[]{pk.getHp(), pk.getAttack(), pk.getDefense(), pk.getSpeed(), pk.getSpecial()};

        double[] weights = new double[5];
        switch (distribution) {
            case EVEN:
                for (int i = 0; i < 5; i++) {
                    weights[i] = 1.0;
                }
                break;
            case RANDOM:
                for (int i = 0; i < 5; i++) {
                    weights[i] = random.nextDouble();
                }
                break;
            case PROPORTIONAL:
            default:
                for (int i = 0; i < 5; i++) {
                    weights[i] = Math.max(current[i], 1);
                }
                break;
        }

        int[] stats = distributeStatsWithCaps(newBST, baseMins, maxes, weights);
        pk.setHp(clampStat(stats[0], MIN_HP, 255));
        pk.setAttack(clampStat(stats[1], 1, 255));
        pk.setDefense(clampStat(stats[2], 1, 255));
        pk.setSpeed(clampStat(stats[3], 1, 255));
        pk.setSpecial(clampStat(stats[4], 1, 255));
    }
}
