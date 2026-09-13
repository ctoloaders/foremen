@smoke @FOR-04
Feature: Dictionary admin pages load and render their tables
  # FOR-04 base-entities smoke coverage (Requirements 8.1, 4.1, 9.2).
  # A data-driven Scenario Outline opens each of the nine flat dictionary routes
  # and verifies the page loads and its shared DataTable renders with columns.
  # This transitively exercises the FOR-01 CRUD list/i18n paths (Requirement 9.2).

  Background:
    Given the application stack is ready
    And I am logged in as the seeded admin

  Scenario Outline: The dictionary page at <route> loads and its table renders
    When I open the dictionary page "<route>"
    Then the dictionary page loads and its table renders with columns

    Examples:
      | route                 |
      | /measurement-units    |
      | /currencies           |
      | /vat-rates            |
      | /room-types           |
      | /work-categories      |
      | /delivery-categories  |
      | /delivery-statuses    |
      | /material-categories  |
      | /offer-packages       |
