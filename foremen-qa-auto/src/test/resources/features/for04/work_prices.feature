@smoke @FOR-04
Feature: Work Prices — list and current price
  # FOR-04 base-entities smoke coverage (Requirements 8.3, 4.1).
  # The Work Prices page renders the list and shows a current price (validTo
  # null, rendered with an open-ended "—" validTo and a "current" badge). A full
  # chain (unit -> category -> work item -> seeded currency -> open-ended price)
  # is set up via the API with LIFO teardown so the assertion is deterministic
  # on a fresh stack and the run stays self-cleaning (Requirement 3).

  Background:
    Given the application stack is ready
    And I am logged in as the seeded admin

  Scenario: The work prices list renders and a current price is shown
    When a current work price exists
    And I open the Work Prices page
    Then the work prices list renders and a current price is shown
