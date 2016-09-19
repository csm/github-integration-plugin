package com.github.kostyasha.github.integration.branch.events.impl;

import com.github.kostyasha.github.integration.branch.GitHubBranch;
import com.github.kostyasha.github.integration.branch.GitHubBranchCause;
import com.github.kostyasha.github.integration.branch.GitHubBranchRepository;
import com.github.kostyasha.github.integration.branch.GitHubBranchTrigger;
import com.github.kostyasha.github.integration.branch.events.GitHubBranchEvent;
import com.github.kostyasha.github.integration.branch.events.GitHubBranchEventDescriptor;
import com.github.kostyasha.github.integration.generic.GitHubRepository;
import hudson.Extension;
import hudson.model.TaskListener;
import org.kohsuke.github.GHBranch;
import org.kohsuke.stapler.DataBoundConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import java.io.IOException;
import java.io.PrintStream;

import static org.jenkinsci.plugins.github.pullrequest.utils.ObjectsUtil.nonNull;

/**
 * When SHA1 changed between local and remote state.
 *
 * @author Kanstantsin Shautsou
 */
public class GitHubBranchHashChangedEvent extends GitHubBranchEvent {
    private static final String DISPLAY_NAME = "Hash Changed";
    private static final Logger LOG = LoggerFactory.getLogger(GitHubBranchHashChangedEvent.class);

    @DataBoundConstructor
    public GitHubBranchHashChangedEvent() {
    }

    @Override
    public GitHubBranchCause check(GitHubBranchTrigger trigger,
                                   GHBranch remoteBranch,
                                   @CheckForNull GitHubBranch localBranch,
                                   GitHubBranchRepository localRepo,
                                   TaskListener listener) throws IOException {
        GitHubBranchCause cause = null;
        if (nonNull(localBranch) && nonNull(remoteBranch)) { // didn't exist before
            final String localBranchSHA1 = localBranch.getSHA1();
            final String remoteBranchSHA1 = remoteBranch.getSHA1();

            if (!localBranchSHA1.equals(remoteBranchSHA1)) {
                final PrintStream logger = listener.getLogger();
                logger.printf("%s: hash has changed '%s' -> '%s'%n", DISPLAY_NAME, localBranchSHA1, remoteBranchSHA1);
                LOG.debug("{}: hash has changed '{}' -> '{}'", DISPLAY_NAME, localBranchSHA1, remoteBranchSHA1);
                cause = new GitHubBranchCause(remoteBranch, localRepo, DISPLAY_NAME, false);
            }
        }

        return cause;
    }

    @Extension
    public static class DescriptorImpl extends GitHubBranchEventDescriptor {
        @Override
        public final String getDisplayName() {
            return DISPLAY_NAME;
        }
    }
}