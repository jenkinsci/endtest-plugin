package io.jenkins.plugins.endtest;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * Client for communicating with the Endtest API.
 */
public class EndtestClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final String ENDTEST_API_HOST = "app.endtest.io";

    /**
     * Starts one or more Endtest executions.
     *
     * <p>A single-suite request returns one hash. A label request can return
     * multiple hashes separated by commas.</p>
     */
    public List<String> startExecutions(String apiRequest, String appId, String appCode)
            throws IOException, InterruptedException {
        URI uri = buildAuthenticatedRequest(apiRequest, appId, appCode);

        String responseBody = sendGet(uri);
        String value = cleanResponse(responseBody);

        if (value.isEmpty()) {
            throw new IOException("Endtest returned an empty execution hash.");
        }

        if (looksLikeHtml(value)) {
            throw new IOException("Endtest returned an HTML page instead of execution hashes.");
        }

        List<String> hashes = new ArrayList<>();

        for (String part : value.split(",")) {
            String hash = part.trim();

            if (hash.isEmpty()) {
                continue;
            }

            if (!hash.matches("[A-Za-z0-9]+")) {
                throw new IOException("Endtest returned an invalid execution hash.");
            }

            hashes.add(hash);
        }

        if (hashes.isEmpty()) {
            throw new IOException("Endtest did not return any execution hashes.");
        }

        return hashes;
    }

    /**
     * Fetches results for one or more Endtest execution hashes.
     */
    public ResultResponse getResults(String appId, String appCode, List<String> hashes, String resultsFormat)
            throws IOException, InterruptedException {
        validateCredentials(appId, appCode);
        validateHashes(hashes);
        validateResultsFormat(resultsFormat);

        String joinedHashes = String.join(",", hashes);

        String resultsRequest = "https://app.endtest.io/api.php"
                + "?action=getResults"
                + "&appId="
                + encode(appId)
                + "&appCode="
                + encode(appCode)
                + "&hash="
                + encode(joinedHashes)
                + "&format="
                + encode(resultsFormat);

        String responseBody = cleanResponse(sendGet(URI.create(resultsRequest)));

        if (responseBody.isEmpty()) {
            return ResultResponse.pending("Waiting for Endtest results.");
        }

        if ("Test is still running.".equals(responseBody)
                || "Processing video recording.".equals(responseBody)
                || "Stopping.".equals(responseBody)) {
            return ResultResponse.pending(responseBody);
        }

        if ("Erred.".equals(responseBody)) {
            throw new IOException("One or more Endtest executions ended with status: Erred.");
        }

        if (looksLikeHtml(responseBody)) {
            throw new IOException("Endtest returned an HTML page instead of test results.");
        }

        List<JSONObject> jsonResults = parseResultsResponse(responseBody);

        if (jsonResults.size() != hashes.size()) {
            throw new IOException("Endtest returned "
                    + jsonResults.size()
                    + " result objects for "
                    + hashes.size()
                    + " execution hashes.");
        }

        List<Map<String, Object>> executions = new ArrayList<>();

        for (int index = 0; index < jsonResults.size(); index++) {
            executions.add(parseExecutionResult(jsonResults.get(index), hashes.get(index)));
        }

        return ResultResponse.completed(buildCombinedResult(hashes, executions));
    }

    public String resolveAppId(String apiRequest, String fallbackAppId) {
        return resolveCredential(apiRequest, "appId", fallbackAppId);
    }

    public String resolveAppCode(String apiRequest, String fallbackAppCode) {
        return resolveCredential(apiRequest, "appCode", fallbackAppCode);
    }

    private String resolveCredential(String apiRequest, String parameterName, String fallbackValue) {
        URI uri = validateApiRequest(apiRequest);
        String requestValue = getQueryParameter(uri, parameterName);

        if (!requestValue.isEmpty()) {
            return requestValue;
        }

        return fallbackValue == null ? "" : fallbackValue.trim();
    }

    private String getQueryParameter(URI uri, String parameterName) {
        String query = uri.getRawQuery();

        if (query == null || query.isEmpty()) {
            return "";
        }

        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String name = decode(parts[0]);

            if (parameterName.equals(name)) {
                return parts.length > 1 ? decode(parts[1]) : "";
            }
        }

        return "";
    }

    private List<JSONObject> parseResultsResponse(String responseBody) throws IOException {
        List<JSONObject> results = new ArrayList<>();

        try {
            if (responseBody.startsWith("[")) {
                JSONArray array = JSONArray.fromObject(responseBody);

                for (int index = 0; index < array.size(); index++) {
                    results.add(array.getJSONObject(index));
                }

                return results;
            }

            if (responseBody.startsWith("{")) {
                results.add(JSONObject.fromObject(responseBody));

                return results;
            }
        } catch (RuntimeException exception) {
            throw new IOException("Endtest returned invalid JSON results.", exception);
        }

        throw new IOException("Unexpected response from Endtest: " + responseBody);
    }

    private Map<String, Object> parseExecutionResult(JSONObject json, String hash) {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("testSuiteName", readString(json, "test_suite_name"));

        result.put("configuration", readString(json, "configuration"));

        result.put("testCases", readInt(json, "test_cases"));

        result.put("passed", readInt(json, "passed"));

        result.put("failed", readInt(json, "failed"));

        result.put("errors", readInt(json, "errors"));

        result.put("startTime", readString(json, "start_time"));

        result.put("endTime", readString(json, "end_time"));

        result.put("detailedLogs", readOptionalValue(json, "detailed_logs"));

        result.put("screenshotsAndVideo", readOptionalValue(json, "screenshots_and_video"));

        result.put("testCaseManagement", readOptionalValue(json, "test_case_management"));

        result.put("hash", hash);

        result.put("resultsUrl", "https://app.endtest.io/results?hash=" + hash);

        return result;
    }

    private Map<String, Object> buildCombinedResult(List<String> hashes, List<Map<String, Object>> executions) {
        Map<String, Object> combined = new LinkedHashMap<>();

        int totalTestCases = 0;
        int totalPassed = 0;
        int totalFailed = 0;
        int totalErrors = 0;

        List<String> resultsUrls = new ArrayList<>();

        for (Map<String, Object> execution : executions) {
            totalTestCases += readNumber(execution, "testCases");

            totalPassed += readNumber(execution, "passed");

            totalFailed += readNumber(execution, "failed");

            totalErrors += readNumber(execution, "errors");

            resultsUrls.add(String.valueOf(execution.get("resultsUrl")));
        }

        combined.put("executionCount", executions.size());

        combined.put("hashes", new ArrayList<>(hashes));

        combined.put("executions", executions);

        combined.put("resultsUrls", resultsUrls);

        combined.put("testCases", totalTestCases);

        combined.put("passed", totalPassed);

        combined.put("failed", totalFailed);

        combined.put("errors", totalErrors);

        combined.put("multipleExecutions", executions.size() > 1);

        if (executions.size() == 1) {
            Map<String, Object> execution = executions.get(0);

            combined.putAll(execution);
            combined.put("executionCount", 1);
            combined.put("hashes", new ArrayList<>(hashes));
            combined.put("executions", executions);
            combined.put("resultsUrls", resultsUrls);
            combined.put("multipleExecutions", false);
        } else {
            combined.put("testSuiteName", "Multiple Endtest executions");

            combined.put("configuration", "Multiple configurations");

            combined.put("hash", String.join(",", hashes));

            combined.put("resultsUrl", "");
        }

        return combined;
    }

    private int readNumber(Map<String, Object> result, String key) {
        Object value = result.get(key);

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        return 0;
    }

    private Object readOptionalValue(JSONObject json, String key) {
        if (!json.containsKey(key) || json.get(key) == null) {
            return null;
        }

        Object value = json.get(key);

        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> list = new ArrayList<>();

            for (int index = 0; index < array.size(); index++) {
                list.add(convertJsonValue(array.get(index)));
            }

            return list;
        }

        if (value instanceof JSONObject) {
            return convertJsonObject((JSONObject) value);
        }

        return value;
    }

    private Object convertJsonValue(Object value) {
        if (value instanceof JSONObject) {
            return convertJsonObject((JSONObject) value);
        }

        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> list = new ArrayList<>();

            for (int index = 0; index < array.size(); index++) {
                list.add(convertJsonValue(array.get(index)));
            }

            return list;
        }

        return value;
    }

    private Map<String, Object> convertJsonObject(JSONObject object) {
        Map<String, Object> result = new LinkedHashMap<>();

        for (Object keyObject : object.keySet()) {
            String key = String.valueOf(keyObject);

            result.put(key, convertJsonValue(object.get(key)));
        }

        return result;
    }

    private URI buildAuthenticatedRequest(String apiRequest, String appId, String appCode) {
        URI uri = validateApiRequest(apiRequest);

        String effectiveAppId = resolveCredential(apiRequest, "appId", appId);

        String effectiveAppCode = resolveCredential(apiRequest, "appCode", appCode);

        validateCredentials(effectiveAppId, effectiveAppCode);

        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("The Endtest API request must not contain a URL fragment.");
        }

        List<String> missingParameters = new ArrayList<>();

        if (!hasQueryParameter(uri, "appId")) {
            missingParameters.add("appId=" + encode(effectiveAppId));
        }

        if (!hasQueryParameter(uri, "appCode")) {
            missingParameters.add("appCode=" + encode(effectiveAppCode));
        }

        if (missingParameters.isEmpty()) {
            return uri;
        }

        String request = uri.toString();
        String separator;

        if (uri.getRawQuery() == null) {
            separator = "?";
        } else if (request.endsWith("?") || request.endsWith("&")) {
            separator = "";
        } else {
            separator = "&";
        }

        return URI.create(request + separator + String.join("&", missingParameters));
    }

    private String sendGet(URI uri) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "*/*")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Endtest returned HTTP " + response.statusCode() + ".");
        }

        return response.body();
    }

    private URI validateApiRequest(String apiRequest) {
        if (apiRequest == null || apiRequest.trim().isEmpty()) {
            throw new IllegalArgumentException("The Endtest API request cannot be empty.");
        }

        URI uri;

        try {
            uri = URI.create(apiRequest.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("The Endtest API request is not a valid URL.", exception);
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("The Endtest API request must use HTTPS.");
        }

        if (!ENDTEST_API_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("The Endtest API request must use the app.endtest.io host.");
        }

        return uri;
    }

    private void validateCredentials(String appId, String appCode) {
        if (appId == null || appId.trim().isEmpty()) {
            throw new IllegalArgumentException("The Endtest App ID cannot be empty.");
        }

        if (appCode == null || appCode.trim().isEmpty()) {
            throw new IllegalArgumentException("The Endtest App Code cannot be empty.");
        }
    }

    private void validateHashes(List<String> hashes) {
        if (hashes == null || hashes.isEmpty()) {
            throw new IllegalArgumentException("At least one Endtest execution hash is required.");
        }

        for (String hash : hashes) {
            if (hash == null || !hash.matches("[A-Za-z0-9]+")) {
                throw new IllegalArgumentException("An invalid Endtest execution hash was provided.");
            }
        }
    }

    private void validateResultsFormat(String resultsFormat) {
        if (!"json".equals(resultsFormat) && !"json-light".equals(resultsFormat)) {
            throw new IllegalArgumentException("resultsFormat must be json or json-light.");
        }
    }

    private boolean hasQueryParameter(URI uri, String parameterName) {
        String query = uri.getRawQuery();

        if (query == null || query.isEmpty()) {
            return false;
        }

        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String name = decode(parts[0]);

            if (parameterName.equals(name)) {
                return true;
            }
        }

        return false;
    }

    private String cleanResponse(String responseBody) {
        if (responseBody == null) {
            return "";
        }

        String value = responseBody.trim();

        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }

        return value;
    }

    private boolean looksLikeHtml(String value) {
        String lowercase = value.toLowerCase();

        return lowercase.contains("<html") || lowercase.contains("<!doctype");
    }

    private String readString(JSONObject json, String key) {
        if (!json.containsKey(key) || json.get(key) == null) {
            return "";
        }

        return String.valueOf(json.get(key));
    }

    private int readInt(JSONObject json, String key) {
        String value = readString(json, key).trim();

        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return 0;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    public static class ResultResponse {

        private final boolean complete;
        private final String status;
        private final Map<String, Object> result;

        private ResultResponse(boolean complete, String status, Map<String, Object> result) {
            this.complete = complete;
            this.status = status;
            this.result = result;
        }

        public static ResultResponse pending(String status) {
            return new ResultResponse(false, status, null);
        }

        public static ResultResponse completed(Map<String, Object> result) {
            return new ResultResponse(true, "Completed", result);
        }

        public boolean isComplete() {
            return complete;
        }

        public String getStatus() {
            return status;
        }

        public Map<String, Object> getResult() {
            return result;
        }
    }
}
