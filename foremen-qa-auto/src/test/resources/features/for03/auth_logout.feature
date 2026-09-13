@smoke @FOR-03
Feature: Logout clears the session
  # FOR-03 authentication smoke coverage (Requirement 5.5).
  # An authenticated user who triggers logout has their stored tokens cleared
  # and is redirected back to /login.

  Background:
    Given the application stack is ready

  Scenario: Logout clears tokens and redirects to login
    Given I am logged in as the seeded admin
    And access and refresh tokens are stored
    When I log out
    Then I am redirected to the login page
    And no tokens are stored
