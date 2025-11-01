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

        // Parse input CSVs
        List<Schedule> schedules = readSchedules(resourceInfo.getTransportSchedulePath());
        List<Request> requests = readRequests(resourceInfo.getCustomerRequestPath());

        // Build adjacency list for graph
        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (int i = 0; i < schedules.size(); i++) {
            adj.put(i, new ArrayList<>());
        }

        // Build edges: connect schedule i to schedule j if destination of i = source of j
        // and departure time of j >= arrival time of i (valid connection)
        for (int i = 0; i < schedules.size(); i++) {
            Schedule a = schedules.get(i);
            for (int j = 0; j < schedules.size(); j++) {
                if (i == j) continue;
                Schedule b = schedules.get(j);
                if (a.destination.equalsIgnoreCase(b.source) && b.departure >= a.arrival) {
                    adj.get(i).add(j);
                }
            }
        }

        Map<String, OptimalTravelSchedule> result = new LinkedHashMap<>();

        // Process each customer request
        for (Request r : requests) {
            OptimalTravelSchedule best = findOptimalRoute(r, schedules, adj);

            if (generateSummary) {
                // Generate AI summary using HuggingFace
                String routeDesc = buildRouteDescription(best, r);
                String aiSummary = HuggingFaceClient.generateTravelSummary(routeDesc);
                best.setSummary(aiSummary);
            } else {
                best.setSummary("Summary not generated");
            }

            result.put(r.requestId, best);
        }

        return result;
    }

    /**
     * Build route description for HuggingFace prompt
     */
    
    private String buildRouteDescription(OptimalTravelSchedule schedule, Request request) {
        StringBuilder desc = new StringBuilder();
        
        if (schedule.getRoutes().isEmpty()) {
            desc.append("No route available from ").append(request.source)
                .append(" to ").append(request.destination);
        } else {
            desc.append("Traveler journey from ").append(request.source)
                .append(" to ").append(request.destination)
                .append(". Total cost: ").append(schedule.getValue())
                .append(". Transportation: ");
            
            for (int i = 0; i < schedule.getRoutes().size(); i++) {
                Route route = schedule.getRoutes().get(i);
                if (i > 0) {
                    desc.append("; then ");
                }
                desc.append(route.getMode()).append(" from ")
                    .append(route.getSource()).append(" to ")
                    .append(route.getDestination()).append(" (")
                    .append(route.getDepartureTime()).append(" - ")
                    .append(route.getArrivalTime()).append(")");
            }
            desc.append(".");
        }

        return desc.toString();
    }


    /**
     * Find optimal route based on criteria using Dijkstra's algorithm with tie-breaking
     */
    private OptimalTravelSchedule findOptimalRoute(Request r, List<Schedule> schedules, Map<Integer, List<Integer>> adj) {

        String criteria = r.criteria.trim().toLowerCase();
        List<Route> bestRouteList = new ArrayList<>();
        long bestValue = 0;

        // Find all starting schedules (source matches request source)
        List<Integer> startNodes = new ArrayList<>();
        Set<Integer> destNodes = new HashSet<>();

        for (int i = 0; i < schedules.size(); i++) {
            Schedule s = schedules.get(i);
            if (s.source.equalsIgnoreCase(r.source)) {
                startNodes.add(i);
            }
            if (s.destination.equalsIgnoreCase(r.destination)) {
                destNodes.add(i);
            }
        }

        // No valid route if no start or destination nodes
        if (startNodes.isEmpty() || destNodes.isEmpty()) {
            return new OptimalTravelSchedule(Collections.emptyList(), r.criteria, 0, "No valid route found");
        }

        // Priority queue state for Dijkstra
        class State implements Comparable<State> {
            int node;
            int primary, secondary, tertiary;
            int startDeparture;
            List<Integer> path;

            State(int n, int p, int s, int t, int start, List<Integer> path) {
                node = n;
                primary = p;
                secondary = s;
                tertiary = t;
                startDeparture = start;
                this.path = path;
            }

            @Override
            public int compareTo(State o) {
                // Compare by primary criterion
                if (primary != o.primary) {
                    return Integer.compare(primary, o.primary);
                }
                // Tie-break by secondary criterion
                if (secondary != o.secondary) {
                    return Integer.compare(secondary, o.secondary);
                }
                // Tie-break by tertiary criterion
                if (tertiary != o.tertiary) {
                    return Integer.compare(tertiary, o.tertiary);
                }
                return 0;
            }
        }

        PriorityQueue<State> pq = new PriorityQueue<>();
        Map<Integer, int[]> bestMetrics = new HashMap<>();

        // Initialize with all starting schedules
        for (int sIdx : startNodes) {
            Schedule s = schedules.get(sIdx);
            int time = s.arrival - s.departure; // travel time for this segment
            int cost = s.cost;
            int hops = 1;

            int p, se, t;
            switch (criteria) {
                case "time":
                    p = time;
                    se = cost;
                    t = hops;
                    break;
                case "cost":
                    p = cost;
                    se = time;
                    t = hops;
                    break;
                case "hops":
                    p = hops;
                    se = time;
                    t = cost;
                    break;
                default:
                    p = time;
                    se = cost;
                    t = hops;
                    break;
            }

            pq.add(new State(sIdx, p, se, t, s.departure, new ArrayList<>(Arrays.asList(sIdx))));
            bestMetrics.put(sIdx, new int[]{p, se, t});
        }

        State best = null;

        // Dijkstra's algorithm with multi-criteria optimization
        while (!pq.isEmpty()) {
            State cur = pq.poll();

            // Check if current state reaches destination
            if (destNodes.contains(cur.node)) {
                if (best == null || cur.compareTo(best) < 0) {
                    best = cur;
                }
            }

            // Expand neighbors
            for (int nb : adj.get(cur.node)) {
                Schedule a = schedules.get(cur.node);
                Schedule b = schedules.get(nb);

                // Validate time order (no backwards time travel)
                if (b.departure < a.arrival) {
                    continue;
                }

                int newPrimary = cur.primary;
                int newSecondary = cur.secondary;
                int newTertiary = cur.tertiary;

                // Update metrics based on criteria
                switch (criteria) {
                    case "time":
                        // Primary: total time from start to destination
                        newPrimary = b.arrival - cur.startDeparture;
                        // Secondary: total cost accumulated
                        newSecondary = cur.secondary + b.cost;
                        // Tertiary: number of hops
                        newTertiary = cur.tertiary + 1;
                        break;

                    case "cost":
                        // Primary: total cost
                        newPrimary = cur.primary + b.cost;
                        // Secondary: total time from start
                        newSecondary = b.arrival - cur.startDeparture;
                        // Tertiary: number of hops
                        newTertiary = cur.tertiary + 1;
                        break;

                    case "hops":
                        // Primary: number of hops
                        newPrimary = cur.primary + 1;
                        // Secondary: total time from start
                        newSecondary = b.arrival - cur.startDeparture;
                        // Tertiary: total cost
                        newTertiary = cur.tertiary + b.cost;
                        break;
                }

                List<Integer> newPath = new ArrayList<>(cur.path);
                newPath.add(nb);

                // Check if this state is better than previously seen state for this node
                int[] seen = bestMetrics.get(nb);
                if (seen == null || better(newPrimary, newSecondary, newTertiary, seen)) {
                    bestMetrics.put(nb, new int[]{newPrimary, newSecondary, newTertiary});
                    pq.add(new State(nb, newPrimary, newSecondary, newTertiary, cur.startDeparture, newPath));
                }
            }
        }

        // No route found
        if (best == null) {
            return new OptimalTravelSchedule(Collections.emptyList(), r.criteria, 0, "No route found");
        }

        // Build output route list from the best path
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

    /**
     * Check if (a1, b1, c1) is better than (a2, b2, c2)
     * Returns true if first tuple is lexicographically smaller
     */
    private boolean better(int a1, int b1, int c1, int[] other) {
        if (a1 != other[0]) {
            return a1 < other[0];
        }
        if (b1 != other[1]) {
            return b1 < other[1];
        }
        if (c1 != other[2]) {
            return c1 < other[2];
        }
        return false;
    }

    /**
     * Convert minutes since midnight to HH:mm format
     */
    private String toTimeString(int minutes) {
        int h = (minutes / 60) % 24;
        int m = minutes % 60;
        return String.format("%02d:%02d", h, m);
    }

    /**
     * Parse Schedules.csv file
     * Format: Source, Destination, Mode, DepartureTime, ArrivalTime, Cost
     */
    private List<Schedule> readSchedules(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        List<Schedule> list = new ArrayList<>();
        boolean first = true;
        for (String line : lines) {
            if (first) {
                first = false;
                continue; // Skip header row
            }
            String[] parts = line.split(",");
            if (parts.length < 6) {
                continue; // Skip malformed rows
            }
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

    /**
     * Parse CustomerRequests.csv file
     * Format: RequestID, CustomerName, Source, Destination, Criteria
     */
    private List<Request> readRequests(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        List<Request> list = new ArrayList<>();
        boolean first = true;
        for (String line : lines) {
            if (first) {
                first = false;
                continue; // Skip header row
            }
            String[] parts = line.split(",");
            if (parts.length < 5) {
                continue; // Skip malformed rows
            }
            Request r = new Request(
                parts[0].trim(),
                parts[1].trim(),
                parts[2].trim(),
                parts[3].trim(),
                parts[4].trim()
            );
            list.add(r);
        }
        return list;
    }

    /**
     * Convert HH:mm time format to minutes since midnight
     */
    private int toMinutes(String t) {
        String[] p = t.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    // ==================== Inner Helper Classes ====================

    /**
     * Schedule data model
     */
    class Schedule {
        String source, destination, mode;
        int departure, arrival, cost;

        Schedule(String s, String d, String m, int dep, int arr, int c) {
            source = s;
            destination = d;
            mode = m;
            departure = dep;
            arrival = arr;
            cost = c;
        }
    }

    /**
     * Customer Request data model
     */
    class Request {
        String requestId, customerName, source, destination, criteria;

        Request(String id, String name, String s, String d, String c) {
            requestId = id;
            customerName = name;
            source = s;
            destination = d;
            criteria = c;
        }
    }
}
