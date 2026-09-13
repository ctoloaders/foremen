@smoke @FOR-03
Feature: Employee password login
  # FOR-03 authentication smoke coverage (Requirements 5.1, 5.2, 5.3).
  # Validates the core email + password sign-in on /login: a valid admin login
  # lands on / with both tokens stored, empty fields are blocked client-side
  # without hitting the API, and invalid credentials surface a localized error
  # while keeping the user on /login.

  Background:
    Given the application stack is ready
    And I am on the login page

  Scenario: Admin logs in with valid credentials and tokens are stored
    When I log in with the seeded admin credentials
    Then I land on the home page
    And access and refresh tokens are stored

  Scenario: Empty fields are blocked by client validation with no request
    When I submit the login form without filling any field
    Then submission is blocked by client validation
    And no login request is sent
    And I stay on the login page

  Scenario: Invalid credentials show a localized error and stay on login
    When I log in with invalid credentials
    Then a localized login error message is shown
    And I stay on the login page
    And no tokens are stored
