package dev.lsegal.jenkins.codebuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.SdkClientException;
import com.amazonaws.regions.DefaultAwsRegionProviderChain;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.codebuild.AWSCodeBuild;
import com.amazonaws.services.codebuild.AWSCodeBuildClientBuilder;
import com.amazonaws.services.codebuild.model.ListProjectsRequest;
import com.amazonaws.services.codebuild.model.ListProjectsResult;
import com.cloudbees.jenkins.plugins.awscredentials.AWSCredentialsHelper;
import com.cloudbees.jenkins.plugins.awscredentials.AmazonWebServicesCredentials;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import hudson.Extension;
import hudson.ProxyConfiguration;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;

/**
 * This is the root class that contains all configuration state about the
 * CodeBuilder cloud agents. Jenkins calls {@link CodeBuilderCloud.provision} on
 * this class to create new nodes.
 */
public class CodeBuilderCloud extends Cloud {
  private static final Logger LOGGER = LoggerFactory.getLogger(CodeBuilderCloud.class);
  private static final String DEFAULT_JNLP_IMAGE = "lsegal/jnlp-docker-agent:alpine";
  private static final String DEFAULT_JNLP_COMMAND = "jenkins-agent";
  private static final int DEFAULT_AGENT_TIMEOUT = 120;
  private static final String DEFAULT_COMPUTE_TYPE = "BUILD_GENERAL1_SMALL";

  static {
    clearAllNodes();
  }

  @Nonnull
  private final String projectName;

  @Nonnull
  private final String credentialsId;

  @Nonnull
  private final String region;

  private String label;
  private String computeType;
  private String jenkinsUrl;
  private String jnlpImage;
  private String jnlpCommand;
  private int agentTimeout;

  private transient AWSCodeBuild client;

  @DataBoundConstructor
  public CodeBuilderCloud(String name, @Nonnull String projectName, @Nullable String credentialsId,
      @Nonnull String region) throws InterruptedException {
    super(StringUtils.isNotBlank(name) ? name : "codebuilder_" + Jenkins.getInstance().clouds.size());

    this.projectName = projectName;
    this.credentialsId = credentialsId;
    if (StringUtils.isBlank(region)) {
      this.region = getDefaultRegion();
    } else {
      this.region = region;
    }

    LOGGER.info("[CodeBuilder]: Initializing Cloud: {}", this);
  }

  /**
   * Clear all CodeBuilder nodes on boot-up because these cannot be permanent.
   */
  private static void clearAllNodes() {
    List<Node> nodes = Jenkins.getInstance().getNodes();
    if (nodes.size() == 0) {
      return;
    }

    LOGGER.info("[CodeBuilder]: Clearing all previous CodeBuilder nodes...");
    for (final Node n : nodes) {
      if (n instanceof CodeBuilderAgent) {
        try {
          ((CodeBuilderAgent) n).terminate();
        } catch (InterruptedException | IOException e) {
          LOGGER.error("[CodeBuilder]: Failed to terminate agent '{}'", n.getDisplayName(), e);
        }
      }
    }
  }

  @Override
  public String toString() {
    return String.format("%s<%s>", name, projectName);
  }

  @Nonnull
  public String getProjectName() {
    return projectName;
  }

  @Nonnull
  public String getRegion() {
    return region;
  }

  @Nonnull
  public String getLabel() {
    return StringUtils.isBlank(label) ? "" : label;
  }

  @DataBoundSetter
  public void setLabel(String label) {
    this.label = label;
  }

  @Nonnull
  public String getJenkinsUrl() {
    if (StringUtils.isNotBlank(jenkinsUrl)) {
      return jenkinsUrl;
    } else {
      JenkinsLocationConfiguration config = JenkinsLocationConfiguration.get();
      if (config != null) {
        return config.getUrl();
      }
    }
    return "unknown";
  }

  @DataBoundSetter
  public void setJenkinsUrl(String jenkinsUrl) {
    JenkinsLocationConfiguration config = JenkinsLocationConfiguration.get();
    if (config != null && config.getUrl() == jenkinsUrl) {
      return;
    }
    this.jenkinsUrl = jenkinsUrl;
  }

  @Nonnull
  public String getJnlpImage() {
    return StringUtils.isBlank(jnlpImage) ? DEFAULT_JNLP_IMAGE : jnlpImage;
  }

  @Nonnull
  public String getJnlpCommand() {
    return StringUtils.isBlank(jnlpCommand) ? DEFAULT_JNLP_COMMAND : jnlpCommand;
  }

  @DataBoundSetter
  public void setJnlpImage(String jnlpImage) {
    this.jnlpImage = jnlpImage;
  }

  @Nonnull
  public int getAgentTimeout() {
    return agentTimeout == 0 ? DEFAULT_AGENT_TIMEOUT : agentTimeout;
  }

  @DataBoundSetter
  public void setAgentTimeout(int agentTimeout) {
    this.agentTimeout = agentTimeout;
  }

  @Nonnull
  public String getComputeType() {
    return StringUtils.isBlank(computeType) ? DEFAULT_COMPUTE_TYPE : computeType;
  }

  @DataBoundSetter
  public void setComputeType(String computeType) {
    this.computeType = computeType;
  }

  @CheckForNull
  private static AmazonWebServicesCredentials getCredentials(@Nullable String credentialsId) {
    return AWSCredentialsHelper.getCredentials(credentialsId, Jenkins.getInstance());
  }

  private static AWSCodeBuild buildClient(String credentialsId, String region) {
    ProxyConfiguration proxy = Jenkins.getInstance().proxy;
    ClientConfiguration clientConfiguration = new ClientConfiguration();

    if (proxy != null) {
      clientConfiguration.setProxyHost(proxy.name);
      clientConfiguration.setProxyPort(proxy.port);
      clientConfiguration.setProxyUsername(proxy.getUserName());
      clientConfiguration.setProxyPassword(proxy.getPassword());
    }

    AWSCodeBuildClientBuilder builder = AWSCodeBuildClientBuilder.standard()
        .withClientConfiguration(clientConfiguration).withRegion(region);

    AmazonWebServicesCredentials credentials = getCredentials(credentialsId);
    if (credentials != null) {
      String awsAccessKeyId = credentials.getCredentials().getAWSAccessKeyId();
      String obfuscatedAccessKeyId = StringUtils.left(awsAccessKeyId, 4)
          + StringUtils.repeat("*", awsAccessKeyId.length() - 8) + StringUtils.right(awsAccessKeyId, 4);
      LOGGER.debug("[CodeBuilder]: Using credentials: {}", obfuscatedAccessKeyId);
      builder.withCredentials(credentials);
    }
    LOGGER.debug("[CodeBuilder]: Selected Region: {}", region);

    return builder.build();
  }

  public synchronized AWSCodeBuild getClient() {
    if (this.client == null) {
      this.client = CodeBuilderCloud.buildClient(credentialsId, region);
    }
    return this.client;
  }

  private transient long lastProvisionTime = 0;

  @Override
  public synchronized Collection<PlannedNode> provision(Label label, int excessWorkload) {
    List<NodeProvisioner.PlannedNode> list = new ArrayList<NodeProvisioner.PlannedNode>();

    // guard against non-matching labels
    if (label != null && !label.matches(Arrays.asList(new LabelAtom(getLabel())))) {
      return list;
    }

    // guard against double-provisioning with a 500ms cooldown clock
    long timeDiff = System.currentTimeMillis() - lastProvisionTime;
    if (timeDiff < 500) {
      LOGGER.info("[CodeBuilder]: Provision of {} skipped, still on cooldown ({}ms of 500ms)", excessWorkload,
          timeDiff);
      return list;
    }

    String labelName = label == null ? getLabel() : label.getDisplayName();
    long stillProvisioning = numStillProvisioning();
    long numToLaunch = Math.max(excessWorkload - stillProvisioning, 0);
    LOGGER.info("[CodeBuilder]: Provisioning {} nodes for label '{}' ({} already provisioning)", numToLaunch, labelName,
        stillProvisioning);

    for (int i = 0; i < numToLaunch; i++) {
      final String suffix = RandomStringUtils.randomAlphabetic(4);
      final String displayName = String.format("%s.cb-%s", projectName, suffix);
      final CodeBuilderCloud cloud = this;
      final Future<Node> nodeResolver = Computer.threadPoolForRemoting.submit(() -> {
        CodeBuilderLauncher launcher = new CodeBuilderLauncher(cloud);
        CodeBuilderAgent agent = new CodeBuilderAgent(cloud, displayName, launcher);
        Jenkins.getInstance().addNode(agent);
        return agent;
      });
      list.add(new NodeProvisioner.PlannedNode(displayName, nodeResolver, 1));
    }

    lastProvisionTime = System.currentTimeMillis();
    return list;
  }

  /**
   * Find the number of {@link CodeBuilderAgent} instances still connecting to
   * Jenkins host.
   */
  private long numStillProvisioning() {
    return Jenkins.getInstance().getNodes().stream().filter(CodeBuilderAgent.class::isInstance)
        .map(CodeBuilderAgent.class::cast).filter(a -> a.getLauncher().isLaunchSupported()).count();
  }

  @Override
  public boolean canProvision(Label label) {
    boolean canProvision = label == null ? true : label.matches(Arrays.asList(new LabelAtom(getLabel())));
    LOGGER.info("[CodeBuilder]: Check provisioning capabilities for label '{}': {}", label, canProvision);
    return canProvision;
  }

  private static String getDefaultRegion() {
    try {
      return new DefaultAwsRegionProviderChain().getRegion();
    } catch (SdkClientException exc) {
      return null;
    }
  }

  @Extension
  public static class DescriptorImpl extends Descriptor<Cloud> {
    @Override
    public String getDisplayName() {
      return Messages.displayName();
    }

    public String getDefaultJnlpImage() {
      return DEFAULT_JNLP_IMAGE;
    }
    public String getDefaultJnlpCommand() {
      return DEFAULT_JNLP_COMMAND;
    }

    public int getDefaultAgentTimeout() {
      return DEFAULT_AGENT_TIMEOUT;
    }

    public String getDefaultComputeType() {
      return DEFAULT_COMPUTE_TYPE;
    }

    public ListBoxModel doFillCredentialsIdItems() {
      return AWSCredentialsHelper.doFillCredentialsIdItems(Jenkins.getInstance());
    }

    public ListBoxModel doFillRegionItems() {
      final ListBoxModel options = new ListBoxModel();

      String defaultRegion = getDefaultRegion();
      if (StringUtils.isNotBlank(defaultRegion)) {
        options.add(defaultRegion);
      }

      for (Region r : RegionUtils.getRegionsForService(AWSCodeBuild.ENDPOINT_PREFIX)) {
        if (r.getName() == defaultRegion) {
          continue;
        }
        options.add(r.getName());
      }
      return options;
    }

    public ListBoxModel doFillProjectNameItems(@QueryParameter String credentialsId, @QueryParameter String region) {
      if (StringUtils.isBlank(region)) {
        region = getDefaultRegion();
        if (StringUtils.isBlank(region)) {
          return new ListBoxModel();
        }
      }

      try {
        final List<String> projects = new ArrayList<String>();
        String lastToken = null;
        do {
          ListProjectsResult result = CodeBuilderCloud.buildClient(credentialsId, region)
              .listProjects(new ListProjectsRequest().withNextToken(lastToken));
          projects.addAll(result.getProjects());
          lastToken = result.getNextToken();
        } while (lastToken != null);
        Collections.sort(projects);
        final ListBoxModel options = new ListBoxModel();
        for (final String arn : projects) {
          options.add(arn);
        }
        return options;
      } catch (RuntimeException e) {
        // missing credentials will throw an "AmazonClientException: Unable to load AWS
        // credentials from any provider in the chain"
        LOGGER.error("[CodeBuilder]: Exception listing projects (region={})", region, e);
        return new ListBoxModel();
      }
    }

    public String getDefaultRegion() {
      return CodeBuilderCloud.getDefaultRegion();
    }
  }
}
