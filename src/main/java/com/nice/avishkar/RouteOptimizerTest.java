package com.nice.avishkar;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class RouteOptimizerTest {

    public static void main(String[] args) {
        try {
            // ✅ CORRECTED PATH (remove the extra src/main/java/)
            Path schedulePath = Path.of("src/main/resources/TestCase-1/Schedules.csv"); 
            Path requestPath = Path.of("src/main/resources/TestCase-1/CustomerRequests.csv");

            System.out.println("Exists: " + java.nio.file.Files.exists(schedulePath));
            System.out.println("Absolute Path: " + schedulePath.toAbsolutePath());

            // Create ResourceInfo object
            ResourceInfo info = new ResourceInfo(schedulePath, requestPath);

            // Initialize optimizer (true = generate summary)
            TravelOptimizerImpl optimizer = new TravelOptimizerImpl(true);

            // Run the optimizer
            Map<String, OptimalTravelSchedule> result = optimizer.getOptimalTravelOptions(info);

            // Print formatted output
            System.out.println("===== Route Optimization Results =====");
            for (Map.Entry<String, OptimalTravelSchedule> entry : result.entrySet()) {
                System.out.println("\nRequest ID: " + entry.getKey());
                OptimalTravelSchedule sch = entry.getValue();

                System.out.println("Criteria: " + sch.getCriteria());
                System.out.println("Value: " + sch.getValue());
                System.out.println("Summary: " + sch.getSummary());
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
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
