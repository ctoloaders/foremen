@smoke @FOR-03
Feature: Role-based menu visibility & authorization
  # FOR-03 menu-visibility smoke coverage (Requirements 6.1, 6.2, 6.3, 6.4).
  # The navigation surfaces are filtered by the caller's permission grants, so a
  # user only sees the items for resources they can READ. This slice verifies:
  #  - ADMIN sees the primary navigation and the current role shown in the shell;
  #  - an ACTIVE user whose role grants only USERS:READ sees the unrestricted and
  #    USERS items but NOT items for resources they lack (e.g. Roles, Projects);
  #  - a restricted user who deep-links to a forbidden route is redirected to /403
  #    with a working Go_Back control.
  # The restricted-role setup/teardown (create role + grant USERS:READ + create/
  # activate a user in it, then remove) uses the API helper + the reusable
  # test-user provisioning steps and honors the self-cleaning contract
  # (Requirement 6.4 / 3).

  Background:
    Given the application stack is ready

  Scenario: Admin sees the primary navigation and the current role in the shell
    Given I am logged in as the seeded admin
    Then the primary navigation groups are visible
    And the current role name is shown in the shell

  Scenario: A USERS:READ-only role hides navigation items for resources it lacks
    Given a role granting only "USERS" "READ" exists
    And a test user in the restricted role exists
    When I log in through the UI as the test user
    Then the navigation item for route "/users" is visible
    And the navigation item for route "/" is visible
    But the navigation item for route "/roles" is hidden
    And the navigation item for route "/projects" is hidden
    And the navigation item for route "/audit" is hidden

  Scenario: A restricted user deep-linking to a forbidden route lands on /403 with Go Back
    Given a role granting only "USERS" "READ" exists
    And a test user in the restricted role exists
    When I log in through the UI as the test user
    And I open the deep-link "/roles"
    Then I am shown the forbidden page
    When I click Go Back on the forbidden page
    Then I am taken away from the forbidden page
