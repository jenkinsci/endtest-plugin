package io.jenkins.plugins.endtest;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

/**
 * Restart-safe asynchronous execution for the Endtest Pipeline step.
 */
public final class EndtestRunExecution extends StepExecution {

    private static final long serialVersionUID = 1L;

    private final String credentialsId;
    private final String apiRequest;
    private final int timeoutMinutes;
    private final int pollIntervalSeconds;
    private final boolean failBuild;
    private final String resultsFormat;

    private List<String> hashes = new ArrayList<>();
    private long deadlineMillis;
    private String lastStatus = "";
    private boolean startRequestInProgress;
    private boolean finished;

    private transient ScheduledFuture<?> task;

    EndtestRunExecution(EndtestRunStep step, StepContext context) {
        super(context);

        credentialsId = step.getCredentialsId();
        apiRequest = step.getApiRequest();
        timeoutMinutes = step.getTimeoutMinutes();
        pollIntervalSeconds = step.getPollIntervalSeconds();
        failBuild = step.isFailBuild();
        resultsFormat = step.getResultsFormat();
    }

    @Override
    public boolean start() {
        validateOptions();

        if (!schedule(0)) {
            throw new IllegalStateException("Jenkins could not schedule the Endtest execution.");
        }

        return false;
    }

    @Override
    public void onResume() {
        boolean interruptedDuringStart;

        synchronized (this) {
            if (finished) {
                return;
            }

            task = null;

            interruptedDuringStart = startRequestInProgress && hashes.isEmpty();
        }

        /*
         * Repeating the start request might create duplicate Endtest runs.
         * Fail safely if Jenkins stopped while the start request was active.
         */
        if (interruptedDuringStart) {
            finishFailure(new AbortException("Jenkins restarted while the Endtest start request "
                    + "was in progress. The execution might have "
                    + "started, but its hash was not available."));
            return;
        }

        if (!schedule(0)) {
            finishFailure(new IllegalStateException("Jenkins could not restore Endtest polling."));
        }
    }

    @Override
    public void stop(@NonNull Throwable cause) throws Exception {
        ScheduledFuture<?> currentTask;

        synchronized (this) {
            if (finished) {
                return;
            }

            finished = true;
            currentTask = task;
            task = null;
        }

        if (currentTask != null) {
            currentTask.cancel(true);
        }

        super.stop(cause);
    }

    @Override
    public synchronized String getStatus() {
        if (finished) {
            return "finished";
        }

        if (hashes.isEmpty()) {
            return "starting Endtest execution";
        }

        long remainingMillis = Math.max(0, deadlineMillis - System.currentTimeMillis());

        return "waiting for "
                + hashes.size()
                + " Endtest execution(s), "
                + TimeUnit.MILLISECONDS.toSeconds(remainingMillis)
                + " seconds remaining";
    }

    private void runCycle() {
        synchronized (this) {
            if (finished) {
                return;
            }

            task = null;
        }

        try {
            if (hashes.isEmpty()) {
                startExecutions();
            } else {
                pollExecutions();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            /*
             * During Jenkins shutdown, onResume() restores the polling task.
             * User cancellation is handled by stop().
             */
        } catch (Throwable exception) {
            finishFailure(exception);
        }
    }

    private void startExecutions() throws Exception {
        TaskListener listener = getListener();
        EndtestClient client = new EndtestClient();
        CredentialValues credentials = resolveCredentialValues(client);

        synchronized (this) {
            if (finished) {
                return;
            }

            startRequestInProgress = true;
        }

        /*
         * Save the in-progress state before sending the API request. This
         * prevents a restart from silently starting the executions twice.
         */
        persistState();

        listener.getLogger().println("[Endtest] Starting Endtest execution...");

        List<String> startedHashes = client.startExecutions(apiRequest, credentials.appId, credentials.appCode);

        synchronized (this) {
            if (finished) {
                return;
            }

            hashes = new ArrayList<>(startedHashes);

            deadlineMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(timeoutMinutes);

            startRequestInProgress = false;
        }

        listener.getLogger()
                .println("[Endtest] Started " + hashes.size() + " execution" + (hashes.size() == 1 ? "." : "s."));

        for (int index = 0; index < hashes.size(); index++) {
            String hash = hashes.get(index);

            listener.getLogger().println("[Endtest] Execution " + (index + 1) + "/" + hashes.size() + " hash: " + hash);

            listener.getLogger().println("[Endtest] Results: " + "https://app.endtest.io/results?hash=" + hash);
        }

        /*
         * Save the hashes and deadline before entering the waiting period.
         */
        persistState();
        scheduleNextPoll();
    }

    private void pollExecutions() throws Exception {
        long currentDeadlineMillis = getDeadlineMillis();

        if (System.currentTimeMillis() >= currentDeadlineMillis) {
            finishFailure(new AbortException("Timed out waiting for Endtest after " + timeoutMinutes + " minutes."));
            return;
        }

        EndtestClient client = new EndtestClient();

        CredentialValues credentials = resolveCredentialValues(client);

        EndtestClient.ResultResponse response =
                client.getResults(credentials.appId, credentials.appCode, hashes, resultsFormat);

        synchronized (this) {
            if (finished) {
                return;
            }
        }

        if (response.isComplete()) {
            Map<String, Object> result = response.getResult();

            printResults(getListener(), result);

            int failed = ((Number) result.get("failed")).intValue();

            int errors = ((Number) result.get("errors")).intValue();

            if (failBuild && (failed > 0 || errors > 0)) {
                finishFailure(new AbortException("Endtest reported "
                        + failed
                        + " failed assertions and "
                        + errors
                        + " errors across "
                        + result.get("executionCount")
                        + " execution(s)."));
                return;
            }

            finishSuccess(result);
            return;
        }

        String status = response.getStatus();

        if (!status.equals(lastStatus)) {
            getListener().getLogger().println("[Endtest] " + status);

            lastStatus = status;
            persistState();
        }

        scheduleNextPoll();
    }

    private CredentialValues resolveCredentialValues(EndtestClient client) throws Exception {
        /*
         * Credentials inside apiRequest take precedence.
         */
        String embeddedAppId = client.resolveAppId(apiRequest, "");

        String embeddedAppCode = client.resolveAppCode(apiRequest, "");

        if (!embeddedAppId.isEmpty() && !embeddedAppCode.isEmpty()) {
            return new CredentialValues(embeddedAppId, embeddedAppCode);
        }

        if (credentialsId == null || credentialsId.trim().isEmpty()) {
            throw new AbortException(
                    "credentialsId is required when appId or appCode " + "is missing from apiRequest.");
        }

        Run<?, ?> run = getContext().get(Run.class);

        if (run == null) {
            throw new IllegalStateException("The current Jenkins build is unavailable.");
        }

        EndtestCredentials storedCredentials = CredentialsProvider.findCredentialById(
                credentialsId, EndtestCredentials.class, run, Collections.emptyList());

        if (storedCredentials == null) {
            throw new AbortException("Could not find Endtest credentials with ID: " + credentialsId);
        }

        String effectiveAppId = client.resolveAppId(apiRequest, storedCredentials.getAppId());

        String effectiveAppCode =
                client.resolveAppCode(apiRequest, storedCredentials.getAppCode().getPlainText());

        return new CredentialValues(effectiveAppId, effectiveAppCode);
    }

    private TaskListener getListener() throws Exception {
        TaskListener listener = getContext().get(TaskListener.class);

        if (listener == null) {
            throw new IllegalStateException("Jenkins TaskListener is unavailable.");
        }

        return listener;
    }

    private synchronized long getDeadlineMillis() {
        return deadlineMillis;
    }

    private void scheduleNextPoll() {
        long remainingMillis = getDeadlineMillis() - System.currentTimeMillis();

        if (remainingMillis <= 0) {
            schedule(0);
            return;
        }

        long normalDelay = TimeUnit.SECONDS.toMillis(pollIntervalSeconds);

        schedule(Math.min(normalDelay, remainingMillis));
    }

    private boolean schedule(long delayMillis) {
        synchronized (this) {
            if (finished) {
                return false;
            }

            try {
                task = Timer.get().schedule(this::runCycle, delayMillis, TimeUnit.MILLISECONDS);

                return true;
            } catch (RejectedExecutionException exception) {
                task = null;
                return false;
            }
        }
    }

    private void persistState() throws Exception {
        getContext().saveState().get(30, TimeUnit.SECONDS);
    }

    private void finishSuccess(Map<String, Object> result) {
        synchronized (this) {
            if (finished) {
                return;
            }

            finished = true;
            task = null;
        }

        getContext().onSuccess(result);
    }

    private void finishFailure(Throwable exception) {
        synchronized (this) {
            if (finished) {
                return;
            }

            finished = true;
            task = null;
        }

        getContext().onFailure(exception);
    }

    private void validateOptions() {
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

    private static final class CredentialValues {

        private final String appId;
        private final String appCode;

        private CredentialValues(String appId, String appCode) {
            this.appId = appId;
            this.appCode = appCode;
        }
    }
}
