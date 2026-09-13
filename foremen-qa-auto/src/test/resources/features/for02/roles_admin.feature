@smoke @FOR-02
Feature: Roles admin — list and access matrix
  # FOR-02 admin-panel smoke coverage (Requirements 7.2, 4.1).
  # The Roles page renders the roles list, and its Matrix tab shows the access
  # matrix: a grid of role rows x resource columns with toggleable C/R/U/D
  # operation controls. This slice verifies both the list and the matrix render
  # for the seeded ADMIN.

  Background:
    Given the application stack is ready
    And I am logged in as the seeded admin

  Scenario: The roles list and the access matrix render
    When I open the Roles page
    Then the roles list renders
    When I open the access matrix
    Then the access matrix is shown
