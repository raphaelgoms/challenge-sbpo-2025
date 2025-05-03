package org.sbpo2025.challenge;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.time.StopWatch;

public class GraspSolver {
    private final List<Map<Integer, Integer>> orders;
    private final List<Map<Integer, Integer>> aisles;
    private final int nItems;
    private final int waveSizeLB;
    private final int waveSizeUB;
    private final int maxIterations;
    private final long maxRuntimeMillis;

    public GraspSolver(List<Map<Integer, Integer>> orders,
                       List<Map<Integer, Integer>> aisles,
                       int nItems,
                       int waveSizeLB,
                       int waveSizeUB,
                       int maxIterations,
                       long maxRuntimeMillis) {
        this.orders = orders;
        this.aisles = aisles;
        this.nItems = nItems;
        this.waveSizeLB = waveSizeLB;
        this.waveSizeUB = waveSizeUB;
        this.maxIterations = maxIterations;
        this.maxRuntimeMillis = maxRuntimeMillis;
    }

    public ChallengeSolution run(StopWatch stopWatch) {
        ChallengeSolution best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < maxIterations; i++) {
            long remaining = getRemainingTime(stopWatch);
            //System.out.println("i = " + i + ", remaining = " + remaining);

            if (remaining <= 0) break;

            ChallengeSolution candidate = constructGreedyRandomizedSolution();
            candidate = localSearch(candidate);
            double score = evaluate(candidate);

            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }

        System.out.println("Objective function value: " + bestScore);
        System.out.println("Is viable: " + isSolutionFeasible(best));
        System.out.println();
        return best;
    }

    protected long getRemainingTime(StopWatch stopWatch) {
        return Math.max(
                TimeUnit.SECONDS.convert(maxRuntimeMillis - stopWatch.getTime(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS),
                0);
    }

private ChallengeSolution constructGreedyRandomizedSolution() {
    Random rand = new Random();
    List<Integer> allOrderIndices = new ArrayList<>();
    for (int i = 0; i < orders.size(); i++) {
        allOrderIndices.add(i);
    }

    Collections.shuffle(allOrderIndices, rand);

    Set<Integer> selectedOrders = new HashSet<>();
    Map<Integer, Integer> totalDemand = new HashMap<>(); // item -> quantidade acumulada

    for (int orderId : allOrderIndices) {
        if (selectedOrders.size() >= waveSizeUB) break;

        Map<Integer, Integer> order = orders.get(orderId);

        // Verifica se adicionar esse pedido ultrapassa a capacidade disponível
        boolean feasible = true;
        for (Map.Entry<Integer, Integer> entry : order.entrySet()) {
            int itemId = entry.getKey();
            int quantity = entry.getValue();

            int currentDemand = totalDemand.getOrDefault(itemId, 0);
            int available = totalItemAvailability(itemId);

            if (currentDemand + quantity > available) {
                feasible = false;
                break;
            }
        }

        if (!feasible) continue;

        // Se for viável, adiciona o pedido e atualiza a demanda
        selectedOrders.add(orderId);
        for (Map.Entry<Integer, Integer> entry : order.entrySet()) {
            int itemId = entry.getKey();
            int quantity = entry.getValue();
            totalDemand.put(itemId, totalDemand.getOrDefault(itemId, 0) + quantity);
        }

        //if (selectedOrders.size() >= waveSizeLB && rand.nextDouble() < 0.5) break;
    }

    // Calcula os corredores com base nos pedidos selecionados
    Set<Integer> selectedAisles = new HashSet<>();
    for (int orderId : selectedOrders) {
        Map<Integer, Integer> order = orders.get(orderId);
        for (int itemId : order.keySet()) {
            for (int aisleId = 0; aisleId < aisles.size(); aisleId++) {
                if (aisles.get(aisleId).containsKey(itemId)) {
                    selectedAisles.add(aisleId);
                }
            }
        }
    }

    return new ChallengeSolution(selectedOrders, selectedAisles);
}

    private int totalItemAvailability(int itemId) {
        int total = 0;
        for (Map<Integer, Integer> aisle : aisles) {
            total += aisle.getOrDefault(itemId, 0);
        }
        return total;
    }


    private ChallengeSolution localSearch(ChallengeSolution solution) {
        Set<Integer> currentOrders = solution.orders();
        Set<Integer> currentAisles = solution.aisles();
        double bestScore = evaluate(solution);
        ChallengeSolution best = solution;

        for (int o = 0; o < orders.size(); o++) {
            if (currentOrders.contains(o)) continue;

            Set<Integer> newOrders = new HashSet<>(currentOrders);
            newOrders.add(o);

            Map<Integer, Integer> itemCounts = computeItemTotals(newOrders);
            int total = itemCounts.values().stream().mapToInt(Integer::intValue).sum();

            if (total >= waveSizeLB && total <= waveSizeUB) {
                Set<Integer> newAisles = selectAislesForItems(itemCounts);
                ChallengeSolution neighbor = new ChallengeSolution(newOrders, newAisles);
                double score = evaluate(neighbor);
                if (score > bestScore) {
                    best = neighbor;
                    bestScore = score;
                }
            }
        }

        return best;
    }

    private Map<Integer, Integer> computeItemTotals(Set<Integer> orderSet) {
        Map<Integer, Integer> totals = new HashMap<>();
        for (int o : orderSet) {
            for (Map.Entry<Integer, Integer> e : orders.get(o).entrySet()) {
                totals.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        return totals;
    }

    private Set<Integer> selectAislesForItems(Map<Integer, Integer> itemCounts) {
        Set<Integer> result = new HashSet<>();
        for (int i = 0; i < nItems; i++) {
            if (!itemCounts.containsKey(i)) continue;

            int minAisle = -1;
            int minQty = Integer.MAX_VALUE;
            for (int a = 0; a < aisles.size(); a++) {
                if (aisles.get(a).containsKey(i)) {
                    int qty = aisles.get(a).get(i);
                    if (qty < minQty) {
                        minQty = qty;
                        minAisle = a;
                    }
                }
            }
            if (minAisle != -1) result.add(minAisle);
        }
        return result;
    }

    private double evaluate(ChallengeSolution solution) {
        Set<Integer> selectedOrders = solution.orders();
        Set<Integer> visitedAisles = solution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return 0.0;
        }
        int totalUnitsPicked = 0;

        // Calculate total units picked
        for (int order : selectedOrders) {
            totalUnitsPicked += orders.get(order).values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
        }

        // Calculate the number of visited aisles
        int numVisitedAisles = visitedAisles.size();

        // Objective function: total units picked / number of visited aisles
        return (double) totalUnitsPicked / numVisitedAisles;
    }

    protected boolean isSolutionFeasible(ChallengeSolution challengeSolution) {
        Set<Integer> selectedOrders = challengeSolution.orders();
        Set<Integer> visitedAisles = challengeSolution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return false;
        }

        int[] totalUnitsPicked = new int[nItems];
        int[] totalUnitsAvailable = new int[nItems];

        // Calculate total units picked
        for (int order : selectedOrders) {
            for (Map.Entry<Integer, Integer> entry : orders.get(order).entrySet()) {
                totalUnitsPicked[entry.getKey()] += entry.getValue();
            }
        }

        // Calculate total units available
        for (int aisle : visitedAisles) {
            for (Map.Entry<Integer, Integer> entry : aisles.get(aisle).entrySet()) {
                totalUnitsAvailable[entry.getKey()] += entry.getValue();
            }
        }

        // Check if the total units picked are within bounds
        int totalUnits = Arrays.stream(totalUnitsPicked).sum();
        if (totalUnits < waveSizeLB || totalUnits > waveSizeUB) {
            return false;
        }

        // Check if the units picked do not exceed the units available
        for (int i = 0; i < nItems; i++) {
            if (totalUnitsPicked[i] > totalUnitsAvailable[i]) {
                return false;
            }
        }

        return true;
    }

}
