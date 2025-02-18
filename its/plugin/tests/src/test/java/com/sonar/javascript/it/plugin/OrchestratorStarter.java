/*
 * SonarQube JavaScript Plugin
 * Copyright (C) 2012-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.sonar.javascript.it.plugin;

import com.sonar.orchestrator.Orchestrator;
import com.sonar.orchestrator.build.SonarScanner;
import com.sonar.orchestrator.locator.FileLocation;
import com.sonar.orchestrator.locator.MavenLocation;
import java.io.File;
import java.util.List;
import java.util.Map;
import javax.annotation.CheckForNull;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.sonarqube.ws.Issues.Issue;
import org.sonarqube.ws.Measures.ComponentWsResponse;
import org.sonarqube.ws.Measures.Measure;
import org.sonarqube.ws.client.HttpConnector;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.issues.SearchRequest;
import org.sonarqube.ws.client.measures.ComponentRequest;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.extension.ExtensionContext.Namespace.GLOBAL;

public final class OrchestratorStarter implements BeforeAllCallback, ExtensionContext.Store.CloseableResource {

  static final String SCANNER_VERSION = "4.7.0.2747";
  static final FileLocation JAVASCRIPT_PLUGIN_LOCATION = FileLocation.byWildcardMavenFilename(
    new File("../../../sonar-javascript-plugin/target"), "sonar-javascript-plugin-*.jar");

  public static final Orchestrator ORCHESTRATOR = Orchestrator.builderEnv()
    .setSonarVersion(System.getProperty("sonar.runtimeVersion", "LATEST_RELEASE"))
    .addPlugin(MavenLocation.of("org.sonarsource.php", "sonar-php-plugin", "LATEST_RELEASE"))
    .addPlugin(MavenLocation.of("org.sonarsource.html", "sonar-html-plugin", "LATEST_RELEASE"))
    .addPlugin(MavenLocation.of("org.sonarsource.iac", "sonar-iac-plugin", "LATEST_RELEASE"))
    // required to load YAML files
    .addPlugin(MavenLocation.of("org.sonarsource.config", "sonar-config-plugin", "LATEST_RELEASE"))
    .addPlugin(JAVASCRIPT_PLUGIN_LOCATION)
    .restoreProfileAtStartup(FileLocation.ofClasspath("/empty-js-profile.xml"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/empty-ts-profile.xml"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/profile-javascript-custom-rules.xml"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/nosonar.xml"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/eslint-based-rules.xml"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/ts-eslint-based-rules.xml"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/js-with-ts-eslint-profile.xml"))
    .restoreProfileAtStartup(FileLocation.ofClasspath("/yaml-aws-lambda-profile.xml"))
    .build();

  private static volatile boolean started;

  private OrchestratorStarter() {
  }

  /**
   * make sure that whole test suite uses the same version of the scanner
   */
  static SonarScanner getSonarScanner() {
    return SonarScanner.create()
        .setScannerVersion(SCANNER_VERSION);
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    synchronized (OrchestratorStarter.class) {
      if (!started) {
        started = true;
        // this will register "this.close()" method to be called when GLOBAL context is shutdown
        context.getRoot().getStore(GLOBAL).put(OrchestratorStarter.class, this);
        ORCHESTRATOR.start();

        // to avoid a race condition in scanner file cache mechanism we analyze single project before any test to populate the cache
        testProject();
      }
    }
  }


  @Override
  public void close() {
    // this is executed once all tests are finished
    ORCHESTRATOR.stop();
  }

  public static SonarScanner createScanner() {
    SonarScanner scanner = getSonarScanner();
    scanner.setProperty("sonar.exclusions", "**/ecmascript6/**, **/file-for-rules/**, **/frameworks/**, **/jest/**/*, **/babylon/**/*");
    scanner.setSourceEncoding("UTF-8");
    return scanner;
  }

  public static void setEmptyProfile(String projectKey) {
    ORCHESTRATOR.getServer().provisionProject(projectKey, projectKey);
    ORCHESTRATOR.getServer().associateProjectToQualityProfile(projectKey, "ts", "empty-profile");
    ORCHESTRATOR.getServer().associateProjectToQualityProfile(projectKey, "js", "empty-profile");
  }

  public static void setProfile(String projectKey, String profileName, String language) {
    ORCHESTRATOR.getServer().provisionProject(projectKey, projectKey);
    ORCHESTRATOR.getServer().associateProjectToQualityProfile(projectKey, language, profileName);
  }

  public static void setProfiles(String projectKey, Map<String, String> profiles) {
    setProfiles(ORCHESTRATOR, projectKey, profiles);
  }

  static void setProfiles(Orchestrator orchestrator, String projectKey, Map<String, String> profiles) {
    orchestrator.getServer().provisionProject(projectKey, projectKey);
    profiles.forEach((profileName, language) -> orchestrator.getServer().associateProjectToQualityProfile(projectKey, language, profileName));
  }

  @CheckForNull
  static Measure getMeasure(String componentKey, String metricKey) {
    ComponentWsResponse response = newWsClient(ORCHESTRATOR).measures().component(new ComponentRequest()
      .setComponent(componentKey)
      .setMetricKeys(singletonList(metricKey)));
    List<Measure> measures = response.getComponent().getMeasuresList();
    return measures.size() == 1 ? measures.get(0) : null;
  }

  @CheckForNull
  static Integer getMeasureAsInt(String componentKey, String metricKey) {
    Measure measure = getMeasure(componentKey, metricKey);
    return (measure == null) ? null : Integer.parseInt(measure.getValue());
  }

  @CheckForNull
  static Double getMeasureAsDouble(String componentKey, String metricKey) {
    Measure measure = getMeasure(componentKey, metricKey);
    return (measure == null) ? null : Double.parseDouble(measure.getValue());
  }

  static WsClient newWsClient(Orchestrator orchestrator) {
    return WsClientFactories.getDefault().newClient(HttpConnector.newBuilder()
      .url(orchestrator.getServer().getUrl())
      .build());
  }

  static List<Issue> getIssues(String componentKey) {
    return getIssues(componentKey, null);
  }

  static List<Issue> getIssues(String componentKey, String pullRequest) {
    return getIssues(ORCHESTRATOR, componentKey, pullRequest);
  }

  static List<Issue> getIssues(Orchestrator orchestrator, String componentKey, String pullRequest) {
    SearchRequest request = new SearchRequest();
    request.setComponentKeys(singletonList(componentKey));
    if (pullRequest != null) {
      request.setPullRequest(pullRequest);
    }
    return newWsClient(orchestrator).issues().search(request).getIssuesList();
  }

  private static void testProject() {
    var projectKey = "eslint_based_rules";
    var projectDir = TestUtils.projectDir(projectKey);
    var build = getSonarScanner()
      .setProjectKey(projectKey)
      .setSourceEncoding("UTF-8")
      .setSourceDirs(".")
      .setProjectDir(projectDir);
    OrchestratorStarter.setProfile(projectKey, "empty-profile", "js");

    var buildResult = ORCHESTRATOR.executeBuild(build);
    assertThat(buildResult.isSuccess()).isTrue();
    assertThat(buildResult.getLogsLines(l -> l.startsWith("ERROR"))).isEmpty();
  }

}
