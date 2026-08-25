import org.eclipse.californium.core.CoapClient;
import org.eclipse.californium.core.CoapResponse;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.CoapHandler;
import org.eclipse.californium.core.config.CoapConfig;
import org.eclipse.californium.elements.config.UdpConfig;

import java.util.Scanner;
import java.util.ArrayList;
import java.util.List;
import java.io.File;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class CloudAppDSM {

    // Map to track last heartbeat timestamp from each sensor
    private static Map<String, Long> lastSeenNodes = new ConcurrentHashMap<>();

    // Californium 3.x initialization
    static {
        CoapConfig.register();
        UdpConfig.register();
    }

    public static void main(String[] args) throws Exception {
	java.util.Properties cloudProps = new java.util.Properties();
	try (java.io.FileInputStream propStream = new java.io.FileInputStream("cloud-config.properties")) {
   	cloudProps.load(propStream);
    	System.out.println("Cloud configuration loaded successfully.");
     	} catch (Exception e) {
    	  System.err.println("Warning: cloud-config.properties not found. Using internal defaults.");
        }

	double thresholdStandard = Double.parseDouble(cloudProps.getProperty("threshold.standard", "3.5"));
	double thresholdEmergency = Double.parseDouble(cloudProps.getProperty("threshold.emergency", "3.0"));
	double thresholdHysteresis = Double.parseDouble(cloudProps.getProperty("threshold.hysteresis", "0.5"));
	long nodeOfflineTimeout = Long.parseLong(cloudProps.getProperty("timeout.node.offline", "45000"));
	long loopSleepMs = Long.parseLong(cloudProps.getProperty("loop.sleep.ms", "30000"));
        
	// Read multi-sensor configuration
        List<String> sensorIPs = new ArrayList<>();
        try {
            File configFile = new File("config.txt");
            Scanner configScanner = new Scanner(configFile);
            while (configScanner.hasNextLine()) {
                String line = configScanner.nextLine().trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    line = line.replace("[", "").replace("]", "");
                    sensorIPs.add(line);
                }
            }
            configScanner.close();
        } catch (Exception e) {
            System.err.println("Error reading config.txt");
        }
        
        System.out.println("Configured sensors: " + sensorIPs.size());
        for(String ip : sensorIPs) {
            System.out.println("  - " + ip);
        }
        
        DatabaseManager db = new DatabaseManager();

        // Thread for user input (CLI - Manual control)
        new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.println("\n[CLI] Commands: [ON <ip>, OFF <ip>, OFF-ALL, EXIT]");
                String input = scanner.nextLine().toLowerCase();
                String[] tokens = input.split(" ");
                String cmd = tokens[0];
                String targetIP = tokens.length > 1 ? tokens[1] : null;
                
                try {
                    if (cmd.equals("on") && targetIP != null) {
                        new CoapClient("coap://[" + targetIP + "]/load").put("mode=on", MediaTypeRegistry.TEXT_PLAIN);
                        System.out.println("[CLI] Command sent: ON to " + targetIP);
                    } else if (cmd.equals("off") && targetIP != null) {
                        new CoapClient("coap://[" + targetIP + "]/load").put("mode=off", MediaTypeRegistry.TEXT_PLAIN);
                        System.out.println("[CLI] Command sent: OFF to " + targetIP);
                    } else if (cmd.equals("off-all")) {
                        for(String ip : sensorIPs) {
                            new CoapClient("coap://[" + ip + "]/load").put("mode=off", MediaTypeRegistry.TEXT_PLAIN);
                        }
                        System.out.println("[CLI] OFF command sent to ALL sensors");
                    } else if (cmd.equals("exit")) {
                        System.exit(0);
                    } else {
                        System.out.println("[CLI] Unknown command");
                    }
                } catch (Exception e) {
                    System.err.println("[CLI] Error: " + e.getMessage());
                }
            }
        }).start();

        System.out.println("\n=== Cloud DSM: Multi-Sensor Observe Registration ===\n");

        // Observe thread (Passive reception and DB storage)
        for(String ipAddress : sensorIPs) {
            try {
                String uriPower = "coap://[" + ipAddress + "]/power";
                CoapClient clientPower = new CoapClient(uriPower);
                
                System.out.println("Activating Observe on sensor: " + ipAddress);

                clientPower.observe(new CoapHandler() {
                    @Override
                    public void onLoad(CoapResponse response) {
                        String content = response.getResponseText();
                        
                        float val = 0.0f;
                        float pred = 0.0f;

                        // Parse JSON payload
                        try {
                            org.json.JSONObject json = new org.json.JSONObject(content);
                            val = (float) json.getDouble("P");
                            pred = (float) json.getDouble("Pr");
                        } catch (Exception e) {
                            System.out.println("[ERROR] Failed to parse JSON payload: " + content);
                            return;
                        }
                        // Defense against inconsistent data
                        if (val < 0.0 || val > 50.0 || pred < 0.0) {
                            System.out.println("[WARNING] Ignoring INCONSISTENT data from node " + ipAddress + " -> P=" + val);
                            return;
                        }

                        // Register that the node is alive
                        lastSeenNodes.put(ipAddress, System.currentTimeMillis());

                        // Send to InfluxDB
                        db.insertReading(ipAddress, val, pred);
                        System.out.printf("[%s] Data saved to DB -> P=%.2f | Pr=%.2f\n", ipAddress, val, pred);

                    }

                    @Override
                    public void onError() {
                        System.err.println("[" + ipAddress + "] Observe connection lost!");
                    }
                });

            } catch (Exception e) {
                System.err.println("[" + ipAddress + "] Error setting up Observe: " + e.getMessage());
            }
        }
        
        // Real Closed-Loop Control based on Database with Adaptive Logic
        System.out.println("\n=== Starting Cloud Control Logic ===");
        double safetyThreshold = thresholdStandard; // Standard threshold
        while (true) {
            try {
                // Wait 30 seconds between control checks
                Thread.sleep(loopSleepMs);
                
                long currentTime = System.currentTimeMillis();
                int activeNodes = 0;

                // PHASE 1: STRESS TEST DETECTION (Node Failure)
                for(String ip : sensorIPs) {
                    long lastSeen = lastSeenNodes.getOrDefault(ip, 0L);
                    
                    if (lastSeen > 0) { // If we ever received data from this node
                        if ((currentTime - lastSeen) > nodeOfflineTimeout) { 
                           //Haven't heard from it for more than 45 seconds (It can happen for both incosistent datas or unreachable node)
                            System.out.println("[ALARM] Node " + ip + " OFFLINE! Network failure detected.");
                        } else {
                            activeNodes++; // Node is alive
                        }
                    }
                }

                // PHASE 2: ADAPTIVE MECHANISM (Dynamic Threshold)
                // If there's at least one dead node, enter emergency state
                if (activeNodes > 0 && activeNodes < sensorIPs.size()) {
		            if(safetyThreshold == thresholdStandard){
                    	System.out.println("[ADAPTIVE] Degraded network (Active nodes: " + activeNodes + "). Lowering safety threshold!");
		            }
                    safetyThreshold = thresholdEmergency; // System becomes more cautious
                }
		        else{
		            safetyThreshold = thresholdStandard;
		        }

                // Send metrics to visualize on Grafana
                db.sendMetrics(activeNodes, safetyThreshold);

                // PHASE 3: CLOSED-LOOP CONTROL
                float globalAvgPrediction = db.getAveragePredictedPower();
                
                if (globalAvgPrediction > 0) {
                    System.out.printf("[CLOUD] DB Analysis: Average consumption = %.2f kW (Current Threshold: %.1f kW)\n", globalAvgPrediction, safetyThreshold);
                }

                // Use DYNAMIC threshold to decide if we need to intervene
                if (globalAvgPrediction > safetyThreshold) { 
                    System.out.println("[CLOUD] Preventive Alert! Consumption exceeds safety threshold. Reducing loads...");
                    for(String ipAddress : sensorIPs) {
			            new CoapClient("coap://[" + ipAddress + "]/load").put(new CoapHandler() {
    			            @Override public void onLoad(CoapResponse response) {}
    			            @Override public void onError() {}
		                }, "mode=off", MediaTypeRegistry.TEXT_PLAIN);
                    }
                } else if (globalAvgPrediction < (safetyThreshold - thresholdHysteresis) && globalAvgPrediction > 0) {
                    System.out.println("[CLOUD] Network stable. Restoring standard loads.");
                    for(String ipAddress : sensorIPs) {
			            new CoapClient("coap://[" + ipAddress + "]/load").put(new CoapHandler() {
    			        @Override public void onLoad(CoapResponse response) {}
    			        @Override public void onError() {}
			            }, "mode=on", MediaTypeRegistry.TEXT_PLAIN);
                    }
                }

            } catch (Exception e) {
                System.err.println("[CLOUD] Error in control loop: " + e.getMessage());
            }
        }
    }
}