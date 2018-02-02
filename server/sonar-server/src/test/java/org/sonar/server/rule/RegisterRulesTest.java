/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
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
package org.sonar.server.rule;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.resources.Language;
import org.sonar.api.resources.Languages;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rule.RuleStatus;
import org.sonar.api.rules.RuleType;
import org.sonar.api.server.debt.DebtRemediationFunction;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.api.utils.DateUtils;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.log.LogTester;
import org.sonar.api.utils.log.LoggerLevel;
import org.sonar.core.util.UuidFactory;
import org.sonar.core.util.UuidFactoryFast;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.rule.DeprecatedRuleKeyDto;
import org.sonar.db.rule.RuleDefinitionDto;
import org.sonar.db.rule.RuleDto;
import org.sonar.db.rule.RuleParamDto;
import org.sonar.db.rule.RuleRepositoryDto;
import org.sonar.server.es.EsTester;
import org.sonar.server.es.SearchIdResult;
import org.sonar.server.es.SearchOptions;
import org.sonar.server.organization.OrganizationFlags;
import org.sonar.server.organization.TestOrganizationFlags;
import org.sonar.server.plugins.ServerPluginRepository;
import org.sonar.server.qualityprofile.QProfileRules;
import org.sonar.server.qualityprofile.index.ActiveRuleIndexer;
import org.sonar.server.rule.index.RuleIndex;
import org.sonar.server.rule.index.RuleIndexDefinition;
import org.sonar.server.rule.index.RuleIndexer;
import org.sonar.server.rule.index.RuleQuery;

import static com.google.common.collect.Sets.newHashSet;
import static java.lang.String.valueOf;
import static java.util.Collections.singletonList;
import static org.apache.commons.lang.RandomStringUtils.randomAlphanumeric;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonar.api.rule.Severity.BLOCKER;
import static org.sonar.api.rule.Severity.INFO;
import static org.sonar.api.server.rule.RulesDefinition.*;

@RunWith(DataProviderRunner.class)
public class RegisterRulesTest {

  private static final String FAKE_PLUGIN_KEY = "unittest";
  private static final Date DATE1 = DateUtils.parseDateTime("2014-01-01T19:10:03+0100");
  private static final Date DATE2 = DateUtils.parseDateTime("2014-02-01T12:10:03+0100");
  private static final Date DATE3 = DateUtils.parseDateTime("2014-03-01T12:10:03+0100");

  private static final RuleKey RULE_KEY1 = RuleKey.of("fake", "rule1");
  private static final RuleKey RULE_KEY2 = RuleKey.of("fake", "rule2");
  private static final RuleKey RULE_KEY3 = RuleKey.of("fake", "rule3");

  private System2 system = mock(System2.class);

  @org.junit.Rule
  public DbTester dbTester = DbTester.create(system);
  @org.junit.Rule
  public EsTester esTester = new EsTester(new RuleIndexDefinition(new MapSettings().asConfig()));
  @org.junit.Rule
  public LogTester logTester = new LogTester();

  private QProfileRules qProfileRules = mock(QProfileRules.class);
  private WebServerRuleFinder webServerRuleFinder = mock(WebServerRuleFinder.class);
  private DbClient dbClient = dbTester.getDbClient();
  private RuleIndexer ruleIndexer;
  private ActiveRuleIndexer activeRuleIndexer;
  private RuleIndex ruleIndex;
  private OrganizationDto defaultOrganization;
  private OrganizationFlags organizationFlags = TestOrganizationFlags.standalone();
  private UuidFactory uuidFactory = UuidFactoryFast.getInstance();

  @Before
  public void before() {
    when(system.now()).thenReturn(DATE1.getTime());
    ruleIndexer = new RuleIndexer(esTester.client(), dbClient);
    ruleIndex = new RuleIndex(esTester.client(), system);
    activeRuleIndexer = new ActiveRuleIndexer(dbClient, esTester.client());
    defaultOrganization = dbTester.getDefaultOrganization();
  }

  @Test
  public void insert_new_rules() {
    execute(new FakeRepositoryV1());

    // verify db
    assertThat(dbClient.ruleDao().selectAllDefinitions(dbTester.getSession())).hasSize(2);
    RuleDto rule1 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), dbTester.getDefaultOrganization(), RULE_KEY1);
    assertThat(rule1.getName()).isEqualTo("One");
    assertThat(rule1.getDescription()).isEqualTo("Description of One");
    assertThat(rule1.getSeverityString()).isEqualTo(BLOCKER);
    assertThat(rule1.getTags()).isEmpty();
    assertThat(rule1.getSystemTags()).containsOnly("tag1", "tag2", "tag3");
    assertThat(rule1.getConfigKey()).isEqualTo("config1");
    assertThat(rule1.getStatus()).isEqualTo(RuleStatus.BETA);
    assertThat(rule1.getCreatedAt()).isEqualTo(DATE1.getTime());
    assertThat(rule1.getUpdatedAt()).isEqualTo(DATE1.getTime());
    assertThat(rule1.getDefRemediationFunction()).isEqualTo(DebtRemediationFunction.Type.LINEAR_OFFSET.name());
    assertThat(rule1.getDefRemediationGapMultiplier()).isEqualTo("5d");
    assertThat(rule1.getDefRemediationBaseEffort()).isEqualTo("10h");
    assertThat(rule1.getType()).isEqualTo(RuleType.CODE_SMELL.getDbConstant());
    assertThat(rule1.getPluginKey()).isEqualTo(FAKE_PLUGIN_KEY);

    List<RuleParamDto> params = dbClient.ruleDao().selectRuleParamsByRuleKey(dbTester.getSession(), RULE_KEY1);
    assertThat(params).hasSize(2);
    RuleParamDto param = getParam(params, "param1");
    assertThat(param.getDescription()).isEqualTo("parameter one");
    assertThat(param.getDefaultValue()).isEqualTo("default1");

    // verify index
    RuleDto rule2 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), dbTester.getDefaultOrganization(), RULE_KEY2);
    assertThat(ruleIndex.search(new RuleQuery(), new SearchOptions()).getIds()).containsOnly(rule1.getId(), rule2.getId());

    // verify repositories
    assertThat(dbClient.ruleRepositoryDao().selectAll(dbTester.getSession())).extracting(RuleRepositoryDto::getKey).containsOnly("fake");
  }

  @Test
  public void insert_then_remove_rule() {
    String ruleKey = randomAlphanumeric(5);

    // register one rule
    execute(context -> {
      RulesDefinition.NewRepository repo = context.createRepository("fake", "java");
      repo.createRule(ruleKey)
        .setName(randomAlphanumeric(5))
        .setHtmlDescription(randomAlphanumeric(20));
      repo.done();
    });

    // verify db
    List<RuleDefinitionDto> rules = dbClient.ruleDao().selectAllDefinitions(dbTester.getSession());
    assertThat(rules)
      .extracting(RuleDefinitionDto::getKey)
      .extracting(RuleKey::rule)
      .containsExactly(ruleKey);
    RuleDefinitionDto rule = rules.iterator().next();

    // verify index
    assertThat(ruleIndex.search(new RuleQuery(), new SearchOptions()).getIds())
      .containsExactly(rule.getId());

    // register no rule
    execute(context -> context.createRepository("fake", "java").done());

    // verify db
    assertThat(dbClient.ruleDao().selectAllDefinitions(dbTester.getSession()))
      .extracting(RuleDefinitionDto::getKey)
      .extracting(RuleKey::rule)
      .containsExactly(ruleKey);
    assertThat(dbClient.ruleDao().selectAllDefinitions(dbTester.getSession()))
      .extracting(RuleDefinitionDto::getStatus)
      .containsExactly(RuleStatus.REMOVED);

    // verify index
    assertThat(ruleIndex.search(new RuleQuery(), new SearchOptions()).getIds())
      .isEmpty();
  }

  @Test
  public void mass_insert_then_remove_rule() {
    int numberOfRules = 5000;

    // register many rules
    execute(context -> {
      NewRepository repo = context.createRepository("fake", "java");
      IntStream.range(0, numberOfRules)
        .mapToObj(i -> "rule-" + i)
        .forEach(ruleKey -> repo.createRule(ruleKey)
          .setName(randomAlphanumeric(20))
          .setHtmlDescription(randomAlphanumeric(20)));
      repo.done();
    });

    // verify db
    assertThat(dbClient.ruleDao().selectAllDefinitions(dbTester.getSession()))
      .hasSize(numberOfRules)
      .extracting(RuleDefinitionDto::getStatus)
      .containsOnly(RuleStatus.READY);

    // verify index
    assertThat(esTester.countDocuments(RuleIndexDefinition.INDEX_TYPE_RULE)).isEqualTo(numberOfRules);
    assertThat(ruleIndex.search(new RuleQuery(), new SearchOptions()).getIds())
      .isNotEmpty();

    // register no rule
    execute(context -> context.createRepository("fake", "java").done());

    // verify db
    assertThat(dbClient.ruleDao().selectAllDefinitions(dbTester.getSession()))
      .hasSize(numberOfRules)
      .extracting(RuleDefinitionDto::getStatus)
      .containsOnly(RuleStatus.REMOVED);

    // verify index (documents are still in the index, but all are removed)
    assertThat(esTester.countDocuments(RuleIndexDefinition.INDEX_TYPE_RULE)).isEqualTo(numberOfRules);
    assertThat(ruleIndex.search(new RuleQuery(), new SearchOptions()).getIds())
      .isEmpty();
  }

  @Test
  public void delete_repositories_that_have_been_uninstalled() {
    RuleRepositoryDto repository = new RuleRepositoryDto("findbugs", "java", "Findbugs");
    DbSession dbSession = dbTester.getSession();
    dbTester.getDbClient().ruleRepositoryDao().insert(dbSession, singletonList(repository));
    dbSession.commit();

    execute(new FakeRepositoryV1());

    assertThat(dbTester.getDbClient().ruleRepositoryDao().selectAll(dbSession)).extracting(RuleRepositoryDto::getKey).containsOnly("fake");
  }

  @Test
  public void update_and_remove_rules_on_changes() {
    execute(new FakeRepositoryV1());
    assertThat(dbClient.ruleDao().selectAllDefinitions(dbTester.getSession())).hasSize(2);
    RuleDto rule1 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY1);
    RuleDto rule2 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY2);
    assertThat(esTester.getIds(RuleIndexDefinition.INDEX_TYPE_RULE)).containsOnly(valueOf(rule1.getId()), valueOf(rule2.getId()));

    // user adds tags and sets markdown note
    rule1.setTags(newHashSet("usertag1", "usertag2"));
    rule1.setNoteData("user *note*");
    rule1.setNoteUserLogin("marius");
    dbClient.ruleDao().insertOrUpdate(dbTester.getSession(), rule1.getMetadata());
    dbTester.getSession().commit();

    when(system.now()).thenReturn(DATE2.getTime());
    execute(new FakeRepositoryV2());

    // rule1 has been updated
    rule1 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY1);
    assertThat(rule1.getName()).isEqualTo("One v2");
    assertThat(rule1.getDescription()).isEqualTo("Description of One v2");
    assertThat(rule1.getSeverityString()).isEqualTo(INFO);
    assertThat(rule1.getTags()).containsOnly("usertag1", "usertag2");
    assertThat(rule1.getSystemTags()).containsOnly("tag1", "tag4");
    assertThat(rule1.getConfigKey()).isEqualTo("config1 v2");
    assertThat(rule1.getNoteData()).isEqualTo("user *note*");
    assertThat(rule1.getNoteUserLogin()).isEqualTo("marius");
    assertThat(rule1.getStatus()).isEqualTo(RuleStatus.READY);
    assertThat(rule1.getType()).isEqualTo(RuleType.BUG.getDbConstant());
    assertThat(rule1.getCreatedAt()).isEqualTo(DATE1.getTime());
    assertThat(rule1.getUpdatedAt()).isEqualTo(DATE2.getTime());
    // TODO check remediation function

    List<RuleParamDto> params = dbClient.ruleDao().selectRuleParamsByRuleKey(dbTester.getSession(), RULE_KEY1);
    assertThat(params).hasSize(2);
    RuleParamDto param = getParam(params, "param1");
    assertThat(param.getDescription()).isEqualTo("parameter one v2");
    assertThat(param.getDefaultValue()).isEqualTo("default1 v2");

    // rule2 has been removed -> status set to REMOVED but db row is not deleted
    rule2 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY2);
    assertThat(rule2.getStatus()).isEqualTo(RuleStatus.REMOVED);
    assertThat(rule2.getUpdatedAt()).isEqualTo(DATE2.getTime());

    // rule3 has been created
    RuleDto rule3 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY3);
    assertThat(rule3).isNotNull();
    assertThat(rule3.getStatus()).isEqualTo(RuleStatus.READY);

    // verify index
    assertThat(ruleIndex.search(new RuleQuery(), new SearchOptions()).getIds()).containsOnly(rule1.getId(), rule3.getId());

    // verify repositories
    assertThat(dbClient.ruleRepositoryDao().selectAll(dbTester.getSession())).extracting(RuleRepositoryDto::getKey).containsOnly("fake");
  }

  @Test
  public void add_new_tag() {
    execute((RulesDefinition) context -> {
      NewRepository repo = context.createRepository("fake", "java");
      repo.createRule("rule1")
        .setName("Rule One")
        .setHtmlDescription("Description of Rule One")
        .setTags("tag1");
      repo.done();
    });

    OrganizationDto defaultOrganization = dbTester.getDefaultOrganization();
    RuleDto rule = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY1);
    assertThat(rule.getSystemTags()).containsOnly("tag1");

    execute((RulesDefinition) context -> {
      NewRepository repo = context.createRepository("fake", "java");
      repo.createRule("rule1")
        .setName("Rule One")
        .setHtmlDescription("Description of Rule One")
        .setTags("tag1", "tag2");
      repo.done();
    });

    rule = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY1);
    assertThat(rule.getSystemTags()).containsOnly("tag1", "tag2");
  }

  @Test
  public void update_only_rule_name() {
    when(system.now()).thenReturn(DATE1.getTime());
    execute((RulesDefinition) context -> {
      NewRepository repo = context.createRepository("fake", "java");
      repo.createRule("rule")
        .setName("Name1")
        .setHtmlDescription("Description");
      repo.done();
    });

    when(system.now()).thenReturn(DATE2.getTime());
    execute((RulesDefinition) context -> {
      NewRepository repo = context.createRepository("fake", "java");
      repo.createRule("rule")
        .setName("Name2")
        .setHtmlDescription("Description");
      repo.done();
    });

    // rule1 has been updated
    RuleDto rule1 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RuleKey.of("fake", "rule"));
    assertThat(rule1.getName()).isEqualTo("Name2");
    assertThat(rule1.getDescription()).isEqualTo("Description");

    assertThat(ruleIndex.search(new RuleQuery().setQueryText("Name2"), new SearchOptions()).getTotal()).isEqualTo(1);
    assertThat(ruleIndex.search(new RuleQuery().setQueryText("Name1"), new SearchOptions()).getTotal()).isEqualTo(0);
  }

  @Test
  public void update_if_rule_key_renamed_and_deprecated_key_declared() {
    String ruleKey1 = "rule1";
    String ruleKey2 = "rule2";
    String repository = "fake";

    when(system.now()).thenReturn(DATE1.getTime());
    execute((RulesDefinition) context -> {
      RulesDefinition.NewRepository repo = context.createRepository(repository, "java");
      repo.createRule(ruleKey1)
        .setName("Name1")
        .setHtmlDescription("Description");
      repo.done();
    });

    RuleDto rule1 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RuleKey.of(repository, ruleKey1));
    SearchIdResult<Integer> searchRule1 = ruleIndex.search(new RuleQuery().setQueryText("Name1"), new SearchOptions());
    assertThat(searchRule1.getIds()).containsOnly(rule1.getId());
    assertThat(searchRule1.getTotal()).isEqualTo(1);

    when(system.now()).thenReturn(DATE2.getTime());
    execute((RulesDefinition) context -> {
      RulesDefinition.NewRepository repo = context.createRepository(repository, "java");
      repo.createRule(ruleKey2)
        .setName("Name2")
        .setHtmlDescription("Description")
        .addDeprecatedRuleKey(repository, ruleKey1);
      repo.done();
    });

    // rule2 is actually rule1
    RuleDto rule2 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RuleKey.of(repository, ruleKey2));
    assertThat(rule2.getId()).isEqualTo(rule1.getId());
    assertThat(rule2.getName()).isEqualTo("Name2");
    assertThat(rule2.getDescription()).isEqualTo(rule1.getDescription());

    SearchIdResult<Integer> searchRule2 = ruleIndex.search(new RuleQuery().setQueryText("Name2"), new SearchOptions());
    assertThat(searchRule2.getIds()).containsOnly(rule2.getId());
    assertThat(searchRule2.getTotal()).isEqualTo(1);
    assertThat(ruleIndex.search(new RuleQuery().setQueryText("Name1"), new SearchOptions()).getTotal()).isEqualTo(0);
  }

  @Test
  public void update_if_repository_changed_and_deprecated_key_declared() {
    String ruleKey = "rule";
    String repository1 = "fake1";
    String repository2 = "fake2";

    when(system.now()).thenReturn(DATE1.getTime());
    execute((RulesDefinition) context -> {
      RulesDefinition.NewRepository repo = context.createRepository(repository1, "java");
      repo.createRule(ruleKey)
        .setName("Name1")
        .setHtmlDescription("Description");
      repo.done();
    });

    RuleDto rule1 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RuleKey.of(repository1, ruleKey));
    SearchIdResult<Integer> searchRule1 = ruleIndex.search(new RuleQuery().setQueryText("Name1"), new SearchOptions());
    assertThat(searchRule1.getIds()).containsOnly(rule1.getId());
    assertThat(searchRule1.getTotal()).isEqualTo(1);

    when(system.now()).thenReturn(DATE2.getTime());
    execute((RulesDefinition) context -> {
      RulesDefinition.NewRepository repo = context.createRepository(repository2, "java");
      repo.createRule(ruleKey)
        .setName("Name2")
        .setHtmlDescription("Description")
        .addDeprecatedRuleKey(repository1, ruleKey);
      repo.done();
    });

    // rule2 is actually rule1
    RuleDto rule2 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RuleKey.of(repository2, ruleKey));
    assertThat(rule2.getId()).isEqualTo(rule1.getId());
    assertThat(rule2.getName()).isEqualTo("Name2");
    assertThat(rule2.getDescription()).isEqualTo(rule1.getDescription());

    SearchIdResult<Integer> searchRule2 = ruleIndex.search(new RuleQuery().setQueryText("Name2"), new SearchOptions());
    assertThat(searchRule2.getIds()).containsOnly(rule2.getId());
    assertThat(searchRule2.getTotal()).isEqualTo(1);
    assertThat(ruleIndex.search(new RuleQuery().setQueryText("Name1"), new SearchOptions()).getTotal()).isEqualTo(0);
  }

  @Test
  @UseDataProvider("allRenamingCases")
  public void update_if_only_renamed_and_deprecated_key_declared(String ruleKey1, String repo1, String ruleKey2, String repo2) {
    String name = "Name1";
    String description = "Description";
    when(system.now()).thenReturn(DATE1.getTime());
    execute((RulesDefinition) context -> {
      RulesDefinition.NewRepository repo = context.createRepository(repo1, "java");
      repo.createRule(ruleKey1)
        .setName(name)
        .setHtmlDescription(description);
      repo.done();
    });

    RuleDto rule1 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RuleKey.of(repo1, ruleKey1));
    assertThat(ruleIndex.search(new RuleQuery().setQueryText(name), new SearchOptions()).getIds())
      .containsOnly(rule1.getId());

    when(system.now()).thenReturn(DATE2.getTime());
    execute((RulesDefinition) context -> {
      RulesDefinition.NewRepository repo = context.createRepository(repo2, "java");
      repo.createRule(ruleKey2)
        .setName(name)
        .setHtmlDescription(description)
        .addDeprecatedRuleKey(repo1, ruleKey1);
      repo.done();
    });

    // rule2 is actually rule1
    RuleDto rule2 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RuleKey.of(repo2, ruleKey2));
    assertThat(rule2.getId()).isEqualTo(rule1.getId());
    assertThat(rule2.getName()).isEqualTo(rule1.getName());
    assertThat(rule2.getDescription()).isEqualTo(rule1.getDescription());

    assertThat(ruleIndex.search(new RuleQuery().setQueryText(name), new SearchOptions()).getIds())
      .containsOnly(rule2.getId());
  }

  @DataProvider
  public static Object[][] allRenamingCases() {
    return new Object[][] {
      {"repo1", "rule1", "repo1", "rule2"},
      {"repo1", "rule1", "repo2", "rule1"},
      {"repo1", "rule1", "repo2", "rule2"},
    };
  }

  @Test
  public void update_if_repository_and_key_changed_and_deprecated_key_declared_among_others() {
    String ruleKey1 = "rule1";
    String ruleKey2 = "rule2";
    String repository1 = "fake1";
    String repository2 = "fake2";

    when(system.now()).thenReturn(DATE1.getTime());
    execute((RulesDefinition) context -> {
      RulesDefinition.NewRepository repo = context.createRepository(repository1, "java");
      repo.createRule(ruleKey1)
        .setName("Name1")
        .setHtmlDescription("Description");
      repo.done();
    });

    RuleDto rule1 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RuleKey.of(repository1, ruleKey1));
    assertThat(ruleIndex.search(new RuleQuery().setQueryText("Name1"), new SearchOptions()).getIds())
      .containsOnly(rule1.getId());

    when(system.now()).thenReturn(DATE2.getTime());
    execute((RulesDefinition) context -> {
      RulesDefinition.NewRepository repo = context.createRepository(repository2, "java");
      repo.createRule(ruleKey2)
        .setName("Name2")
        .setHtmlDescription("Description")
        .addDeprecatedRuleKey("foo", "bar")
        .addDeprecatedRuleKey(repository1, ruleKey1)
        .addDeprecatedRuleKey("some", "noise");
      repo.done();
    });

    // rule2 is actually rule1
    RuleDto rule2 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RuleKey.of(repository2, ruleKey2));
    assertThat(rule2.getId()).isEqualTo(rule1.getId());

    assertThat(ruleIndex.search(new RuleQuery().setQueryText("Name2"), new SearchOptions()).getIds())
      .containsOnly(rule1.getId());
  }

  @Test
  public void update_only_rule_description() {
    when(system.now()).thenReturn(DATE1.getTime());
    execute((RulesDefinition) context -> {
      NewRepository repo = context.createRepository("fake", "java");
      repo.createRule("rule")
        .setName("Name")
        .setHtmlDescription("Desc1");
      repo.done();
    });

    when(system.now()).thenReturn(DATE2.getTime());
    execute((RulesDefinition) context -> {
      NewRepository repo = context.createRepository("fake", "java");
      repo.createRule("rule")
        .setName("Name")
        .setHtmlDescription("Desc2");
      repo.done();
    });

    // rule1 has been updated
    RuleDto rule1 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RuleKey.of("fake", "rule"));
    assertThat(rule1.getName()).isEqualTo("Name");
    assertThat(rule1.getDescription()).isEqualTo("Desc2");

    assertThat(ruleIndex.search(new RuleQuery().setQueryText("Desc2"), new SearchOptions()).getTotal()).isEqualTo(1);
    assertThat(ruleIndex.search(new RuleQuery().setQueryText("Desc1"), new SearchOptions()).getTotal()).isEqualTo(0);
  }

  @Test
  public void disable_then_enable_rule() {
    // Install rule
    when(system.now()).thenReturn(DATE1.getTime());
    execute(new FakeRepositoryV1());

    // Uninstall rule
    when(system.now()).thenReturn(DATE2.getTime());
    execute();

    RuleDto rule = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY1);
    assertThat(rule.getStatus()).isEqualTo(RuleStatus.REMOVED);
    assertThat(ruleIndex.search(new RuleQuery().setKey(RULE_KEY1.toString()), new SearchOptions()).getTotal()).isEqualTo(0);

    // Re-install rule
    when(system.now()).thenReturn(DATE3.getTime());
    execute(new FakeRepositoryV1());

    rule = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY1);
    assertThat(rule.getStatus()).isEqualTo(RuleStatus.BETA);
    assertThat(ruleIndex.search(new RuleQuery().setKey(RULE_KEY1.toString()), new SearchOptions()).getTotal()).isEqualTo(1);
  }

  @Test
  public void do_not_update_rules_when_no_changes() {
    execute(new FakeRepositoryV1());
    assertThat(dbClient.ruleDao().selectAllDefinitions(dbTester.getSession())).hasSize(2);

    when(system.now()).thenReturn(DATE2.getTime());
    execute(new FakeRepositoryV1());

    RuleDto rule1 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY1);
    assertThat(rule1.getCreatedAt()).isEqualTo(DATE1.getTime());
    assertThat(rule1.getUpdatedAt()).isEqualTo(DATE1.getTime());
  }

  @Test
  public void do_not_update_already_removed_rules() {
    execute(new FakeRepositoryV1());
    assertThat(dbClient.ruleDao().selectAllDefinitions(dbTester.getSession())).hasSize(2);
    RuleDto rule1 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY1);
    RuleDto rule2 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY2);
    assertThat(esTester.getIds(RuleIndexDefinition.INDEX_TYPE_RULE)).containsOnly(valueOf(rule1.getId()), valueOf(rule2.getId()));

    assertThat(rule2.getStatus()).isEqualTo(RuleStatus.READY);

    when(system.now()).thenReturn(DATE2.getTime());
    execute(new FakeRepositoryV2());

    // On MySQL, need to update a rule otherwise rule2 will be seen as READY, but why ???
    dbClient.ruleDao().update(dbTester.getSession(), rule1.getDefinition());
    dbTester.getSession().commit();

    // rule2 is removed
    rule2 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY2);
    RuleDto rule3 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY3);
    assertThat(rule2.getStatus()).isEqualTo(RuleStatus.REMOVED);

    assertThat(ruleIndex.search(new RuleQuery(), new SearchOptions()).getIds()).containsOnly(rule1.getId(), rule3.getId());

    when(system.now()).thenReturn(DATE3.getTime());
    execute(new FakeRepositoryV2());
    dbTester.getSession().commit();

    // -> rule2 is still removed, but not update at DATE3
    rule2 = dbClient.ruleDao().selectOrFailByKey(dbTester.getSession(), defaultOrganization, RULE_KEY2);
    assertThat(rule2.getStatus()).isEqualTo(RuleStatus.REMOVED);
    assertThat(rule2.getUpdatedAt()).isEqualTo(DATE2.getTime());

    assertThat(ruleIndex.search(new RuleQuery(), new SearchOptions()).getIds()).containsOnly(rule1.getId(), rule3.getId());
  }

  @Test
  public void mass_insert() {
    execute(new BigRepository());
    assertThat(dbTester.countRowsOfTable("rules")).isEqualTo(BigRepository.SIZE);
    assertThat(dbTester.countRowsOfTable("rules_parameters")).isEqualTo(BigRepository.SIZE * 20);
    assertThat(esTester.getIds(RuleIndexDefinition.INDEX_TYPE_RULE)).hasSize(BigRepository.SIZE);
  }

  @Test
  public void manage_repository_extensions() {
    execute(new FindbugsRepository(), new FbContribRepository());
    List<RuleDefinitionDto> rules = dbClient.ruleDao().selectAllDefinitions(dbTester.getSession());
    assertThat(rules).hasSize(2);
    for (RuleDefinitionDto rule : rules) {
      assertThat(rule.getRepositoryKey()).isEqualTo("findbugs");
    }
  }

  @Test
  public void remove_system_tags_when_plugin_does_not_provide_any() {
    // Rule already exists in DB, with some system tags
    dbClient.ruleDao().insert(dbTester.getSession(), new RuleDefinitionDto()
      .setRuleKey("rule1")
      .setRepositoryKey("findbugs")
      .setName("Rule One")
      .setDescription("Rule one description")
      .setDescriptionFormat(RuleDto.Format.HTML)
      .setSystemTags(newHashSet("tag1", "tag2")));
    dbTester.getSession().commit();

    // Synchronize rule without tag
    execute(new FindbugsRepository());

    List<RuleDefinitionDto> rules = dbClient.ruleDao().selectAllDefinitions(dbTester.getSession());
    assertThat(rules).hasSize(1);
    RuleDefinitionDto result = rules.get(0);
    assertThat(result.getKey()).isEqualTo(RuleKey.of("findbugs", "rule1"));
    assertThat(result.getSystemTags()).isEmpty();
  }

  @Test
  public void ignore_template_rules_if_organizations_are_enabled() {
    organizationFlags.enable(dbTester.getSession());
    execute(new RepositoryWithOneTemplateRule());

    List<RuleDefinitionDto> rules = dbClient.ruleDao().selectAllDefinitions(dbTester.getSession());
    assertThat(rules).hasSize(0);
  }

  @Test
  public void log_ignored_template_rules_if_organizations_are_enabled() {
    organizationFlags.enable(dbTester.getSession());
    execute(new RepositoryWithOneTemplateRule());

    assertThat(logTester.logs(LoggerLevel.INFO)).contains("Template rule test:rule1 will not be imported, because organizations are enabled.");
  }

  @Test
  public void rules_that_deprecate_previous_rule_must_be_recorded() {
    execute(context -> {
      NewRepository repo = context.createRepository("fake", "java");
      repo.createRule("rule1")
        .setName("One")
        .setHtmlDescription("Description of One")
        .setSeverity(BLOCKER)
        .setInternalKey("config1")
        .setTags("tag1", "tag2", "tag3")
        .setType(RuleType.CODE_SMELL)
        .setStatus(RuleStatus.BETA);
      repo.done();
    });

    execute(context -> {
      NewRepository repo = context.createRepository("fake", "java");
      repo.createRule("newKey")
        .setName("One")
        .setHtmlDescription("Description of One")
        .setSeverity(BLOCKER)
        .setInternalKey("config1")
        .setTags("tag1", "tag2", "tag3")
        .setType(RuleType.CODE_SMELL)
        .setStatus(RuleStatus.BETA)
        .addDeprecatedRuleKey("fake", "rule1")
        .addDeprecatedRuleKey("fake", "rule2");
      repo.done();
    });

    List<RuleDefinitionDto> rules = dbClient.ruleDao().selectAllDefinitions(dbTester.getSession());
    Set<DeprecatedRuleKeyDto> deprecatedRuleKeys = dbClient.ruleDao().selectAllDeprecatedRuleKeys(dbTester.getSession());
    assertThat(rules).hasSize(1);
    assertThat(deprecatedRuleKeys).hasSize(2);
  }

  @Test
  public void rules_that_remove_deprecated_key_must_remove_records() {
    execute(context -> {
      NewRepository repo = context.createRepository("fake", "java");
      repo.createRule("rule1")
        .setName("One")
        .setHtmlDescription("Description of One")
        .setSeverity(BLOCKER)
        .setInternalKey("config1")
        .setTags("tag1", "tag2", "tag3")
        .setType(RuleType.CODE_SMELL)
        .setStatus(RuleStatus.BETA);
      repo.done();
    });

    execute(context -> {
      NewRepository repo = context.createRepository("fake", "java");
      repo.createRule("newKey")
        .setName("One")
        .setHtmlDescription("Description of One")
        .setSeverity(BLOCKER)
        .setInternalKey("config1")
        .setTags("tag1", "tag2", "tag3")
        .setType(RuleType.CODE_SMELL)
        .setStatus(RuleStatus.BETA)
        .addDeprecatedRuleKey("fake", "rule1")
        .addDeprecatedRuleKey("fake", "rule2");
      repo.done();
    });

    assertThat(dbClient.ruleDao().selectAllDefinitions(dbTester.getSession())).hasSize(1);
    Set<DeprecatedRuleKeyDto> deprecatedRuleKeys = dbClient.ruleDao().selectAllDeprecatedRuleKeys(dbTester.getSession());
    assertThat(deprecatedRuleKeys).hasSize(2);

    execute(context -> {
      NewRepository repo = context.createRepository("fake", "java");
      repo.createRule("newKey")
        .setName("One")
        .setHtmlDescription("Description of One")
        .setSeverity(BLOCKER)
        .setInternalKey("config1")
        .setTags("tag1", "tag2", "tag3")
        .setType(RuleType.CODE_SMELL)
        .setStatus(RuleStatus.BETA);
      repo.done();
    });

    assertThat(dbClient.ruleDao().selectAllDefinitions(dbTester.getSession())).hasSize(1);
    deprecatedRuleKeys = dbClient.ruleDao().selectAllDeprecatedRuleKeys(dbTester.getSession());
    assertThat(deprecatedRuleKeys).hasSize(0);
  }

  private void execute(RulesDefinition... defs) {
    ServerPluginRepository pluginRepository = mock(ServerPluginRepository.class);
    when(pluginRepository.getPluginKey(any(RulesDefinition.class))).thenReturn(FAKE_PLUGIN_KEY);
    RuleDefinitionsLoader loader = new RuleDefinitionsLoader(mock(DeprecatedRulesDefinitionLoader.class), mock(CommonRuleDefinitionsImpl.class), pluginRepository,
      defs);
    Languages languages = mock(Languages.class);
    when(languages.get("java")).thenReturn(mock(Language.class));
    reset(webServerRuleFinder);

    RegisterRules task = new RegisterRules(loader, qProfileRules, dbClient, ruleIndexer, activeRuleIndexer,
      languages, system, organizationFlags, webServerRuleFinder, uuidFactory);
    task.start();
    // Execute a commit to refresh session state as the task is using its own sessiongci
    dbTester.getSession().commit();

    verify(webServerRuleFinder).startCaching();
  }

  private RuleParamDto getParam(List<RuleParamDto> params, String key) {
    for (RuleParamDto param : params) {
      if (param.getName().equals(key)) {
        return param;
      }
    }
    return null;
  }

  static class FakeRepositoryV1 implements RulesDefinition {
    @Override
    public void define(Context context) {
      NewRepository repo = context.createRepository("fake", "java");
      NewRule rule1 = repo.createRule(RULE_KEY1.rule())
        .setName("One")
        .setHtmlDescription("Description of One")
        .setSeverity(BLOCKER)
        .setInternalKey("config1")
        .setTags("tag1", "tag2", "tag3")
        .setType(RuleType.CODE_SMELL)
        .setStatus(RuleStatus.BETA)
        .setGapDescription("squid.S115.effortToFix");
      rule1.setDebtRemediationFunction(rule1.debtRemediationFunctions().linearWithOffset("5d", "10h"));

      rule1.createParam("param1").setDescription("parameter one").setDefaultValue("default1");
      rule1.createParam("param2").setDescription("parameter two").setDefaultValue("default2");

      repo.createRule(RULE_KEY2.rule())
        .setName("Two")
        .setHtmlDescription("Minimal rule");
      repo.done();
    }
  }

  /**
   * FakeRepositoryV1 with some changes
   */
  static class FakeRepositoryV2 implements RulesDefinition {
    @Override
    public void define(Context context) {
      NewRepository repo = context.createRepository("fake", "java");

      // almost all the attributes of rule1 are changed
      NewRule rule1 = repo.createRule(RULE_KEY1.rule())
        .setName("One v2")
        .setHtmlDescription("Description of One v2")
        .setSeverity(INFO)
        .setInternalKey("config1 v2")
        // tag2 and tag3 removed, tag4 added
        .setTags("tag1", "tag4")
        .setType(RuleType.BUG)
        .setStatus(RuleStatus.READY)
        .setGapDescription("squid.S115.effortToFix.v2");
      rule1.setDebtRemediationFunction(rule1.debtRemediationFunctions().linearWithOffset("6d", "2h"));
      rule1.createParam("param1").setDescription("parameter one v2").setDefaultValue("default1 v2");
      rule1.createParam("param2").setDescription("parameter two v2").setDefaultValue("default2 v2");

      // rule2 is dropped, rule3 is new
      repo.createRule(RULE_KEY3.rule())
        .setName("Three")
        .setHtmlDescription("Rule Three");
      repo.done();
    }
  }

  static class BigRepository implements RulesDefinition {
    static final int SIZE = 500;

    @Override
    public void define(Context context) {
      NewRepository repo = context.createRepository("big", "java");
      for (int i = 0; i < SIZE; i++) {
        NewRule rule = repo.createRule("rule" + i)
          .setName("name of " + i)
          .setHtmlDescription("description of " + i);
        for (int j = 0; j < 20; j++) {
          rule.createParam("param" + j);
        }

      }
      repo.done();
    }
  }

  static class FindbugsRepository implements RulesDefinition {
    @Override
    public void define(Context context) {
      NewRepository repo = context.createRepository("findbugs", "java");
      repo.createRule("rule1")
        .setName("Rule One")
        .setHtmlDescription("Description of Rule One");
      repo.done();
    }
  }

  static class FbContribRepository implements RulesDefinition {

    @Override
    public void define(Context context) {
      NewExtendedRepository repo = context.extendRepository("findbugs", "java");
      repo.createRule("rule2")
        .setName("Rule Two")
        .setHtmlDescription("Description of Rule Two");
      repo.done();
    }
  }

  static class RepositoryWithOneTemplateRule implements RulesDefinition {
    @Override
    public void define(Context context) {
      NewRepository repo = context.createRepository("test", "java");
      repo.createRule("rule1")
        .setName("Rule One")
        .setHtmlDescription("Description of Rule One")
        .setTemplate(true);
      repo.done();
    }
  }
}
