package hudson.plugins.virtualbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import hudson.util.Secret;

/**
 * @author Evgeny Mandrikov
 */
public class VirtualBoxCloudTest {

  @Rule
  public JenkinsRule j = new JenkinsRule();

  @Test
  public void testPluginStart() {
    assertTrue(j.jenkins.getPluginManager().getPlugin("virtualbox").isActive());
  }

  @Test
  public void testConfigRoundtrip() throws Exception {
    VirtualBoxCloud orig = new VirtualBoxCloud("Test", "http://localhost:18083", "godin", Secret.fromString("12345"));
    j.jenkins.clouds.add(orig);

    j.submit(j.createWebClient().goTo("configure").getFormByName("config"));

    VirtualBoxCloud after = (VirtualBoxCloud) j.jenkins.clouds.iterator().next();
    assertNotNull(after);
    assertEquals("Test", after.getDisplayName());
    assertEquals("http://localhost:18083", after.getUrl());
    assertEquals("godin", after.getUsername());
    assertEquals("12345", after.getPassword().getPlainText());
  }
}
