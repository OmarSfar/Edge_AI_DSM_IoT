import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class DatabaseManager {
    // InfluxDB configuration
    private static final String INFLUX_URL = "http://localhost:8086";
    private static final String ORG = "myorg";
    private static final String BUCKET = "sensors";
    private static final String TOKEN = "sKI9pViursQFyefD2YlDt7-TBmgUWQ-CPQwCMKhYjUYOf8FaI21p5g4z0LJEMwyzJzNp5cn_9Hdqihq-7N4eZQ==";

    private final HttpClient httpClient;

    public DatabaseManager() {
        // Initialize HTTP client
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // Send real and predicted power
    public void insertReading(String ip, float actual, float predicted) {
        // Prepare string for InfluxDB in Line Protocol format
        String lineProtocol = String.format(java.util.Locale.US, 
            "power,sensor_ip=%s actual_value=%f,predicted_value=%f", 
            ip, actual, predicted);

        // Send data to InfluxDB via HTTP POST
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(INFLUX_URL + "/api/v2/write?org=" + ORG + "&bucket=" + BUCKET + "&precision=s"))
                .header("Authorization", "Token " + TOKEN)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(lineProtocol))
                .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            
        } catch (Exception e) {
            System.err.println("Error writing to InfluxDB: " + e.getMessage());
        }
    }

    // Send number of actve nodes and current threshold
    public void sendMetrics(int activeNodes, double currentThreshold) {
        try {
            String lineProtocolData = String.format(java.util.Locale.US, 
                "system_metrics active_nodes=%d,safety_threshold=%f", 
                activeNodes, currentThreshold);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(INFLUX_URL + "/api/v2/write?org=" + ORG + "&bucket=" + BUCKET + "&precision=s"))
                .header("Authorization", "Token " + TOKEN)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(lineProtocolData))
                .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("Error writing to InfluxDB: " + e.getMessage());
        }
    }

    public float getAveragePredictedPower() {
        try {
            // FLUX query: Gets data from the last 2 minutes, selects the latest
            // predicted value for each sensor, groups them, and calculates the mean.
            String fluxQuery = "from(bucket: \"" + BUCKET + "\")\n" +
                    "  |> range(start: -2m)\n" +
                    "  |> filter(fn: (r) => r[\"_measurement\"] == \"power\")\n" +
                    "  |> filter(fn: (r) => r[\"_field\"] == \"predicted_value\")\n" +
                    "  |> last()\n" +
                    "  |> group()\n" +
                    "  |> mean()";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(INFLUX_URL + "/api/v2/query?org=" + ORG))
                    .header("Authorization", "Token " + TOKEN)
                    .header("Content-Type", "application/vnd.flux")
                    .header("Accept", "application/csv") // InfluxDB responds in CSV format
                    .POST(HttpRequest.BodyPublishers.ofString(fluxQuery))
                    .build();

            // SYNCHRONOUS call (the Cloud waits for the DB to make a control decision)
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();

            // Parse InfluxDB CSV response
            String[] lines = body.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                // Skip CSV header rows, look for the data row
                if (!line.isEmpty() && !line.startsWith("#") && !line.contains("result,table")) {
                    String[] cols = line.split(",");
                    // The last column element is the mean value
                    return Float.parseFloat(cols[cols.length - 1]);
                }
            }
        } catch (Exception e) {
            System.err.println("Error querying InfluxDB: " + e.getMessage());
        }
        return 0f;
    }
}