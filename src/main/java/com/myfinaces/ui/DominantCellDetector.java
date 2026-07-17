package com.myfinaces.ui;

import java.util.OptionalInt;

/**
 * DominantCellDetector encapsulates the logic for determining which cell
 * in a row should be highlighted as the "Dominant Cell".
 * 
 * The Dominant Cell is a passive visual signal that helps users identify
 * the most representative value in a row without interrupting natural reading.
 * 
 * Design Philosophy:
 * - Passive signal, never a protagonist
 * - Subordinate to TOTAL, current month, user selection, and hover
 * - Extremely subtle visual treatment
 * - Recalculated on each table rebuild, no state preservation
 */
public final class DominantCellDetector {

    private DominantCellDetector() {
    }

    /**
     * Result of dominant cell detection for a row.
     * 
     * @param monthIndex the 1-based month index (1-12) where the dominant cell exists,
     *                   or empty if no dominant cell exists
     */
    public record Result(OptionalInt monthIndex) {
        public boolean hasDominant() {
            return monthIndex.isPresent();
        }

        public int getMonth() {
            return monthIndex.orElse(-1);
        }

        public static Result none() {
            return new Result(OptionalInt.empty());
        }

        public static Result of(int month) {
            return new Result(OptionalInt.of(month));
        }
    }

    /**
     * Determines the dominant cell for a row based on monthly values.
     * 
     * Rules for dominance:
     * - Only considers months 1-12 (not TOTAL or AVERAGE)
     * - Must have at least one non-zero value
     * - Must have a unique maximum (no ties)
     * - No ambiguity
     * - If in doubt, no dominant cell
     * 
     * @param monthlyValues array of 13 long values where index 1-12 represent January-December
     * @return Result indicating which month (1-12) is dominant, or none if no dominant cell exists
     */
    public static Result findDominantCell(long[] monthlyValues) {
        if (monthlyValues == null || monthlyValues.length < 13) {
            return Result.none();
        }

        // Check for at least one non-zero value
        boolean hasNonZero = false;
        for (int m = 1; m <= 12; m++) {
            if (monthlyValues[m] != 0) {
                hasNonZero = true;
                break;
            }
        }

        if (!hasNonZero) {
            return Result.none();
        }

        // Find the maximum value and its position
        long maxVal = Long.MIN_VALUE;
        int maxMonth = -1;
        int maxCount = 0;

        for (int m = 1; m <= 12; m++) {
            if (monthlyValues[m] > maxVal) {
                maxVal = monthlyValues[m];
                maxMonth = m;
                maxCount = 1;
            } else if (monthlyValues[m] == maxVal) {
                maxCount++;
            }
        }

        // No dominant if there's a tie (multiple maxima)
        if (maxCount > 1) {
            return Result.none();
        }

        // No dominant if the maximum is zero (shouldn't happen due to hasNonZero check, but safe)
        if (maxVal == 0) {
            return Result.none();
        }

        return Result.of(maxMonth);
    }

    /**
     * Determines the dominant cell for a row based on monthly values,
     * with support for filtering to a subset of months.
     * 
     * This overload allows future extensibility for scenarios where
     * only certain months should be considered (e.g., elapsed months only).
     * 
     * @param monthlyValues array of 13 long values where index 1-12 represent January-December
     * @param considerMonth predicate that returns true if a month should be considered
     * @return Result indicating which month (1-12) is dominant, or none if no dominant cell exists
     */
    public static Result findDominantCell(long[] monthlyValues, java.util.function.IntPredicate considerMonth) {
        if (monthlyValues == null || monthlyValues.length < 13) {
            return Result.none();
        }

        // Check for at least one non-zero value in considered months
        boolean hasNonZero = false;
        for (int m = 1; m <= 12; m++) {
            if (considerMonth.test(m) && monthlyValues[m] != 0) {
                hasNonZero = true;
                break;
            }
        }

        if (!hasNonZero) {
            return Result.none();
        }

        // Find the maximum value and its position among considered months
        long maxVal = Long.MIN_VALUE;
        int maxMonth = -1;
        int maxCount = 0;

        for (int m = 1; m <= 12; m++) {
            if (!considerMonth.test(m)) {
                continue;
            }
            if (monthlyValues[m] > maxVal) {
                maxVal = monthlyValues[m];
                maxMonth = m;
                maxCount = 1;
            } else if (monthlyValues[m] == maxVal) {
                maxCount++;
            }
        }

        // No dominant if there's a tie
        if (maxCount > 1) {
            return Result.none();
        }

        // No dominant if the maximum is zero
        if (maxVal == 0) {
            return Result.none();
        }

        return Result.of(maxMonth);
    }
}
