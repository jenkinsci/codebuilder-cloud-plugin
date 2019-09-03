package dev.lsegal.jenkins.codebuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class CodeBuilderCloudTest {
  @Rule
  public JenkinsRule j = new JenkinsRule();

  @Test
  public void initializes_correctly() throws InterruptedException {
    CodeBuilderCloud cloud = new CodeBuilderCloud(null, "project", null, "local");
    assertEquals("project", cloud.getProjectName());
    assertEquals("codebuilder_0", cloud.getDisplayName());
    assertNotNull(cloud.getClient());
  }
}
