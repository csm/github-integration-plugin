package com.github.kostyasha.github.integration.branch;

import antlr.ANTLRException;
import com.cloudbees.jenkins.GitHubWebHook;
import com.github.kostyasha.github.integration.branch.events.GitHubBranchEvent;
import com.github.kostyasha.github.integration.branch.events.GitHubBranchEventDescriptor;
import com.github.kostyasha.github.integration.branch.trigger.JobRunnerForBranchCause;
import com.github.kostyasha.github.integration.generic.GitHubTrigger;
import com.github.kostyasha.github.integration.generic.GitHubTriggerDescriptor;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.triggers.Trigger;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.triggers.SCMTriggerItem;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.github.GitHubPlugin;
import org.jenkinsci.plugins.github.pullrequest.GitHubPRTriggerMode;
import org.jenkinsci.plugins.github.pullrequest.restrictions.GitHubPRBranchRestriction;
import org.jenkinsci.plugins.github.pullrequest.restrictions.GitHubPRUserRestriction;
import org.jenkinsci.plugins.github.pullrequest.utils.LoggingTaskListenerWrapper;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.github.GHBranch;
import org.kohsuke.github.GHRateLimit;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.github.kostyasha.github.integration.branch.trigger.check.BranchToCauseConverter.toGitHubBranchCause;
import static com.github.kostyasha.github.integration.branch.trigger.check.LocalRepoUpdater.updateLocalRepo;
import static com.github.kostyasha.github.integration.branch.trigger.check.SkipFirstRunForBranchFilter.ifSkippedFirstRun;
import static com.github.kostyasha.github.integration.branch.webhook.WebhookInfoBranchPredicates.withHookTriggerMode;
import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Predicates.and;
import static com.google.common.base.Predicates.not;
import static com.google.common.base.Predicates.notNull;
import static java.text.DateFormat.getDateTimeInstance;
import static org.jenkinsci.plugins.github.pullrequest.GitHubPRTrigger.DescriptorImpl.githubFor;
import static org.jenkinsci.plugins.github.pullrequest.GitHubPRTriggerMode.LIGHT_HOOKS;
import static org.jenkinsci.plugins.github.pullrequest.utils.ObjectsUtil.isNull;
import static org.jenkinsci.plugins.github.pullrequest.utils.ObjectsUtil.nonNull;
import static org.jenkinsci.plugins.github.util.FluentIterableWrapper.from;
import static org.jenkinsci.plugins.github.util.JobInfoHelpers.isBuildable;

/**
 * @author Kanstantsin Shautsou
 */
public class GitHubBranchTrigger extends GitHubTrigger<GitHubBranchTrigger> {
    private static final Logger LOG = LoggerFactory.getLogger(GitHubBranchTrigger.class);
    public static final String FINISH_MSG = "Finished GitHub Branch trigger check";

    private List<GitHubBranchEvent> events = new ArrayList<>();

    private boolean preStatus = false;

    @CheckForNull
    private GitHubPRUserRestriction userRestriction;
    @CheckForNull
    private GitHubPRBranchRestriction branchRestriction;

    @CheckForNull
    private transient GitHubBranchPollingLogAction pollingLogAction;

    @Override
    public String getFinishMsg() {
        return FINISH_MSG;
    }

    /**
     * For groovy UI
     */
    @Restricted(value = NoExternalUse.class)
    public GitHubBranchTrigger() throws ANTLRException {
        super("");
    }

    @DataBoundConstructor
    public GitHubBranchTrigger(String spec, GitHubPRTriggerMode triggerMode, List<GitHubBranchEvent> events)
            throws ANTLRException {
        super(spec, triggerMode);
        this.events = events;
    }

    @DataBoundSetter
    public void setPreStatus(boolean preStatus) {
        this.preStatus = preStatus;
    }

    @DataBoundSetter
    public void setUserRestriction(GitHubPRUserRestriction userRestriction) {
        this.userRestriction = userRestriction;
    }

    @DataBoundSetter
    public void setBranchRestriction(GitHubPRBranchRestriction branchRestriction) {
        this.branchRestriction = branchRestriction;
    }

    public boolean isPreStatus() {
        return preStatus;
    }

    public GitHubPRUserRestriction getUserRestriction() {
        return userRestriction;
    }

    public GitHubPRBranchRestriction getBranchRestriction() {
        return branchRestriction;
    }

    @CheckForNull
    public List<GitHubBranchEvent> getEvents() {
        return events;
    }

    public void setEvents(List<GitHubBranchEvent> events) {
        this.events = events;
    }

    @Override
    public void start(Job<?, ?> project, boolean newInstance) {
        LOG.info("Starting GitHub Branch trigger for project {}", project.getName());
        super.start(project, newInstance);

        if (newInstance && GitHubPlugin.configuration().isManageHooks() && withHookTriggerMode().apply(project)) {
            GitHubWebHook.get().registerHookFor(project);
        }
    }

    public void run() {
        if (getTriggerMode() != LIGHT_HOOKS) {
            doRun(null);
        }
    }

    @CheckForNull
    public GitHubBranchPollingLogAction getPollingLogAction() {
        if (isNull(pollingLogAction) && nonNull(job)) {
            pollingLogAction = new GitHubBranchPollingLogAction(job);
        }

        return pollingLogAction;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * For running from external places. Goes to queue.
     */
    public void queueRun(Job<?, ?> job, final String branch) {
        this.job = job;
        getDescriptor().getQueue().execute(new Runnable() {
            @Override
            public void run() {
                doRun(branch);
            }
        });
    }

    /**
     * Runs check
     *
     * @param branch - branch for check, if null - then all PRs
     */
    public void doRun(String branch) {
        if (not(isBuildable()).apply(job)) {
            LOG.debug("Job {} is disabled, but trigger run!", isNull(job) ? "<no job>" : job.getFullName());
            return;
        }

        if (!isSupportedTriggerMode(getTriggerMode())) {
            LOG.warn("Trigger mode {} is not supported yet ({})", getTriggerMode(), job.getFullName());
            return;
        }

        GitHubBranchRepository localRepository = job.getAction(GitHubBranchRepository.class);
        if (isNull(localRepository)) {
            LOG.warn("Can't get repository info, maybe project {} misconfigured?", job.getFullName());
            return;
        }

        List<GitHubBranchCause> causes;

        try (LoggingTaskListenerWrapper listener =
                     new LoggingTaskListenerWrapper(getPollingLogAction().getPollingLogFile(), UTF_8)) {
            long startTime = System.currentTimeMillis();
            listener.debug("Running GitHub Branch trigger check for {} on {}",
                    getDateTimeInstance().format(new Date(startTime)), localRepository.getFullName());

            causes = readyToBuildCauses(localRepository, listener, branch);

            localRepository.saveQuietly();

            long duration = System.currentTimeMillis() - startTime;
            listener.info(FINISH_MSG + " for {} at {}. Duration: {}ms",
                    localRepository.getFullName(), getDateTimeInstance().format(new Date()), duration);
        } catch (Exception e) {
            LOG.error("Can't process check ({})", e.getMessage(), e);
            return;
        }

        from(causes).filter(new JobRunnerForBranchCause(job, this)).toSet();
    }

    /**
     * @return list of causes for scheduling branch builds.
     */
    private List<GitHubBranchCause> readyToBuildCauses(GitHubBranchRepository localRepository,
                                                       LoggingTaskListenerWrapper listener,
                                                       @Nullable String branch) {
        try {
            GitHub github = githubFor(localRepository.getGithubUrl().toURI());
            GHRateLimit rateLimitBefore = github.getRateLimit();
            listener.debug("GitHub rate limit before check: {}", rateLimitBefore);

            // get local and remote list of branches
            GHRepository remoteRepo = getRemoteRepository();
            Set<GHBranch> remoteBranches = branchesToCheck(branch, remoteRepo, localRepository);

            Set<GHBranch> prepared = from(remoteBranches)
//                    .transform(prepareUserRestrictionFilter(localRepository, this))
                    .toSet();

            List<GitHubBranchCause> causes = from(prepared)
                    .filter(and(
                            ifSkippedFirstRun(listener, skipFirstRun)//,
//                            withBranchRestriction(listener, branchRestriction),
//                            withUserRestriction(listener, userRestriction)
                    ))
                    .transform(toGitHubBranchCause(localRepository, listener, this))
                    .filter(notNull())
                    .toList();

            LOG.trace("Causes count for {}: {}", localRepository.getFullName(), causes.size());
            from(prepared).transform(updateLocalRepo(localRepository)).toSet();

            saveIfSkipFirstRun();

            GHRateLimit rateLimitAfter = github.getRateLimit();
            int consumed = rateLimitBefore.remaining - rateLimitAfter.remaining;
            LOG.info("GitHub rate limit after check {}: {}, consumed: {}, checked branches: {}",
                    localRepository.getFullName(), rateLimitAfter, consumed, remoteBranches.size());

            return causes;
        } catch (IOException | URISyntaxException e) {
            listener.error("Can't get build causes, because: '{}'", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Remote branch for future analysing. null - all remote branches.
     */
    private Set<GHBranch> branchesToCheck(String branch, GHRepository remoteRepo, GitHubBranchRepository localRepository)
            throws IOException {
        final LinkedHashSet<GHBranch> ghBranches = new LinkedHashSet<>();

        if (branch != null) {
            final GHBranch ghBranch = remoteRepo.getBranches().get(branch);
            if (ghBranch != null) {
                ghBranches.add(ghBranch);
            }
        } else {
            ghBranches.addAll(remoteRepo.getBranches().values());
        }

        return ghBranches;
    }

    private static boolean isSupportedTriggerMode(GitHubPRTriggerMode mode) {
        return mode != LIGHT_HOOKS;
    }

    @Extension
    public static class DescriptorImpl extends GitHubTriggerDescriptor {

        public DescriptorImpl() {
            load();
        }

        @Override
        public boolean isApplicable(Item item) {
            return item instanceof Job && nonNull(SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(item))
                    && item instanceof ParameterizedJobMixIn.ParameterizedJob;
        }

        @Override
        public String getDisplayName() {
            return "Experimental: GitHub Branches";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            req.bindJSON(this, formData);

            save();
            return super.configure(req, formData);
        }

        // list all available descriptors for choosing in job configuration
        public List<GitHubBranchEventDescriptor> getEventDescriptors() {
            return GitHubBranchEventDescriptor.all();
        }

        public static DescriptorImpl get() {
            return Trigger.all().get(DescriptorImpl.class);
        }
    }
}
