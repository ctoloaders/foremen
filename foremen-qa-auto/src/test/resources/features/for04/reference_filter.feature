@smoke @FOR-04
Feature: Reference filter narrows a list
  # FOR-04 base-entities smoke coverage (Requirements 8.5, 4.1).
  # On the Work Catalog list, a reference-backed column (work category) exposes a
  # filter popover. Applying a single-value reference filter narrows the list to
  # the rows matching the picked category. Two run-created work items in two
  # different run-created categories are set up via the API so the narrowing is
  # observable and deterministic; all fixtures are torn down afterward
  # (self-cleaning, Requirement 3).

  Background:
    Given the application stack is ready
    And I am logged in as the seeded admin

  Scenario: A single-value reference filter narrows the work catalog list
    When two work items in two different categories exist
    And I open the Work Catalog page
    Then the work catalog list renders
    And applying a single-value category filter narrows the list
