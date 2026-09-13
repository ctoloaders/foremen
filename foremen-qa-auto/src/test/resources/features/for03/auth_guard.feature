@smoke @FOR-03
Feature: Protected deep-link guard round-trip
  # FOR-03 authentication smoke coverage (Requirement 5.4).
  # An unauthenticated user who opens a protected deep-link is redirected to
  # /login; after a successful login they are returned to the original deep-link
  # (Return_Location round-trip).

  Background:
    Given the application stack is ready

  Scenario: Unauthenticated deep-link redirects to login then returns after login
    When I open the protected deep-link "/users" while unauthenticated
    Then I am redirected to the login page
    When I log in with the seeded admin credentials
    Then I am returned to the deep-link "/users"
