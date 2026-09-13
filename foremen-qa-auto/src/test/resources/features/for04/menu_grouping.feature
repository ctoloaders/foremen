@smoke @FOR-04
Feature: Catalog and Dictionaries navigation groups
  # FOR-04 base-entities smoke coverage (Requirements 8.6, 4.1, FOR-04-15).
  # The "Catalog" and "Dictionaries" navigation groups are present in the shell
  # and their items route to the correct pages: Catalog -> Work Catalog
  # (/catalog/works) + Work Prices (/catalog/prices); Dictionaries -> the nine
  # flat dictionary routes (a representative subset is verified to route).

  Background:
    Given the application stack is ready
    And I am logged in as the seeded admin

  Scenario: The Catalog and Dictionaries nav groups are present and route correctly
    Then the Catalog navigation group items route correctly
    And the Dictionaries navigation group items route correctly
