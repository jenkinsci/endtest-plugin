package io.jenkins.plugins.endtest;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import hudson.Extension;
import hudson.Util;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Jenkins credential containing an Endtest App ID and App Code.
 */
public final class EndtestCredentials extends BaseStandardCredentials {

    private static final long serialVersionUID = 1L;

    private final String appId;
    private final Secret appCode;

    @DataBoundConstructor
    public EndtestCredentials(CredentialsScope scope, String id, String description, String appId, Secret appCode) {
        super(scope, id, description);
        this.appId = Util.fixNull(appId).trim();
        this.appCode = appCode == null ? Secret.fromString("") : appCode;
    }

    public String getAppId() {
        return appId;
    }

    public Secret getAppCode() {
        return appCode;
    }

    @Extension
    public static class DescriptorImpl extends BaseStandardCredentials.BaseStandardCredentialsDescriptor {

        @Override
        public String getDisplayName() {
            return "Endtest API credentials";
        }
    }
}
