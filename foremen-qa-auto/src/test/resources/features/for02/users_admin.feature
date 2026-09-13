@smoke @FOR-02
Feature: Users admin — list and create
  # FOR-02 admin-panel smoke coverage (Requirements 7.3, 9.2, 4.1).
  # The Users page renders the users list; creating a user with a run-unique
  # generated email makes it appear in the list. The created user is deactivated
  # in teardown (via the same DELETE /api/users/{id} the UI uses), so the run
  # stays self-cleaning without manual DB cleanup. This create -> verify ->
  # deactivate lifecycle transitively exercises the FOR-01 CRUD framework
  # (Requirement 9.2).

  Background:
    Given the application stack is ready
    And I am logged in as the seeded admin

  Scenario: The users list renders and a created user appears in it
    When I open the Users page
    Then the users list renders
    When I create a user with a generated email
    Then the created user appears in the users list
