@smoke @FOR-02
Feature: Admin app shell renders with usable navigation
  # FOR-02 admin-panel smoke coverage (Requirements 7.1, 4.1).
  # When an authenticated user loads the app, the SPA shell (topbar + sidebar
  # navigation on desktop) renders and the primary navigation is operable —
  # clicking primary nav items routes to their pages. This is the known-good
  # foundation the rest of the admin panel builds on.

  Background:
    Given the application stack is ready
    And I am logged in as the seeded admin

  Scenario: The authenticated shell renders and primary navigation is usable
    Then the application shell renders
    And the primary navigation is usable
