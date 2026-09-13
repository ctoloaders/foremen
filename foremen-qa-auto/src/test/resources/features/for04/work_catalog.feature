@smoke @FOR-04
Feature: Work Catalog — list and create with FK selects
  # FOR-04 base-entities smoke coverage (Requirements 8.2, 4.1, 9.2).
  # The Work Catalog page renders the list; creating a work item selects a
  # category and a unit via the two FK reference selects, then the item appears
  # in the list. The FK category and unit are run-created via the API so the
  # picks are deterministic; the work item and its FK rows are torn down in
  # FK-safe LIFO order (self-cleaning, Requirement 3). This create -> verify ->
  # delete lifecycle transitively exercises the FOR-01 CRUD framework
  # (Requirement 9.2).

  Background:
    Given the application stack is ready
    And I am logged in as the seeded admin

  Scenario: The work catalog list renders and a created work item appears in it
    When I open the Work Catalog page
    Then the work catalog list renders
    When I create a work item selecting a category and a unit
    Then the created work item appears in the work catalog list
