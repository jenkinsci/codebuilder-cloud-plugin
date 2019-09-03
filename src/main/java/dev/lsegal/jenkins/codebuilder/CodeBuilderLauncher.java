package dev.lsegal.jenkins.codebuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nonnull;

import com.amazonaws.services.codebuild.model.SourceType;
import com.amazonaws.services.codebuild.model.StartBuildRequest;
import com.amazonaws.services.codebuild.model.StartBuildResult;

import com.iwombat.util.StringUtil;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import hudson.util.StreamTaskListener;


/**
 * CodeBuilderLauncher class.
 *
 * @author Loren Segal
 */
public class CodeBuilderLauncher extends JNLPLauncher {
  private static final int sleepMs = 500;
  private static final Logger LOGGER = LoggerFactory.getLogger(CodeBuilderLauncher.class);

  private final CodeBuilderCloud cloud;
  private boolean launched;

  /**
   * Constructor for CodeBuilderLauncher.
   *
   * @param cloud a {@link CodeBuilderCloud} object.
   * @param tunnel tunnel URL if configured {@link String}
   * @param vmargs a {@link String}
   */
  public CodeBuilderLauncher(CodeBuilderCloud cloud, String tunnel, String vmargs) {
    super(tunnel, vmargs);
    this.cloud = cloud;
  }

  /** {@inheritDoc} */
  @Override
  public boolean isLaunchSupported() {
    return !launched;
  }

  /** {@inheritDoc} */
  @Override
  public void launch(@Nonnull SlaveComputer computer, @Nonnull TaskListener listener) {
    this.launched = false;
    if (!(computer instanceof CodeBuilderComputer)) {
      LOGGER.error("[CodeBuilder]: Not launching {} since it is not the correct type ({})", computer,
          CodeBuilderComputer.class.getName());
      return;
    }

    Node node = computer.getNode();
    if (node == null) {
      LOGGER.error("[CodeBuilder]: Not launching {} since it is missing a node.", computer);
      return;
    }

    LOGGER.info("[CodeBuilder]: Launching {} with {}", computer, listener);
    CodeBuilderComputer cbcpu = (CodeBuilderComputer) computer;
    StartBuildRequest req = new StartBuildRequest().withProjectName(cloud.getProjectName())
        .withSourceTypeOverride(SourceType.NO_SOURCE).withBuildspecOverride(buildspec(computer))
        .withImageOverride(cloud.getJnlpImage()).withPrivilegedModeOverride(true)
        .withComputeTypeOverride(cloud.getComputeType());

    try {
      StartBuildResult res = cloud.getClient().startBuild(req);
      String buildId = res.getBuild().getId();
      cbcpu.setBuildId(buildId);

      LOGGER.info("[CodeBuilder]: Waiting for agent '{}' to connect to build ID: {}...", computer, buildId);
      for (int i = 0; i < cloud.getAgentTimeout() * (1000 / sleepMs); i++) {
        if (computer.isOnline() && computer.isAcceptingTasks()) {
          LOGGER.info("[CodeBuilder]: Agent '{}' connected to build ID: {}.", computer, buildId);
          launched = true;
          return;
        }
        Thread.sleep(sleepMs);
      }
      throw new TimeoutException("Timed out while waiting for agent " + node + " to start for build ID: " + buildId);

    } catch (Exception e) {
      cbcpu.setBuildId(null);
      LOGGER.error("[CodeBuilder]: Exception while starting build: {}", e.getMessage(), e);
      listener.fatalError("Exception while starting build: %s", e.getMessage());

      if (node instanceof CodeBuilderAgent) {
        try {
          CodeBuilderCloud.jenkins().removeNode(node);
        } catch (IOException e1) {
          LOGGER.error("Failed to terminate agent: {}", node.getDisplayName(), e);
        }
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public void beforeDisconnect(@Nonnull SlaveComputer computer, @Nonnull StreamTaskListener listener) {
    if (computer instanceof CodeBuilderComputer) {
      ((CodeBuilderComputer) computer).setBuildId(null);
    }
  }

  private String buildspec(@Nonnull SlaveComputer computer) {
    Node n = computer.getNode();
    if (n == null) {
      return "";
    }
    Collection<String> command = new ArrayList<String>(Arrays.asList(
            "jenkins-agent",
            "-noreconnect",
            "-workDir",
            "\"$CODEBUILD_SRC_DIR\"",
            "-url",
            String.format("\"%s\"", cloud.getJenkinsUrl()),
            String.format("\"%s\"", computer.getJnlpMac()),
            String.format("\"%s\"", n.getDisplayName())
    ));
    if (StringUtils.isNotBlank(tunnel)) {
      command.add("-tunnel");
      command.add(cloud.getTunnel());
    }
    String cmd = String.join(" ", command);
    StringBuilder builder = new StringBuilder();
    builder.append("version: 0.2\n");
    builder.append("phases:\n");
    builder.append("  pre_build:\n");
    builder.append("    commands:\n");
    builder.append("      - which dockerd-entrypoint.sh >/dev/null && dockerd-entrypoint.sh || exit 0\n");
    builder.append("  build:\n");
    builder.append("    commands:\n");
    builder.append("      - " + cmd + " || exit 0\n");

    return builder.toString();
  }
}
