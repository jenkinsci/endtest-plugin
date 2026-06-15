package io.jenkins.plugins.endtest;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Jenkins Pipeline step for running one or more Endtest executions.
 */
public final class EndtestRunStep extends Step {

    private final String apiRequest;

    private String credentialsId;
    private int timeoutMinutes = 30;
    private int pollIntervalSeconds = 30;
    private boolean failBuild = true;
    private String resultsFormat = "json-light";

    @DataBoundConstructor
    public EndtestRunStep(String apiRequest) {
        this.apiRequest = apiRequest;
    }

    public String getApiRequest() {
        return apiRequest;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
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
        return new EndtestRunExecution(this, context);
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

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
