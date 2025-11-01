package com.nice.avishkar;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public class TravelOptimizerImpl implements ITravelOptimizer {

    private boolean generateSummary;

    public TravelOptimizerImpl(boolean generateSummary) {
        this.generateSummary = generateSummary;
    }

    @Override
    public Map<String, OptimalTravelSchedule> getOptimalTravelOptions(ResourceInfo resourceInfo) throws IOException {

        //  Parse input CSVs
        List<Schedule> schedules = readSchedules(resourceInfo.getTransportSchedulePath());
        List<Request> requests = readRequests(resourceInfo.getCustomerRequestPath());

        //  Build adjacency
        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (int i = 0; i < schedules.size(); i++) adj.put(i, new ArrayList<>());
        for (int i = 0; i < schedules.size(); i++) {
            Schedule a = schedules.get(i);
            for (int j = 0; j < schedules.size(); j++) {
                if (i == j) continue;
                Schedule b = schedules.get(j);
                if (a.destination.equals(b.source) && b.departure >= a.arrival)
                    adj.get(i).add(j);
            }
        }

        Map<String, OptimalTravelSchedule> result = new LinkedHashMap<>();

        //  Process each customer request
        for (Request r : requests) {
            OptimalTravelSchedule best = findOptimalRoute(r, schedules, adj);
            if (generateSummary) {
                best.setSummary("Optimal route found for " + r.customerName + " with " + best.getCriteria() + " = " + best.getValue());
            } else {
                best.setSummary("Summary not generated");
            }
            result.put(r.requestId, best);
        }

        return result;
    }

    private OptimalTravelSchedule findOptimalRoute(Request r, List<Schedule> schedules, Map<Integer, List<Integer>> adj) {

        String criteria = r.criteria.trim().toLowerCase();
        List<Route> bestRouteList = new ArrayList<>();
        long bestValue = 0;

        // Find all starting and target schedules
        List<Integer> startNodes = new ArrayList<>();
        Set<Integer> destNodes = new HashSet<>();

        for (int i = 0; i < schedules.size(); i++) {
            Schedule s = schedules.get(i);
            if (s.source.equalsIgnoreCase(r.source)) startNodes.add(i);
            if (s.destination.equalsIgnoreCase(r.destination)) destNodes.add(i);
        }

        if (startNodes.isEmpty() || destNodes.isEmpty()) {
            return new OptimalTravelSchedule(Collections.emptyList(), r.criteria, 0, "No valid route found");
        }

        // Priority queue of states
        class State implements Comparable<State> {
            int node;
            int primary, secondary, tertiary;
            int startDeparture;
            List<Integer> path;
            State(int n, int p, int s, int t, int start, List<Integer> path) {
                node = n; primary = p; secondary = s; tertiary = t; startDeparture = start; this.path = path;
            }

            @Override
            public int compareTo(State o) {
                if (primary != o.primary) return Integer.compare(primary, o.primary);
                if (secondary != o.secondary) return Integer.compare(secondary, o.secondary);
                if (tertiary != o.tertiary) return Integer.compare(tertiary, o.tertiary);
                return 0;
            }
        }

        PriorityQueue<State> pq = new PriorityQueue<>();
        Map<Integer, int[]> bestMetrics = new HashMap<>();

        // Push all starting schedules
        for (int sIdx : startNodes) {
            Schedule s = schedules.get(sIdx);
            int time = s.arrival - s.departure;
            int cost = s.cost;
            int hops = 1;
            int p, se, t;
            switch (criteria) {
                case "time":  p = time; se = cost; t = hops; break;
                case "cost":  p = cost; se = time; t = hops; break;
                case "hops":  p = hops; se = time; t = cost; break;
                default:      p = time; se = cost; t = hops; break;
            }
            pq.add(new State(sIdx, p, se, t, s.departure, new ArrayList<>(Arrays.asList(sIdx))));
            bestMetrics.put(sIdx, new int[]{p, se, t});
        }

        State best = null;

        while (!pq.isEmpty()) {
            State cur = pq.poll();

            // Check for destination
            if (destNodes.contains(cur.node)) {
                if (best == null || cur.compareTo(best) < 0) best = cur;
            }

            // Expand neighbours
            for (int nb : adj.get(cur.node)) {
                Schedule a = schedules.get(cur.node);
                Schedule b = schedules.get(nb);

                // only valid if time order is respected
                if (b.departure < a.arrival) continue;

                int newHops = cur.tertiary == 0 ? 1 : cur.tertiary + 1; // for clarity

                int newPrimary = cur.primary;
                int newSecondary = cur.secondary;
                int newTertiary = cur.tertiary;

                switch (criteria) {
                    case "time":
                        newPrimary = b.arrival - cur.startDeparture;
                        newSecondary = cur.secondary + b.cost;
                        newTertiary = cur.tertiary + 1;
                        break;
                    case "cost":
                        newPrimary = cur.primary + b.cost;
                        newSecondary = b.arrival - cur.startDeparture;
                        newTertiary = cur.tertiary + 1;
                        break;
                    case "hops":
                        newPrimary = cur.primary + 1;
                        newSecondary = b.arrival - cur.startDeparture;
                        newTertiary = cur.tertiary + b.cost;
                        break;
                }

                List<Integer> newPath = new ArrayList<>(cur.path);
                newPath.add(nb);

                int[] seen = bestMetrics.get(nb);
                if (seen == null || better(newPrimary, newSecondary, newTertiary, seen)) {
                    bestMetrics.put(nb, new int[]{newPrimary, newSecondary, newTertiary});
                    pq.add(new State(nb, newPrimary, newSecondary, newTertiary, cur.startDeparture, newPath));
                }
            }
        }

        if (best == null) {
            return new OptimalTravelSchedule(Collections.emptyList(), r.criteria, 0, "No route found");
        }

        // Build output route list
        for (int idx : best.path) {
            Schedule s = schedules.get(idx);
            bestRouteList.add(new Route(
                            s.source,
                            s.destination,
                            s.mode,
                            toTimeString(s.departure),
                            toTimeString(s.arrival)
            ));

        }

        bestValue = best.primary;

        return new OptimalTravelSchedule(bestRouteList, r.criteria, bestValue, "Route computed successfully");
    }

    // Utility: check if (a1,b1,c1) is better than (a2,b2,c2)
    private boolean better(int a1, int b1, int c1, int[] other) {
        if (a1 != other[0]) return a1 < other[0];
        if (b1 != other[1]) return b1 < other[1];
        if (c1 != other[2]) return c1 < other[2];
        return false;
    }

    // Convert minutes to HH:mm
    private String toTimeString(int minutes) {
        int h = (minutes / 60) % 24;
        int m = minutes % 60;
        return String.format("%02d:%02d", h, m);
    }


    // CSV parsing
    private List<Schedule> readSchedules(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        List<Schedule> list = new ArrayList<>();
        boolean first = true;
        for (String line : lines) {
            if (first) { first = false; continue; }
            String[] parts = line.split(",");
            if (parts.length < 6) continue;
            Schedule s = new Schedule(
                parts[0].trim(),
                parts[1].trim(),
                parts[2].trim(),
                toMinutes(parts[3].trim()),
                toMinutes(parts[4].trim()),
                Integer.parseInt(parts[5].trim())
            );
            list.add(s);
        }
        return list;
    }

    private List<Request> readRequests(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        List<Request> list = new ArrayList<>();
        boolean first = true;
        for (String line : lines) {
            if (first) { first = false; continue; }
            String[] parts = line.split(",");
            if (parts.length < 5) continue;
            Request r = new Request(parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim(), parts[4].trim());
            list.add(r);
        }
        return list;
    }

    private int toMinutes(String t) {
        String[] p = t.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    // inner helper classes
    class Schedule {
        String source, destination, mode;
        int departure, arrival, cost;
        Schedule(String s, String d, String m, int dep, int arr, int c) {
            source=s; destination=d; mode=m; departure=dep; arrival=arr; cost=c;
        }
    }

    class Request {
        String requestId, customerName, source, destination, criteria;
        Request(String id, String name, String s, String d, String c) {
            requestId=id; customerName=name; source=s; destination=d; criteria=c;
        }
    }
}
