package com.nice.avishkar;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class RouteOptimizerTest {
    public static void main(String[] args) {
        try {
            // Get the gen_trip_summary flag from args or default to true
            boolean genTripSummary = args.length > 0 ? Boolean.parseBoolean(args[0]) : true;
            
            Path schedulePath = Path.of("src/main/resources/TestCase-1/Schedules.csv"); 
            Path requestPath = Path.of("src/main/resources/TestCase-1/CustomerRequests.csv");

            ResourceInfo info = new ResourceInfo(schedulePath, requestPath);
            TravelOptimizerImpl optimizer = new TravelOptimizerImpl(genTripSummary);

            Map<String, OptimalTravelSchedule> result = optimizer.getOptimalTravelOptions(info);

            System.out.println("===== Route Optimization Results =====");
            for (Map.Entry<String, OptimalTravelSchedule> entry : result.entrySet()) {
                System.out.println("\nRequest ID: " + entry.getKey());
                OptimalTravelSchedule sch = entry.getValue();

                System.out.println("Criteria: " + sch.getCriteria());
                System.out.println("Value: " + sch.getValue());
                System.out.println("AI Summary: " + sch.getSummary());
                System.out.println("Route:");
                for (Route rt : sch.getRoutes()) {
                    System.out.printf("   %s -> %s via %s [%s - %s]%n",
                            rt.getSource(), rt.getDestination(), rt.getMode(),
                            rt.getDepartureTime(), rt.getArrivalTime());
                }
            }

            System.out.println("\n===== End of Test =====");

        } catch (IOException e) {
            System.err.println("Error reading files: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

