@smoke @FOR-02
Feature: Appearance settings — theme persistence
  # FOR-02 admin-panel smoke coverage (Requirements 7.4, 4.1).
  # On the Appearance settings page, toggling the theme mode (dark <-> light)
  # persists across a full page reload: the selection is remembered, the
  # preference is stored in localStorage, and the applied theme (the `dark`
  # class on <html>) survives the reload.

  Background:
    Given the application stack is ready
    And I am logged in as the seeded admin

  Scenario: Toggling the theme persists across a page reload
    When I open the Appearance settings page
    And I toggle the theme mode
    Then the theme selection persists across a page reload
