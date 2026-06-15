package io.jenkins.plugins.endtest;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.AbortException;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Jenkins Pipeline step for running Endtest tests.
 */
public class EndtestRunStep extends Step {

    private final String credentialsId;
    private final String apiRequest;

    private int timeoutMinutes = 30;
    private int pollIntervalSeconds = 30;
    private boolean failBuild = true;
    private String resultsFormat = "json-light";

    @DataBoundConstructor
    public EndtestRunStep(String credentialsId, String apiRequest) {
        this.credentialsId = credentialsId;
        this.apiRequest = apiRequest;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getApiRequest() {
        return apiRequest;
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    @DataBoundSetter
    public void setTimeoutMinutes(int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }

    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    @DataBoundSetter
    public void setPollIntervalSeconds(int pollIntervalSeconds) {
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public boolean isFailBuild() {
        return failBuild;
    }

    @DataBoundSetter
    public void setFailBuild(boolean failBuild) {
        this.failBuild = failBuild;
    }

    public String getResultsFormat() {
        return resultsFormat;
    }

    @DataBoundSetter
    public void setResultsFormat(String resultsFormat) {
        this.resultsFormat = resultsFormat;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(this, context);
    }

    private static class Execution extends SynchronousNonBlockingStepExecution<Map<String, Object>> {

        private static final long serialVersionUID = 1L;

        private final String credentialsId;
        private final String apiRequest;
        private final int timeoutMinutes;
        private final int pollIntervalSeconds;
        private final boolean failBuild;
        private final String resultsFormat;

        Execution(EndtestRunStep step, StepContext context) {
            super(context);

            this.credentialsId = step.credentialsId;

            this.apiRequest = step.apiRequest;

            this.timeoutMinutes = step.timeoutMinutes;

            this.pollIntervalSeconds = step.pollIntervalSeconds;

            this.failBuild = step.failBuild;

            this.resultsFormat = step.resultsFormat;
        }

        @Override
        protected Map<String, Object> run() throws Exception {
            validateOptions();

            TaskListener listener = getContext().get(TaskListener.class);

            Run<?, ?> run = getContext().get(Run.class);

            if (listener == null) {
                throw new IllegalStateException("Jenkins TaskListener is unavailable.");
            }

            if (run == null) {
                throw new IllegalStateException("The current Jenkins build is unavailable.");
            }

            EndtestCredentials credentials = CredentialsProvider.findCredentialById(
                    credentialsId, EndtestCredentials.class, run, Collections.emptyList());

            if (credentials == null) {
                throw new AbortException("Could not find Endtest credentials with ID: " + credentialsId);
            }

            String appId = credentials.getAppId();

            String appCode = credentials.getAppCode().getPlainText();

            EndtestClient client = new EndtestClient();

            String effectiveAppId = client.resolveAppId(apiRequest, appId);

            String effectiveAppCode = client.resolveAppCode(apiRequest, appCode);

            listener.getLogger().println("[Endtest] Starting Endtest execution...");

            List<String> hashes = client.startExecutions(apiRequest, effectiveAppId, effectiveAppCode);

            listener.getLogger()
                    .println("[Endtest] Started " + hashes.size() + " execution" + (hashes.size() == 1 ? "." : "s."));

            for (int index = 0; index < hashes.size(); index++) {
                String hash = hashes.get(index);

                listener.getLogger()
                        .println("[Endtest] Execution " + (index + 1) + "/" + hashes.size() + " hash: " + hash);

                listener.getLogger().println("[Endtest] Results: " + "https://app.endtest.io/results?hash=" + hash);
            }

            long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(timeoutMinutes);

            String lastStatus = "";

            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(TimeUnit.SECONDS.toMillis(pollIntervalSeconds));

                EndtestClient.ResultResponse response =
                        client.getResults(effectiveAppId, effectiveAppCode, hashes, resultsFormat);

                if (response.isComplete()) {
                    Map<String, Object> result = response.getResult();

                    printResults(listener, result);

                    applyBuildResult(result);

                    return result;
                }

                if (!response.getStatus().equals(lastStatus)) {
                    listener.getLogger().println("[Endtest] " + response.getStatus());

                    lastStatus = response.getStatus();
                }
            }

            throw new AbortException("Timed out waiting for Endtest after " + timeoutMinutes + " minutes.");
        }

        private void validateOptions() {
            if (credentialsId == null || credentialsId.trim().isEmpty()) {
                throw new IllegalArgumentException("credentialsId cannot be empty.");
            }

            if (apiRequest == null || apiRequest.trim().isEmpty()) {
                throw new IllegalArgumentException("apiRequest cannot be empty.");
            }

            if (timeoutMinutes < 1 || timeoutMinutes > 1440) {
                throw new IllegalArgumentException("timeoutMinutes must be between 1 and 1440.");
            }

            if (pollIntervalSeconds < 5 || pollIntervalSeconds > 300) {
                throw new IllegalArgumentException("pollIntervalSeconds must be between 5 and 300.");
            }

            if (!"json".equals(resultsFormat) && !"json-light".equals(resultsFormat)) {
                throw new IllegalArgumentException("resultsFormat must be json or json-light.");
            }
        }

        @SuppressWarnings("unchecked")
        private void printResults(TaskListener listener, Map<String, Object> result) {
            int executionCount = ((Number) result.get("executionCount")).intValue();

            List<Map<String, Object>> executions = (List<Map<String, Object>>) result.get("executions");

            listener.getLogger()
                    .println("[Endtest] "
                            + executionCount
                            + " execution"
                            + (executionCount == 1 ? " completed." : "s completed."));

            for (int index = 0; index < executions.size(); index++) {
                Map<String, Object> execution = executions.get(index);

                listener.getLogger().println("[Endtest] Execution " + (index + 1) + "/" + executions.size());

                listener.getLogger().println("[Endtest] Test Suite: " + execution.get("testSuiteName"));

                listener.getLogger().println("[Endtest] Configuration: " + execution.get("configuration"));

                listener.getLogger().println("[Endtest] Test Cases: " + execution.get("testCases"));

                listener.getLogger().println("[Endtest] Passed: " + execution.get("passed"));

                listener.getLogger().println("[Endtest] Failed: " + execution.get("failed"));

                listener.getLogger().println("[Endtest] Errors: " + execution.get("errors"));

                listener.getLogger().println("[Endtest] Start Time: " + execution.get("startTime"));

                listener.getLogger().println("[Endtest] End Time: " + execution.get("endTime"));

                listener.getLogger().println("[Endtest] Results: " + execution.get("resultsUrl"));
            }

            if (executionCount > 1) {
                listener.getLogger().println("[Endtest] Aggregate results");

                listener.getLogger().println("[Endtest] Test Cases: " + result.get("testCases"));

                listener.getLogger().println("[Endtest] Passed: " + result.get("passed"));

                listener.getLogger().println("[Endtest] Failed: " + result.get("failed"));

                listener.getLogger().println("[Endtest] Errors: " + result.get("errors"));
            }
        }

        private void applyBuildResult(Map<String, Object> result) throws AbortException {
            int failed = ((Number) result.get("failed")).intValue();

            int errors = ((Number) result.get("errors")).intValue();

            if (failBuild && (failed > 0 || errors > 0)) {
                throw new AbortException("Endtest reported "
                        + failed
                        + " failed assertions and "
                        + errors
                        + " errors across "
                        + result.get("executionCount")
                        + " execution(s).");
            }
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "endtestRun";
        }

        @Override
        public String getDisplayName() {
            return "Run Endtest tests";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(TaskListener.class, Run.class);
        }
    }
}
